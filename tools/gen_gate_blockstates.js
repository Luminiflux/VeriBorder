#!/usr/bin/env node
/**
 * VeriBorder gate blockstate generator (26.2.1).
 *
 * Writes the three gate blockstates from one declarative table:
 *
 *     properties -> value domain -> model mapping
 *
 * Why a generator: a gate blockstate is a 32-entry cartesian product
 * (4 facings x left x right x open). Hand-writing it means one wrong
 * `left=/right=` pair or one missing `open=` value produces a blockstate whose
 * variant keys no longer cover every reachable state -- and vanilla renders the
 * **missing model** (the magenta/black cube) for the uncovered states. The table
 * below cannot drift from the cartesian product because the product *is* the
 * table.
 *
 * Usage
 *   node tools/gen_gate_blockstates.js            # rewrite the three files
 *   node tools/gen_gate_blockstates.js --check    # compare only, exit 1 on drift
 *   node tools/gen_gate_blockstates.js --print    # stdout only, no writes
 *
 * The output is byte-stable (LF, 2-space indent, one trailing newline) so
 * `--check` is a real guard and `ModelGoldenTest` keeps passing when nothing
 * changed: generated output is identical to the committed files.
 */

'use strict';

const fs = require('fs');
const path = require('path');

const PROJECT = path.join(__dirname, '..');
const BLOCKSTATES = path.join(PROJECT, 'src', 'main', 'resources', 'assets', 'veriborder', 'blockstates');
const MODELS = path.join(PROJECT, 'src', 'main', 'resources', 'assets', 'veriborder', 'models', 'block');

const NAMESPACE = 'veriborder';

/**
 * The block's real property set, in the order vanilla serialises it.
 * MUST stay in sync with `DeviceBlock.FACING` (HorizontalDirectionalBlock.FACING)
 * and `EGateBlock.OPEN` / `LEFT` / `RIGHT`; `GateBlockstateModelTest` reads those
 * properties off the real block classes and fails if this table drifts.
 */
const PROPERTIES = [
    { name: 'facing', values: ['north', 'east', 'south', 'west'] },
    { name: 'left', values: ['false', 'true'] },
    { name: 'right', values: ['false', 'true'] },
    { name: 'open', values: ['false', 'true'] },
];

/**
 * Property -> model selection. `open` is deliberately absent: the leaves are
 * drawn by `client/EGateRenderer`, so both values of `open` share one model and
 * the shell never changes when the doors swing.
 */
const Y_BY_FACING = { north: 0, east: 90, south: 180, west: 270 };

/** left x right -> model name suffix (the cap elements hidden on a joined side). */
function modelSuffix(left, right) {
    if (left === 'true' && right === 'true') return '_both';
    if (left === 'true') return '_left';
    if (right === 'true') return '_right';
    return '';
}

const GATES = [
    { id: 'egate', model: 'egate' },
    { id: 'exit_gate', model: 'exit_gate' },
    { id: 'dual_gate', model: 'dual_gate' },
];

/** Every combination of PROPERTIES, in declaration order (facing outermost). */
function combinations(properties = PROPERTIES) {
    let rows = [{}];
    for (const property of properties) {
        const next = [];
        for (const row of rows) {
            for (const value of property.values) {
                next.push({ ...row, [property.name]: value });
            }
        }
        rows = next;
    }
    return rows;
}

function variantKey(row, properties = PROPERTIES) {
    return properties.map((property) => `${property.name}=${row[property.name]}`).join(',');
}

function modelFor(gate, row) {
    return `${NAMESPACE}:block/${gate.model}${modelSuffix(row.left, row.right)}`;
}

/**
 * Renders one gate blockstate with the exact layout of the committed files:
 *
 *   {
 *     "variants": {
 *       "<key>": {
 *         "model": "veriborder:block/<name>",
 *         "y": <0|90|180|270>
 *       },
 *       ...
 *     }
 *   }
 */
function render(gate) {
    const rows = combinations();
    const lines = ['{', '  "variants": {'];
    rows.forEach((row, index) => {
        const comma = index === rows.length - 1 ? '' : ',';
        lines.push(`    "${variantKey(row)}": {`);
        lines.push(`      "model": "${modelFor(gate, row)}",`);
        lines.push(`      "y": ${Y_BY_FACING[row.facing]}`);
        lines.push(`    }${comma}`);
    });
    lines.push('  }');
    lines.push('}');
    return lines.join('\n') + '\n';
}

/** Builds the variant -> {model, y} map straight from the table (for reporting). */
function plan(gate) {
    return combinations().map((row) => ({
        key: variantKey(row),
        model: modelFor(gate, row),
        y: Y_BY_FACING[row.facing],
    }));
}

function main(argv) {
    const check = argv.includes('--check');
    const print = argv.includes('--print');

    let drift = 0;
    let written = 0;

    for (const gate of GATES) {
        const target = path.join(BLOCKSTATES, `${gate.id}.json`);
        const expected = render(gate);
        const exists = fs.existsSync(target);
        const actual = exists ? fs.readFileSync(target, 'utf8') : null;

        if (actual === expected) {
            if (!print) console.log(`same      ${gate.id}.json  (${plan(gate).length} variants)`);
            continue;
        }

        drift++;
        if (print) {
            console.log(`----- ${gate.id}.json -----`);
            process.stdout.write(expected);
            continue;
        }
        if (check) {
            console.log(`DRIFT     ${gate.id}.json  ${exists ? '(content differs)' : '(missing)'}`);
            continue;
        }
        fs.writeFileSync(target, expected, 'utf8');
        written++;
        console.log(`wrote     ${gate.id}.json  (${plan(gate).length} variants)`);
    }

    if (!check && !print && written === 0 && drift === 0) {
        console.log('\nall gate blockstates match the generator (no change)');
    }

    if (check && drift > 0) {
        console.error(`\n${drift} gate blockstate(s) differ from the generator; run without --check to rewrite them.`);
        return 1;
    }
    if (check) {
        console.log('\nall gate blockstates match the generator');
    }
    return 0;
}

if (require.main === module) {
    process.exitCode = main(process.argv.slice(2));
}

module.exports = { PROPERTIES, Y_BY_FACING, GATES, combinations, variantKey, modelSuffix, modelFor, render, plan };
