const fs = require('fs');
const p = 'src/main/java/com/veriborder/border/BorderService.java';
let t = fs.readFileSync(p, 'utf8');
if (!t.includes('REASON_NO_MATCHING_CODE')) {
  t = t.replace('    public static final String REASON_SESSION_RECORD_MISSING =',
    '    public static final String REASON_NO_MATCHING_CODE = "verify.veriborder.reason.no_matching_code";\n' +
    '    public static final String REASON_SESSION_RECORD_MISSING =');
  fs.writeFileSync(p, t);
  console.log('const added');
}
const texts = {
  en_us: 'No matching exit code on this document.',
  zh_cn: '证件上没有匹配的出境编码。'
};
for (const [lang, txt] of Object.entries(texts)) {
  const q = 'src/main/resources/assets/veriborder/lang/' + lang + '.json';
  const m = JSON.parse(fs.readFileSync(q, 'utf8'));
  m['verify.veriborder.reason.no_matching_code'] = txt;
  fs.writeFileSync(q, JSON.stringify(m, null, 2) + '\n', 'utf8');
}
console.log('lang ok');
