const fs = require('fs');
const base = 'src/main/resources/assets/veriborder/lang/';

const edits = {
  en_us: {
    'device.veriborder.endorsement_machine.desc': 'Issue, renew and revoke endorsements.',
    'device.veriborder.self_endorsement.desc': 'Self-service kiosk for issuing endorsements. Authority and fixed code are administrator-configured.',
    'screen.veriborder.endorsement.no_target2': 'Hold a permit or place one in your inventory.',
    'message.veriborder.endorsement.no_permit': 'No permit selected.'
  },
  zh_cn: {
    'device.veriborder.permit_issuer.desc': '签发通行证，并可直接添加初始签注。',
    'device.veriborder.endorsement_machine.desc': '为通行证办理、续期、注销各类签注。',
    'device.veriborder.self_endorsement.desc': '自助办理通行证签注；签发机关与固定编码由管理员配置。',
    'screen.veriborder.endorsement.target': '目标通行证',
    'message.veriborder.endorsement.no_permit': '未选择通行证。'
  }
};

for (const [lang, map] of Object.entries(edits)) {
  const p = base + lang + '.json';
  const m = JSON.parse(fs.readFileSync(p, 'utf8'));
  for (const [k, v] of Object.entries(map)) {
    if (!(k in m)) console.log('MISSING', lang, k);
    m[k] = v;
  }
  fs.writeFileSync(p, JSON.stringify(m, null, 2) + '\n', 'utf8');
  console.log('updated', lang);
}
