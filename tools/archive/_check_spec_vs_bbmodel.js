// Compare a tool-created .bbmodel against the spec the builder script would emit,
// so a transcription slip in a blockbench_create call cannot pass silently.
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const ROOT = path.resolve(__dirname, '..');
const ids = process.argv.slice(2);
if (!ids.length) ids.push('entry_stamp', 'exit_stamp', 'stamp_history', 'print_preview');

let bad = 0;
for (const id of ids) {
  const spec = JSON.parse(execSync('node tools/r9_build_stamp_print.js --spec ' + id, { cwd: ROOT, encoding: 'utf8' }));
  const model = JSON.parse(fs.readFileSync(path.join(ROOT, 'assets', 'models', id + '.bbmodel'), 'utf8'));
  const problems = [];
  const texName = new Map(model.textures.map((t) => [String(t.id), t.name]));
  const texPath = new Map(spec.textures.map((t) => [t.name, t.path]));
  for (const t of model.textures) {
    if (texPath.get(t.name) !== t.path) problems.push(`texture ${t.name}: ${t.path} != ${texPath.get(t.name)}`);
  }
  if (model.elements.length !== spec.elements.length) {
    problems.push(`element count ${model.elements.length} != ${spec.elements.length}`);
  }
  spec.elements.forEach((want, i) => {
    const got = model.elements[i];
    if (!got) return;
    if (got.name !== want.name) problems.push(`#${i} name ${got.name} != ${want.name}`);
    for (const k of ['from', 'to']) {
      if (got[k].some((v, j) => Math.abs(v - want[k][j]) > 1e-6)) {
        problems.push(`${want.name}.${k} ${JSON.stringify(got[k])} != ${JSON.stringify(want[k])}`);
      }
    }
    for (const f of ['north', 'south', 'east', 'west', 'up', 'down']) {
      const gf = got.faces && got.faces[f];
      const wantName = want.faces[f].texture;
      if (!gf) { problems.push(`${want.name}.${f} missing`); continue; }
      if (texName.get(String(gf.texture)) !== wantName) {
        problems.push(`${want.name}.${f} texture ${texName.get(String(gf.texture))} != ${wantName}`);
      }
      if (JSON.stringify(gf.uv) !== '[0,0,16,16]') problems.push(`${want.name}.${f} uv ${JSON.stringify(gf.uv)}`);
    }
  });
  if (problems.length) { bad++; console.log(id + ': MISMATCH'); for (const p of problems.slice(0, 12)) console.log('  - ' + p); }
  else console.log(id + ': ok (' + spec.elements.length + ' elements match the builder spec)');
}
process.exit(bad ? 1 : 0);
