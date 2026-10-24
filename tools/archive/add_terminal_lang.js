const fs = require('fs');
const base = 'src/main/resources/assets/veriborder/lang/';
const en = {
  'item.veriborder.border_terminal': 'Border Terminal',
  'screen.veriborder.terminal.title': 'Border Terminal',
  'screen.veriborder.terminal.status': 'Status',
  'screen.veriborder.terminal.force_exit': 'Force Exit',
  'screen.veriborder.terminal.force_entry': 'Force Entry',
  'screen.veriborder.terminal.clear_codes': 'Clear Codes',
  'screen.veriborder.terminal.add_code': 'Add Code',
  'screen.veriborder.terminal.code': 'Permanent code',
  'screen.veriborder.terminal.read_only': 'Read-only (OP 2 required to edit)',
  'message.veriborder.terminal.target_missing': 'That player is not online.',
  'message.veriborder.terminal.no_document': 'No document in that slot.',
  'message.veriborder.terminal.status': 'Document status set to %s.',
  'message.veriborder.terminal.code_invalid': 'Invalid code.',
  'message.veriborder.terminal.code_duplicate': 'That code is already on the document.',
  'message.veriborder.terminal.code_missing': 'That code is not on the document.',
  'message.veriborder.terminal.unchanged': 'Nothing changed.',
  'message.veriborder.terminal.saved': 'Document %s saved.',
  'message.veriborder.terminal.no_session': '%s has no active entry.',
  'message.veriborder.terminal.force_exit': 'Cleared the entry of %s.',
  'message.veriborder.terminal.already_entered': '%s is already inside.',
  'message.veriborder.terminal.force_entry': 'Created an entry for %s.'
};
const zh = {
  'item.veriborder.border_terminal': '边检终端',
  'screen.veriborder.terminal.title': '边检终端',
  'screen.veriborder.terminal.status': '状态',
  'screen.veriborder.terminal.force_exit': '强制出境',
  'screen.veriborder.terminal.force_entry': '强制入境',
  'screen.veriborder.terminal.clear_codes': '清空编码',
  'screen.veriborder.terminal.add_code': '添加编码',
  'screen.veriborder.terminal.code': '永久编码',
  'screen.veriborder.terminal.read_only': '只读（需要 OP 2 才能修改）',
  'message.veriborder.terminal.target_missing': '该玩家不在线。',
  'message.veriborder.terminal.no_document': '该槽位没有证件。',
  'message.veriborder.terminal.status': '证件状态已设为 %s。',
  'message.veriborder.terminal.code_invalid': '编码无效。',
  'message.veriborder.terminal.code_duplicate': '该编码已在证件上。',
  'message.veriborder.terminal.code_missing': '证件上没有该编码。',
  'message.veriborder.terminal.unchanged': '没有改动。',
  'message.veriborder.terminal.saved': '证件 %s 已保存。',
  'message.veriborder.terminal.no_session': '%s 没有有效入境记录。',
  'message.veriborder.terminal.force_exit': '已清除 %s 的入境记录。',
  'message.veriborder.terminal.already_entered': '%s 已在境内。',
  'message.veriborder.terminal.force_entry': '已为 %s 建立入境记录。'
};
for (const [lang, add] of [['en_us', en], ['zh_cn', zh]]) {
  const p = base + lang + '.json';
  const m = JSON.parse(fs.readFileSync(p, 'utf8'));
  Object.assign(m, add);
  fs.writeFileSync(p, JSON.stringify(m, null, 2) + '\n', 'utf8');
  console.log(lang, Object.keys(m).length);
}
