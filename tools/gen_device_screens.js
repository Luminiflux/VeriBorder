#!/usr/bin/env node
'use strict';
/*
 * VeriBorder 26.4.1 — per-device screen UI generator.
 *
 *   node tools/gen_device_screens.js            # write the textures
 *   node tools/gen_device_screens.js --dry      # render to tools/preview/_screens_new only
 *   node tools/gen_device_screens.js --zoom 8   # additionally dump 8x upscales for review
 *
 * WHY THIS EXISTS
 * ---------------
 * All 19 non-gate devices declared `"screen": "veriborder:block/r9_screen_ui"`,
 * so every terminal showed the exact same dark-navy panel with the same four
 * bars. tools/rebind_device_screens.js repoints each model at its own
 * <device>_screen.png; this script draws those 19 screens.
 *
 * DESIGN SYSTEM (see tools/lib/screen_kit.js)
 *   - one 3x5 glyph grid, 1 px stroke, 4 px advance — shared by every screen
 *   - one 1 px rounded border rule, corners cut
 *   - one shared state palette (ok / warn / err / info)
 *   - exactly one accent hue per device class, used for identity + CTA
 *   - backgrounds stay below 0x14 luminance so accents read as emissive
 *   - alpha is binary (255 or 0); nothing is feathered
 *
 * Canvas sizes are limited to 32x32 / 64x32 / 32x64 / 64x64 because
 * tools/preview_models.js asserts every declared texture is 32 or 64 px on
 * both axes. The size per device is picked to approximate that device's real
 * screen-quad aspect ratio so the art is not badly stretched in world space.
 */

const path = require('path');
const fs = require('fs');
const K = require('./lib/screen_kit');
const { C, hex, mix, dark, baseScreen, header, textW } = K;

const ROOT = path.resolve(__dirname, '..');
const TEX = path.join(ROOT, 'src', 'main', 'resources', 'assets', 'veriborder', 'textures', 'block');
const PREV = path.join(ROOT, 'tools', 'preview');

const argv = process.argv.slice(2);
const DRY = argv.includes('--dry');
const ZOOM = (() => {
  const i = argv.indexOf('--zoom');
  return i >= 0 ? Math.max(1, parseInt(argv[i + 1], 10) || 4) : 0;
})();

// ---------------------------------------------------------------------------
// Shared secondary state: the "verified" screen used by <device>_screen2.png.
// Kept generic on purpose — it is the same confirmation pattern on every
// terminal, differentiated only by the device accent and its short code.
// ---------------------------------------------------------------------------
function resultScreen(w, h, A, code) {
  const cv = baseScreen(w, h, A);
  header(cv, A, code, C.ok);
  const wide = w >= h;
  const R = Math.max(6, Math.min(13, Math.round(Math.min(w, h) / 2) - 9));

  if (wide) {
    const cx = Math.round(R + 6), cy = Math.round(h / 2) + 1;
    cv.ring(cx, cy, R, dark(C.ok, 0.35));
    cv.ring(cx, cy, R - 1, C.ok);
    cv.bigCheck(cx, cy, R * 1.35, C.ok);
    const tx = cx + R + 5;
    cv.text(tx, cy - 6, 'VERIFIED', C.ok);
    cv.hl(tx, cy - 1, Math.min(w - tx - 3, 34), 1, dark(C.ok, 0.55));
    cv.bar(tx, cy + 2, Math.min(w - tx - 3, 34), 4, 1, C.ok, C.bg3);
    return cv;
  }

  const cx = Math.round(w / 2);
  const cy = h <= 40 ? Math.round(h / 2) + 2 : Math.round(h * 0.38);
  cv.ring(cx, cy, R, dark(C.ok, 0.35));
  cv.ring(cx, cy, R - 1, C.ok);
  cv.bigCheck(cx, cy, R * 1.35, C.ok);
  const label = w < 48 ? 'DONE' : 'VERIFIED';
  cv.textCenter(cx, Math.min(h - 16, cy + R + 6), label, C.ok);
  cv.bar(3, h - 9, w - 6, 4, 1, C.ok, C.bg3);
  return cv;
}

