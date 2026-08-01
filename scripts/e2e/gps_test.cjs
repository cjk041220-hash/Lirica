/* eslint-disable no-console */
// GPS 端到端实测（P1-2 GPS 半边的回归验证）：
// 用 Playwright 注入固定坐标（香港·尖沙咀），发一条与位置相关的话，
// 拦截 /api/chat/stream 请求体断言坐标已上传，并打印小汐的最终回复
// （高德 key 已配置时，她会真的用这个坐标查"附近"）。
//
// 运行（playwright 是 web/ 的 devDependency，体检 F-13 后不再依赖全局
// mcp-playwright；脚本在 scripts/e2e/ 解析不到 web/node_modules，仍需
// NODE_PATH 指过去；浏览器用系统 Edge）：
//   先启动后端 :8000，然后
//   NODE_PATH=$PWD/web/node_modules node scripts/e2e/gps_test.cjs
//
// 退出码：任一断言 [FAIL] → 非零（修复旧版断言失败仍 exit 0 的缺陷，否则 CI 看不见失败）
const path = require('path');
const os = require('os');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://127.0.0.1:8000';
const HK_LAT = 22.3193, HK_LON = 114.1694; // 香港·尖沙咀
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

(async () => {
  const profileDir = path.join(os.tmpdir(), 'xiaoxi-gps-' + Date.now());
  const ctx = await chromium.launchPersistentContext(profileDir, {
    channel: 'msedge', headless: true,
    viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    geolocation: { latitude: HK_LAT, longitude: HK_LON },
    permissions: ['geolocation'],
  });
  const page = await ctx.newPage();

  let chatReqBody = null;
  page.on('request', (req) => {
    if (req.url().includes('/api/chat/stream') && req.method() === 'POST') {
      try { chatReqBody = req.postDataJSON(); } catch { /* ignore */ }
    }
  });
  const consoleErrors = [];
  let failed = false; // 断言失败计数；非零即非零退出
  page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });

  await page.goto(BASE, { waitUntil: 'networkidle', timeout: 30000 });

  const input = page.locator('input[placeholder="和小汐说说话……"]');
  await input.fill('附近有没有适合散步的公园呀？');
  await page.locator('button[aria-label="发送"]').click();

  // 等请求发出（含首次定位获取）
  await page.waitForTimeout(4000);

  // ── 断言 1：请求体携带坐标 ──────────────────────────────
  if (!chatReqBody) {
    failed = true;
    console.log('[FAIL] /api/chat/stream 请求未发出');
  } else {
    const { latitude, longitude, session_id, message } = chatReqBody;
    console.log('请求体:', JSON.stringify({ message, session_id, latitude, longitude }));
    if (typeof latitude === 'number' && Math.abs(latitude - HK_LAT) < 0.01 &&
        typeof longitude === 'number' && Math.abs(longitude - HK_LON) < 0.01) {
      console.log('[PASS] GPS 坐标已随对话上传后端（香港·尖沙咀）');
    } else {
      failed = true;
      console.log('[FAIL] 请求体缺少坐标或坐标不符');
    }
  }

  // ── 断言 2：小汐给出回复（流式光标消失），最多等 90s ─────
  let reply = '';
  const deadline = Date.now() + 90000;
  while (Date.now() < deadline) {
    reply = await page.evaluate(() => {
      const ws = [...document.querySelectorAll('div')].filter(d => {
        const c = typeof d.className === 'string' ? d.className : '';
        return c.includes('animate-fade-in-up') && c.includes('flex') &&
          !c.includes('justify-end') && !c.includes('items-center');
      });
      const texts = ws.map(w => w.innerText.trim()).filter(t =>
        t && !t.startsWith('小汐') || (t && t.length > 20));
      return texts[texts.length - 1] || '';
    });
    const streaming = await page.locator('.animate-shimmer').count();
    if (reply && streaming === 0) break;
    await page.waitForTimeout(2000);
  }
  console.log('--- 小汐的回复 ---');
  console.log(reply.slice(0, 500) || '(未等到回复)');

  await page.screenshot({ path: path.join(SHOTS, 'gps-e2e.png') });
  if (consoleErrors.length) console.log('console errors:', consoleErrors.slice(0, 5));
  await ctx.close();
  if (failed) {
    console.log('[DONE] 存在断言失败，以非零退出');
    process.exit(1);
  }
  console.log('[DONE]');
})().catch(e => { console.error('[ERROR]', e); process.exit(1); });
