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

  // ── 第二阶段：关掉「位置辅助」后，请求体必须带 gps_enabled=false
  //    且不再携带坐标（体检「GPS 关了还在用」回归：后端 8h TTL 旧缓存
  //    曾持续注入流式对话，前端关开关形同虚设。严格断言打在请求体上——
  //    回复文本有 LLM 随机性，只作软断言）──────────────────────
  await page.locator('button[aria-label="设置"]').click();
  await page.waitForTimeout(500);
  await page.locator('button[aria-label="位置辅助开关"]').click();
  await page.waitForTimeout(300);
  await page.locator('button[aria-label="关闭设置"]').click();
  await page.waitForTimeout(500);

  chatReqBody = null;
  await input.fill('那现在陪我说说话吧～');
  await page.locator('button[aria-label="发送"]').click();
  await page.waitForTimeout(4000);

  if (!chatReqBody) {
    failed = true;
    console.log('[FAIL] 第二阶段 /api/chat/stream 请求未发出');
  } else {
    console.log('第二阶段请求体:', JSON.stringify({
      message: chatReqBody.message, gps_enabled: chatReqBody.gps_enabled,
      latitude: chatReqBody.latitude, longitude: chatReqBody.longitude,
    }));
    if (chatReqBody.gps_enabled === false &&
        chatReqBody.latitude === undefined && chatReqBody.longitude === undefined) {
      console.log('[PASS] 关闭 GPS 后请求体带 gps_enabled=false 且无坐标');
    } else {
      failed = true;
      console.log('[FAIL] 关闭 GPS 后请求体未带 gps_enabled=false 或仍携带坐标');
    }
  }

  // 软断言：回复不含尖沙咀坐标字面量（后端已清缓存则注入无从发生；
  // LLM 回复有随机性，失败仅告警不计入 failed）
  await page.waitForTimeout(6000);
  const reply2 = await page.evaluate(() => document.body.innerText);
  if (reply2.includes('114.1694') || reply2.includes('22.3193')) {
    console.log('[WARN] 第二阶段回复出现坐标字面量，疑似后端仍在注入旧缓存');
  } else {
    console.log('[PASS] 第二阶段回复无坐标字面量');
  }
  await page.screenshot({ path: path.join(SHOTS, 'gps-e2e-off.png') });

  if (consoleErrors.length) console.log('console errors:', consoleErrors.slice(0, 5));
  await ctx.close();
  if (failed) {
    console.log('[DONE] 存在断言失败，以非零退出');
    process.exit(1);
  }
  console.log('[DONE]');
})().catch(e => { console.error('[ERROR]', e); process.exit(1); });