// ---------------------------------------------------------------------------
// 19 device screens
// ---------------------------------------------------------------------------
const DEVICES = [
  // ======================================================= 1 身份证发行机
  {
    id: 'id_issuer', w: 64, h: 32, accent: '#4FA8FF', code: 'ID',
    cn: '身份证发行机', file: 'id_issuer_screen',
    ui: '证件预览缩略 + 字段列表 + 「签发」按钮',
    note: '左侧是待制证的证件缩略（人像 + 打码字段 + 版号条码），右侧三行「标签条 + 值条」，底部整条强调色 CTA。',
    draw(cv, A) {
      header(cv, A, 'ID ISSUE', C.ok);
      // --- 证件预览缩略 (card preview thumbnail)
      const cx = 3, cy = 9, cw = 21, ch = 20;
      cv.panel(cx, cy, cw, ch, C.bg2);
      cv.round(cx, cy, cw, ch, dark(A, 0.45));
      cv.fill(cx + 2, cy + 2, 9, 16, C.bg3);         // 人像框
      cv.disc(cx + 6, cy + 6, 3, C.icon);            // 头
      cv.fill(cx + 2, cy + 11, 9, 5, C.icon);        // 肩
      cv.hl(cx + 12, cy + 3, 7, 2, A);               // 打码字段
      cv.hl(cx + 12, cy + 7, 6, 2, C.dim);
      cv.hl(cx + 12, cy + 11, 7, 2, C.dim);
      cv.barcode(cx + 12, cy + 15, 7, 3, C.dim, 0x51D);
      // --- 字段列表：标签条 + 值条（表单语言）
      [18, 14, 25].forEach((w, i) => {
        const y = 10 + i * 5;
        cv.hl(26, y, 8, 2, C.dim);
        cv.hl(36, y, w, 2, i === 2 ? A : C.fg);
      });
      // --- 签发按钮 (issue CTA)
      cv.panel(26, 23, 35, 7, A);
      cv.text(30, 24, 'ISSUE', C.void);
      cv.textRight(58, 24, '>', C.void);
    }
  },

  // ======================================================= 2 护照发行机
  {
    id: 'passport_issuer', w: 64, h: 64, accent: '#E85D5D', code: 'PASSPORT',
    cn: '护照发行机', file: 'passport_issuer_screen',
    ui: 'MRZ 两条样张 + 国徽占位 + 打印进度条',
    note: '左上国徽占位（双环 + 五角星），右上证件字段，中部打印进度，底部两条等宽 MRZ 样张。',
    draw(cv, A) {
      header(cv, A, 'PASSPORT', C.ok);
      // --- 国徽占位 (national emblem placeholder)
      cv.ring(13, 24, 9, A);
      cv.ring(13, 24, 7, dark(A, 0.5));
      cv.star(13, 23, 4, A);
      // --- 字段
      [['TYPE', 13], ['NAME', 19], ['NO', 25]].forEach(([lab, y]) => {
        cv.text(28, y, lab, C.dim);
        cv.hl(48, y + 1, 13, 2, lab === 'NO' ? A : C.fg);
      });
      // --- 打印进度条 (print progress)
      cv.text(3, 35, 'PRINT', C.dim);
      cv.bar(3, 41, 43, 5, 0.62, A, C.bg3);
      cv.text(48, 41, '62%', A);
      // --- MRZ 两条样张
      cv.panel(3, 48, 58, 14, C.bg2);
      cv.round(3, 48, 58, 14, C.line);
      cv.text(5, 50, 'P<CHNVERIBORD', C.fg);
      cv.text(5, 56, '9F3K2M7X4Q1P6', C.fg);
    }
  },

  // ======================================================= 3 通行证发行机
  {
    id: 'permit_issuer', w: 64, h: 32, accent: '#35D9C0', code: 'PERMIT',
    cn: '通行证发行机', file: 'permit_issuer_screen',
    ui: '签注章预览 + 次数徽章 + 「加签」',
    note: '左侧圆形签注章预览（双环），右侧次数徽章 X2 与强调色「加签」按钮，底部次数 pip 条。',
    draw(cv, A) {
      header(cv, A, 'PERMIT', C.ok);
      // --- 签注章预览 (endorsement stamp preview)
      cv.ring(14, 21, 9, A);
      cv.ring(14, 21, 7, dark(A, 0.55));
      cv.text(9, 19, 'END', A);
      cv.star(14, 27, 2, dark(A, 0.3));
      // --- 次数徽章 (times badge)
      cv.panel(27, 10, 13, 9, C.bg2);
      cv.round(27, 10, 13, 9, A);
      cv.text(30, 12, 'X2', A);
      // --- 加签按钮
      cv.panel(42, 10, 19, 9, A);
      cv.text(45, 12, '+', C.void);
      cv.text(49, 12, 'ADD', C.void);
      // --- 次数 pip
      cv.text(27, 21, 'TIMES', C.dim);
      cv.pips(27, 26, 3, 4, 6, 4, 2, A);
    }
  },

  // ======================================================= 4 签证签发机
  {
    id: 'visa_issuer', w: 64, h: 32, accent: '#B980FF', code: 'VISA',
    cn: '签证签发机', file: 'visa_issuer_screen',
    ui: '贴纸样式签证 + 有效期 + 条形码',
    note: '左侧是一张「贴纸签证」（带右下折角），右侧有效期与条形码——与通行证的圆形章形成明显区分。',
    draw(cv, A) {
      header(cv, A, 'VISA', C.ok);
      // --- 贴纸签证 (sticker-style visa)
      cv.panel(3, 9, 27, 21, C.bg2);
      cv.round(3, 9, 27, 21, A);
      cv.hl(5, 11, 23, 2, A);
      cv.hl(5, 15, 18, 1, C.dim);
      cv.hl(5, 18, 21, 1, C.dim);
      cv.hl(5, 21, 15, 1, C.dim);
      // 右下折角 (peel corner)
      for (let i = 0; i < 7; i++) cv.hl(23 + i, 28 - i, 7 - i, 1, C.bg3);
      cv.thickLine(22, 29, 29, 22, A, 1);
      // --- 有效期
      cv.text(32, 10, 'VALID', C.dim);
      cv.text(32, 16, '2029-12', C.fg);
      // --- 条形码
      cv.barcode(32, 24, 29, 5, C.fg, 0x7A31);
    }
  },

  // ======================================================= 5 签注办理机
  {
    id: 'endorsement_machine', w: 32, h: 64, accent: '#FFB43D', code: 'ENDORSE',
    cn: '签注办理机', file: 'endorsement_machine_screen',
    ui: '签注类型选择 + 次数',
    note: '竖屏：三行类型选择（首行高亮 + 实心单选点），底部次数 pip 与 NEXT。竖版布局与横屏机型一眼分开。',
    draw(cv, A) {
      header(cv, A, 'ENDORSE', C.ok);
      cv.text(3, 10, 'TYPE', C.dim);
      [['GRP', true], ['IND', false], ['TRN', false]].forEach(([lab, on], i) => {
        const y = 16 + i * 10;
        cv.panel(3, y, 26, 8, on ? dark(A, 0.82) : C.bg2);
        cv.round(3, y, 26, 8, on ? A : C.line);
        cv.radio(8, y + 4, on, A);
        cv.text(13, y + 2, lab, on ? A : C.dim);
      });
      cv.text(3, 46, 'TIMES', C.dim);
      cv.pips(3, 50, 3, 4, 5, 5, 2, A);
      cv.panel(3, 56, 26, 6, A);
      cv.text(9, 57, 'NEXT', C.void);
    }
  },

  // ======================================================= 6 自助签证机
  {
    id: 'self_visa', w: 64, h: 64, accent: '#6B7BFF', code: 'SELF VISA',
    cn: '自助签证机', file: 'self_visa_screen',
    ui: '步骤条（1 选类型 2 支付 3 打印）+ 触摸提示',
    note: '左侧纵向步骤条（1 已完成实心 / 2-3 空心），底部「TOUCH TO START」触摸条——自助机专属的引导语言。',
    draw(cv, A) {
      header(cv, A, 'SELF VISA', C.ok);
      const steps = [['1', 'PICK TYPE', true], ['2', 'PAYMENT', false], ['3', 'PRINT', false]];
      steps.forEach(([n, lab, on], i) => {
        const cy = 17 + i * 14;
        if (on) {
          cv.disc(11, cy, 5, A);
          cv.text(10, cy - 2, n, C.void);
        } else {
          cv.ring(11, cy, 5, C.dim);
          cv.text(10, cy - 2, n, C.dim);
        }
        cv.text(21, cy - 2, lab, on ? A : C.dim);
        if (i < 2) cv.vl(11, cy + 6, 3, C.line);
      });
      // --- 触摸提示
      cv.panel(3, 52, 58, 9, C.bg2);
      cv.round(3, 52, 58, 9, A);
      cv.text(4, 54, 'TOUCH TO START', A);
    }
  },

  // ======================================================= 7 自助签注机
  {
    id: 'self_endorsement', w: 64, h: 64, accent: '#FF7CC6', code: 'SELF ENDORSE',
    cn: '自助签注办理机', file: 'self_endorsement_screen',
    ui: '步骤条（1 选类型 2 支付 3 打印）+ 触摸提示',
    note: '横向步骤条 + 两张签注方案卡（首张选中）+ 底部指纹环「PLACE FINGER」。与自助签证共用步骤语言但布局横/纵、图标不同。',
    draw(cv, A) {
      header(cv, A, 'SELF ENDORSE', C.ok);
      // --- 横向步骤条
      const xs = [14, 32, 50], labs = ['1', '2', '3'];
      xs.forEach((cx, i) => {
        if (i === 0) { cv.disc(cx, 14, 5, A); cv.text(cx - 1, 12, labs[i], C.void); }
        else { cv.ring(cx, 14, 5, C.dim); cv.text(cx - 1, 12, labs[i], C.dim); }
        if (i < 2) cv.hl(cx + 6, 14, xs[i + 1] - cx - 12, 1, i === 0 ? dark(A, 0.4) : C.line);
      });
      // --- 签注方案卡
      [['ENDORSE 1X', true], ['ENDORSE 2X', false]].forEach(([lab, on], i) => {
        const y = 24 + i * 14;
        cv.panel(3, y, 58, 12, on ? dark(A, 0.82) : C.bg2);
        cv.round(3, y, 58, 12, on ? A : C.line);
        cv.radio(8, y + 6, on, A);
        cv.text(14, y + 3, lab, on ? A : C.dim);
        cv.hl(14, y + 9, 20, 1, on ? dark(A, 0.4) : C.line);
      });
      // --- 触摸提示 + 指纹
      cv.panel(3, 51, 58, 9, C.bg2);
      cv.round(3, 51, 58, 9, A);
      [4, 3, 2].forEach((r) => cv.ring(11, 55, r, A));
      cv.text(19, 54, 'TOUCH HERE', C.fg);
    }
  },

  // ======================================================= 8 人工核验终端
  {
    id: 'manual_terminal', w: 64, h: 64, accent: '#45E2FF', code: 'BORDER',
    cn: '前台人工核验终端', file: 'manual_terminal_screen',
    ui: '玩家列表 + 状态徽章（IN / OUT）',
    note: '三行旅客卡片：头像 + 姓名条 + 证件条 + IN/OUT 徽章（绿=入境、红=出境）。唯一的「多人列表」界面。',
    draw(cv, A) {
      header(cv, A, 'BORDER CHECK', C.ok);
      cv.text(3, 11, 'MANUAL', C.dim);
      [['IN', true], ['OUT', false], ['IN', true]].forEach(([badge, ok], i) => {
        const y = 18 + i * 12;
        cv.panel(3, y, 58, 11, C.bg2);
        cv.round(3, y, 58, 11, C.line);
        cv.fill(5, y + 2, 8, 8, C.bg3);
        cv.disc(9, y + 4, 2, C.icon);
        cv.fill(6, y + 8, 6, 2, C.icon);
        cv.hl(16, y + 3, 20, 2, C.fg);
        cv.hl(16, y + 7, 12, 2, C.dim);
        const bc = ok ? C.ok : C.err;
        cv.pips(48, y + 2, 1, 1, 12, 7, 0, bc);
        cv.text(badge === 'OUT' ? 48 : 51, y + 3, badge, C.void);
      });
      cv.text(3, 56, 'PAX 03', C.dim);
      cv.put(59, 58, C.ok);
    }
  },

  // ======================================================= 9 车站实名核验机
  {
    id: 'station_verifier', w: 64, h: 64, accent: '#62E67E', code: 'VERIFY',
    cn: '车站实名核验机', file: 'station_verifier_screen',
    ui: '护照插口示意 + 「请放置证件」',
    note: '证件书悬浮在插口上方，强调色下箭头指向插槽开口，最底一行 PLACE DOC。以「物理插口」为核心的唯一界面。',
    draw(cv, A) {
      header(cv, A, 'VERIFY', C.warn);
      cv.text(3, 10, 'INSERT DOC', C.dim);
      // --- 证件书（带书脊）
      cv.panel(22, 17, 20, 22, C.bg3);
      cv.round(22, 17, 20, 22, C.icon);
      cv.vl(24, 19, 18, dark(A, 0.5));
      cv.ring(33, 25, 5, A);
      cv.hl(28, 33, 11, 1, C.dim);
      cv.hl(28, 36, 9, 1, C.dim);
      // --- 粗下箭头
      cv.fill(30, 40, 3, 6, A);
      for (let i = 0; i < 4; i++) cv.hl(31 - i, 46 + i, 1 + 2 * i, 1, A);
      // --- 插口
      cv.fill(18, 51, 28, 5, C.bg3);
      cv.hl(18, 51, 28, 1, C.icon);
      cv.hl(18, 55, 28, 1, C.icon);
      cv.hl(18, 53, 28, 1, C.void);
      // --- 提示
      cv.text(14, 57, 'PLACE DOC', A);
    }
  },

  // ======================================================= 10 入境盖章机
  {
    id: 'entry_stamp', w: 64, h: 32, accent: '#FF8A45', code: 'ENTRY',
    cn: '入境盖章机', file: 'entry_stamp_screen',
    ui: '印章图案 + 日期（下箭头 = IN）',
    note: '左侧圆形入境章（双环 + ENTRY 字样），右侧日期与向下箭头。出境章为镜像 + 三重细环 + 上箭头，两者成对。',
    draw(cv, A) {
      header(cv, A, 'ENTRY STAMP', C.ok);
      // --- 印章
      cv.ring(15, 21, 9, A);
      cv.ring(15, 21, 7, dark(A, 0.55));
      cv.text(6, 19, 'ENTRY', A);
      cv.hl(10, 16, 11, 1, dark(A, 0.35));
      // --- 日期 + 方向
      cv.text(30, 10, 'DATE', C.dim);
      cv.text(30, 16, '26-10-25', C.fg);
      cv.arrowDown(31, 23, 7, A);
      cv.text(36, 24, 'IN', A);
      cv.text(47, 24, 'AUTO', C.dim);
    }
  },

  // ======================================================= 11 出境盖章机
  {
    id: 'exit_stamp', w: 64, h: 32, accent: '#FFDD4D', code: 'EXIT',
    cn: '出境盖章机', file: 'exit_stamp_screen',
    ui: '印章图案 + 日期（上箭头 = OUT）',
    note: '入境章的镜像：章在右、日期在左、箭头向上。印章由「圆形双环」改为「方形内框」+ 内嵌上行箭头——同一家族、相反方向、不同印型。',
    draw(cv, A) {
      header(cv, A, 'EXIT STAMP', C.ok);
      // --- 方形出境章（与入境圆章成对）
      cv.panel(38, 11, 21, 20, dark(A, 0.84));
      cv.round(38, 11, 21, 20, A);
      cv.round(40, 13, 17, 16, dark(A, 0.45));
      cv.arrowUp(48, 15, 5, A);
      cv.text(43, 23, 'OUT', A);
      // --- 日期
      cv.text(3, 10, 'DATE', C.dim);
      cv.text(3, 16, '26-10-25', C.fg);
      cv.text(3, 24, 'AUTO', C.dim);
    }
  },

  // ======================================================= 12 酒店入住读卡器
  {
    id: 'hotel_reader', w: 64, h: 32, accent: '#C77BFF', code: 'HOTEL',
    cn: '酒店入住读卡器', file: 'hotel_reader_screen',
    ui: '证件卡片 + 校验结果勾',
    note: '极扁超宽屏（3:1）：左证件卡图标、中房号、右侧大面积绿色对勾徽章。横向阅读，唯一以「房号」为主体的界面。',
    draw(cv, A) {
      header(cv, A, 'HOTEL CHECKIN', C.ok);
      // --- 证件卡图标
      cv.panel(3, 10, 15, 10, C.bg2);
      cv.round(3, 10, 15, 10, A);
      cv.hl(5, 12, 9, 2, A);
      cv.hl(5, 16, 6, 2, C.dim);
      // --- 房号
      cv.text(21, 10, 'ROOM', C.dim);
      cv.text(21, 16, '1420', C.fg);
      // --- 底部条码 + 结果
      cv.barcode(3, 22, 14, 6, C.dim, 0x2C9);
      cv.text(19, 23, 'AUTH OK', C.ok);
      // --- 校验结果（勾）
      cv.disc(53, 16, 7, C.ok);
      cv.bigCheck(53, 16, 9, C.void);
    }
  },

  // ======================================================= 13 台式证件扫描仪
  {
    id: 'desk_scanner', w: 64, h: 32, accent: '#3DFFB0', code: 'SCAN',
    cn: '台式证件扫描仪', file: 'desk_scanner_screen',
    ui: '证件卡片 + 校验结果勾/叉',
    note: '证件横置于扫描台，「扫描光束」是一条高亮横带；右侧绿色勾徽章表示本次读取通过（同一位置换红色叉即失败态）。',
    draw(cv, A) {
      header(cv, A, 'SCAN', C.ok);
      // --- 证件卡
      cv.panel(3, 10, 34, 19, C.bg2);
      cv.round(3, 10, 34, 19, dark(A, 0.45));
      cv.fill(5, 12, 10, 15, C.bg3);
      cv.disc(10, 16, 2, C.icon);
      cv.fill(6, 19, 8, 5, C.icon);
      cv.hl(17, 13, 15, 2, C.fg);
      cv.hl(17, 17, 11, 2, C.dim);
      cv.hl(17, 21, 14, 2, C.dim);
      // --- 扫描光束
      cv.fill(4, 24, 32, 2, mix(A, C.fg, 0.35));
      cv.hl(4, 26, 32, 1, A);
      // --- 校验结果
      cv.disc(50, 19, 9, C.ok);
      cv.bigCheck(50, 19, 11, C.void);
    }
  },

  // ======================================================= 14 证件过期刷新机
  {
    id: 'renewal_machine', w: 32, h: 64, accent: '#9BE04F', code: 'RENEW',
    cn: '证件过期刷新机', file: 'renewal_machine_screen',
    ui: '表单条目（高密度）+ 纵向进度',
    note: '最窄最竖的一块屏：7 行复选框表单条目铺满，右缘一条纵向进度条。密度是它的识别特征。',
    draw(cv, A) {
      header(cv, A, 'RENEW', C.warn);
      for (let i = 0; i < 7; i++) {
        const y = 11 + i * 6;
        cv.box(3, y + 1, 4, 4, C.line);
        if (i < 3) cv.fill(4, y + 2, 2, 2, A);
        cv.hl(9, y + 2, 8, 2, C.dim);
        cv.hl(18, y + 2, 7, 2, i < 3 ? C.fg : mix(C.dim, C.bg, 0.5));
      }
      // --- 纵向进度
      cv.vbar(27, 11, 2, 41, 0.45, A, C.bg3);
      cv.text(3, 55, '45%', A);
      cv.put(29, 59, A);
    }
  },

  // ======================================================= 15 证件挂失注销
  {
    id: 'loss_terminal', w: 64, h: 32, accent: '#FF6B4F', code: 'LOSS',
    cn: '证件挂失注销终端', file: 'loss_terminal_screen',
    ui: '警示色 + 表单条目',
    note: '顶部 hazard 斜纹警示带 + 左侧红底感叹号三角，右侧表单条目，底部红色 REPORT 按钮（带留白的实心 CTA）。',
    draw(cv, A) {
      header(cv, A, 'LOSS', C.err);
      cv.hazard(2, 9, 60, 3, C.err, dark(C.err, 0.82));
      // --- 警示三角
      cv.warnTri(4, 14, 12, 13, C.err, C.void);
      // --- 表单条目
      [[C.fg, 24], [C.err, 20]].forEach(([col, w], i) => {
        const y = 13 + i * 6;
        cv.hl(22, y, 9, 2, C.dim);
        cv.hl(34, y, w, 2, col);
      });
      // --- 提交
      cv.panel(33, 24, 26, 7, C.err);
      cv.text(35, 25, 'REPORT', C.void);
    }
  },

  // ======================================================= 16 黑名单查询机
  {
    id: 'blacklist_terminal', w: 64, h: 64, accent: '#FF2E63', code: 'BLACKLIST',
    cn: '口岸黑名单查询机', file: 'blacklist_terminal_screen',
    ui: '警示色 + 表单条目',
    note: '整条红色标题横幅（深红字压亮红底，最高警示等级），四行命中记录（叉号 + 姓名条 + 命中条），底部红色汇总条。',
    draw(cv, A) {
      // 红色横幅取代常规标题
      cv.panel(2, 2, 60, 7, C.err);
      cv.text(5, 3, 'BLACKLIST', C.void);
      for (let i = 0; i < 4; i++) {
        const y = 13 + i * 10;
        cv.panel(3, y, 58, 9, C.bg2);
        cv.round(3, y, 58, 9, C.line);
        cv.cross(6, y + 2, C.err);
        cv.hl(14, y + 1, 22, 2, C.fg);
        cv.hl(14, y + 5, 14, 2, C.dim);
        cv.hl(46, y + 2, 12, 2, C.err);
      }
      cv.panel(3, 53, 58, 8, C.bg2);
      cv.round(3, 53, 58, 8, C.err);
      cv.text(5, 54, '17 MATCHES', C.err);
    }
  },

  // ======================================================= 17 证照打印预览机
  {
    id: 'print_preview', w: 64, h: 32, accent: '#8FC7E8', code: 'PRINT',
    cn: '证照打印预览机', file: 'print_preview_screen',
    ui: '打印页缩略',
    note: '左侧一张「纸」——全屏唯一的亮白面，压印墨线和一枚小章；右侧队列进度与纸张规格。',
    draw(cv, A) {
      header(cv, A, 'PRINT', C.ok);
      // --- 打印页缩略（全屏最亮的元素 = 纸）
      cv.fill(3, 9, 20, 21, C.paper);
      cv.round(3, 9, 20, 21, dark(A, 0.4));
      cv.hl(6, 13, 12, 1, C.ink);
      cv.hl(6, 16, 14, 1, C.ink);
      cv.hl(6, 19, 10, 1, C.ink);
      cv.ring(17, 25, 3, C.ink);
      // --- 队列
      cv.text(26, 10, 'QUEUE', C.dim);
      cv.bar(26, 15, 35, 4, 0.35, A, C.bg3);
      cv.text(26, 20, 'A4', C.dim);
      cv.text(35, 20, '01', C.fg);
      cv.text(26, 26, 'READY', C.ok);
    }
  },

  // ======================================================= 18 机场登机核验机
  {
    id: 'airport_checkin', w: 64, h: 64, accent: '#4FD8FF', code: 'BOARDING',
    cn: '机场登机核验机', file: 'airport_checkin_screen',
    ui: '登机牌（闸口/座位大号字）+ 竖条码',
    note: '整屏就是一张登机牌：撕线穿孔、GATE/SEAT 用 2 倍字模放大、右半是竖排条码。唯一用 scale-2 字模的界面。',
    draw(cv, A) {
      header(cv, A, 'BOARDING', C.ok);
      cv.panel(3, 10, 58, 42, C.bg2);
      cv.round(3, 10, 58, 42, A);
      // 撕线 + 缺口
      for (let y = 12; y < 50; y += 3) cv.put(40, y, C.line);
      cv.disc(40, 10, 2, C.bg);
      cv.disc(40, 51, 2, C.bg);
      // 左：闸口 / 座位
      cv.text(7, 14, 'GATE', C.dim);
      cv.textBig(7, 19, '12', A, 2);
      cv.text(7, 33, 'SEAT', C.dim);
      cv.textBig(7, 38, '22A', C.fg, 2);
      // 右：竖条码
      cv.text(43, 14, 'BRD', C.dim);
      cv.barcode(43, 20, 16, 22, C.fg, 0x4B1);
      cv.text(43, 45, 'OK', C.ok);
      // 底部（用 READY 而非 "ON TIME"：3x5 字模里孤立的 N 容易被读成 H）
      cv.text(3, 54, 'ECONOMY', C.dim);
      cv.text(34, 54, 'READY', C.ok);
    }
  },

  // ======================================================= 19 盖章记录查询终端
  {
    id: 'stamp_history', w: 64, h: 64, accent: '#E8C46B', code: 'STAMP LOG',
    cn: '盖章记录查询终端', file: 'stamp_history_screen',
    ui: '记录表格（日期 / 口岸 / 章 / 入出）',
    note: '唯一的真表格：表头底纹 + 横纵网格线 + 每行一枚迷你章。数据查询类终端的气质与发行类完全不同。',
    draw(cv, A) {
      header(cv, A, 'STAMP LOG', C.ok);
      cv.fill(3, 11, 58, 6, C.bg3);                  // 表头底纹
      cv.text(5, 12, 'DATE', C.dim);
      cv.text(27, 12, 'PT', C.dim);
      cv.text(50, 12, 'IO', C.dim);
      cv.round(3, 11, 58, 44, C.line);
      // 列分隔：日期 | 口岸 | 章 | 入出
      [25, 39, 48].forEach((x) => cv.vl(x, 11, 44, C.line));
      [17, 25, 33, 41, 49].forEach((y) => cv.hl(3, y, 58, 1, C.line));
      [18, 26, 34, 42, 50].forEach((y, i) => {
        cv.text(5, y, ['26-10', '26-09', '26-08', '26-07', '26-06'][i], C.fg);
        cv.text(27, y, ['PVG', 'HKG', 'NRT', 'SIN', 'ICN'][i], C.dim);
        cv.ring(43, y + 2, 3, A);
        cv.text(50, y + 1, i % 2 ? 'OUT' : 'IN', i % 2 ? C.err : C.ok);
      });
      cv.text(3, 57, '04 RECORDS', C.dim);
    }
  }
];

