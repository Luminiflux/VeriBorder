// Runs the same validator that the blockbench_validate tool uses (dsh-blockbench
// lib/bbmodel.js validateModel) over the R9 terminal models and prints only the
// verdict, so 8 models can be checked without dumping each full summary.
//
//   node tools/qa_r9_validate.mjs [id ...]
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const here = path.dirname(fileURLToPath(import.meta.url))
const root = path.resolve(here, '..')
const plugin = 'C:/Users/GhostBubbles/.dsh/profiles/web/node_modules/dsh-blockbench/lib/bbmodel.js'
const { validateModel } = await import(pathToFileURL(plugin).href)

const ids = process.argv.slice(2).length
  ? process.argv.slice(2)
  : ['hotel_reader', 'desk_scanner', 'manual_terminal', 'airport_checkin',
     'station_verifier', 'blacklist_terminal', 'loss_terminal', 'renewal_machine']

let bad = 0
for (const id of ids) {
  const file = path.join(root, 'assets', 'models', id + '.bbmodel')
  const model = JSON.parse(fs.readFileSync(file, 'utf8'))
  const r = validateModel(model, path.dirname(file))
  const ok = r.passed && r.warnings.length === 0
  if (!ok) bad++
  console.log(`${id.padEnd(20)} ${ok ? 'PASS' : 'FAIL'}  errors ${r.errors.length}  warnings ${r.warnings.length}` +
    (r.errors.length ? '\n  E: ' + r.errors.join('; ') : '') +
    (r.warnings.length ? '\n  W: ' + r.warnings.join('; ') : ''))
}
console.log(bad ? `\nvalidate: ${bad} model(s) not clean` : '\nvalidate: all clean (0 errors, 0 warnings)')
process.exit(bad ? 1 : 0)
