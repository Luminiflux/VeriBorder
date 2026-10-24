#!/usr/bin/env node
/* One-off: replace the remaining bare English defaults with lang keys. */
'use strict';
const fs = require('fs');
const path = require('path');
const ROOT = path.resolve(__dirname, '..');
const dir = path.join(ROOT, 'src/main/java/com/veriborder/client/screen');
const edits = [
  ['EndorsementScreen.java',
    'cached("authority", "Exit-Entry Administration")',
    'cached("authority", Component.translatable("screen.veriborder.endorsement.default_authority").getString())'],
  ['EndorsementScreen.java',
    'return "One year multiple";',
    'return Component.translatable("screen.veriborder.endorsement.default_template").getString();'],
  ['VisaIssuerScreen.java',
    'cached("country", "United States")',
    'cached("country", Component.translatable("screen.veriborder.visa.default_country").getString())'],
  ['VisaIssuerScreen.java',
    'cached("authority", "Embassy")',
    'cached("authority", Component.translatable("screen.veriborder.visa.default_authority").getString())'],
  ['VisaIssuerScreen.java',
    'return "Tourist visa";',
    'return Component.translatable("screen.veriborder.visa.default_template").getString();'],
  ['VisaIssuerScreen.java',
    'name.isBlank() ? "Visa template" : name',
    'name.isBlank() ? Component.translatable("screen.veriborder.visa.template_fallback").getString() : name'],
  ['IssuerScreen.java',
    'return "Standard";',
    'return Component.translatable("screen.veriborder.issuer.default_preset").getString();'],
  ['IssuerScreen.java',
    'data.putString("presetName", name.isBlank() ? "Standard" : name);',
    'data.putString("presetName", name.isBlank()\n                ? Component.translatable("screen.veriborder.issuer.default_preset").getString() : name);'],
  ['ModifierScreen.java',
    '"field.veriborder.authority", "Authority")',
    '"field.veriborder.authority", Component.translatable("field.veriborder.authority").getString())'],
  ['PrintPreviewScreen.java',
    'String watermark = "VERIBORDER \u00B7 OFFICIAL DOCUMENT PREVIEW";',
    'String watermark = Component.translatable("screen.veriborder.print.watermark").getString();'],
  ['GeneratorScreen.java',
    'style == DocStyle.CN_ID ? "Custom Identity Card" : "Custom Document"',
    'style == DocStyle.CN_ID\n                ? Component.translatable("screen.veriborder.generator.custom_id_name").getString()\n                : Component.translatable("screen.veriborder.generator.custom_doc_name").getString()'],
  ['GeneratorScreen.java',
    'tag.putString("customShortName", "CUSTOM");',
    'tag.putString("customShortName", Component.translatable("screen.veriborder.generator.custom_short").getString());'],
];
let patched = 0;
for (const [file, from, to] of edits) {
  const full = path.join(dir, file);
  let text = fs.readFileSync(full, 'utf8');
  if (text.includes(from)) {
    text = text.split(from).join(to);
    fs.writeFileSync(full, text, 'utf8');
    patched++;
  } else {
    console.log('MISS ' + file + ' :: ' + from.slice(0, 70));
  }
}
console.log('patched ' + patched + '/' + edits.length);