// ---------------------------------------------------------------------------
// Upscale helper so the art can be eyeballed at 1 glyph = N screen pixels
// ---------------------------------------------------------------------------
function zoom(cv, z) {
  const n = new K.Canvas(cv.w * z, cv.h * z, C.void);
  for (let y = 0; y < cv.h; y++) {
    for (let x = 0; x < cv.w; x++) {
      const i = (y * cv.w + x) * 4;
      if (!cv.d[i + 3]) continue;
      n.fill(x * z, y * z, z, z, [cv.d[i], cv.d[i + 1], cv.d[i + 2]]);
    }
  }
  return n;
}

// ---------------------------------------------------------------------------
// Quality gate — every screen must be fully opaque and use binary alpha.
// A transparent pixel here means a fill was skipped (a silent drawing bug) and
// would punch a hole straight through the device bezel in game.
// ---------------------------------------------------------------------------
const problems = [];
function audit(cv, label) {
  let clear = 0, offAlpha = 0;
  for (let i = 0; i < cv.d.length; i += 4) {
    const a = cv.d[i + 3];
    if (a === 0) clear++;
    else if (a !== 255) offAlpha++;
  }
  if (clear) problems.push(`${label}: ${clear} transparent px`);
  if (offAlpha) problems.push(`${label}: ${offAlpha} px with partial alpha`);
}

// ---------------------------------------------------------------------------
// Main
// ---------------------------------------------------------------------------
const outDir = DRY ? path.join(PREV, '_screens_new') : TEX;
const zoomDir = path.join(PREV, '_screens_new_zoom');
const stat = [];

for (const d of DEVICES) {
  const A = hex(d.accent);
  // --- 主屏
  const cv = baseScreen(d.w, d.h, A);
  d.draw(cv, A);
  audit(cv, d.file);
  cv.export(path.join(outDir, d.file + '.png'));
  // --- 次屏（校验通过 / 完成态）
  const cv2 = resultScreen(d.w, d.h, A, d.code);
  audit(cv2, d.file + '2');
  cv2.export(path.join(outDir, d.file + '2.png'));
  if (ZOOM) {
    zoom(cv, ZOOM).export(path.join(zoomDir, d.id + '_screen.png'));
    zoom(cv2, ZOOM).export(path.join(zoomDir, d.id + '_screen2.png'));
  }
  stat.push({ id: d.id, w: d.w, h: d.h, accent: d.accent, cn: d.cn });
}

fs.writeFileSync(
  path.join(PREV, '_screens_manifest.json'),
  JSON.stringify(DEVICES.map((d) => ({
    id: d.id, cn: d.cn, accent: d.accent, size: `${d.w}x${d.h}`,
    file: d.file + '.png', ui: d.ui, note: d.note, code: d.code
  })), null, 2)
);

console.log(`wrote ${DEVICES.length * 2} screens to ${path.relative(ROOT, outDir)}`);
for (const s of stat) console.log(`  ${s.id.padEnd(22)} ${String(s.w + 'x' + s.h).padEnd(6)} ${s.accent}  ${s.cn}`);
if (ZOOM) console.log(`zoom ${ZOOM}x -> ${path.relative(ROOT, zoomDir)}`);
if (problems.length) {
  console.error(`\nQUALITY GATE FAILED (${problems.length}):`);
  for (const p of problems) console.error('  - ' + p);
  process.exit(1);
}
console.log('alpha gate: OK (opaque, binary)');
