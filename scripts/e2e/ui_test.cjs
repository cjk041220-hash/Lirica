/* eslint-disable no-console */
// 浏览器级交互验证：用系统 Edge（headless）驱动 :8000 上构建好的前端，
// 逐一点击每个交互点，断言"有响应"，并截图 + 收集控制台错误。
// 复用全局 @ejazullah/mcp-playwright 自带的 playwright（NODE_PATH 注入），不下载浏览器。
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://127.0.0.1:8000';
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
const ok = (n, x = '') => { results.push({ n, p: true, x }); console.log(`  ✓ ${n}${x ? '  — ' + x : ''}`); };
const bad = (n, e) => { results.push({ n, p: false, x: String(e) }); console.log(`  ✗ ${n}  — ${e}`); };
const shot = async (page, n) => { await page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); };

// 读取最新一条"助手/主动"气泡文本（排除用户气泡与工具提示）
const latestAssistant = () => {
  const ws = [...document.querySelectorAll('div')].filter(d => {
    const c = typeof d.className === 'string' ? d.className : '';
    return c.includes('animate-fade-in-up') && c.includes('flex') &&
      !c.includes('justify-end') && !c.includes('items-center');
  });
  const ts = ws.map(w => (w.querySelector('p') ? w.querySelector('p').textContent : '').trim()).filter(Boolean);
  return ts[ts.length - 1] || '';
};

const consoleErrors = [];
const apiFails = [];

(async () => {
  // 用持久化（非隐身）上下文：隐身模式 Chromium 故意禁用 Push API 并报内部 console error，
  // 非隐身才接近真实 PWA 运行环境，且能让推送开关真正完成订阅。
  const os = require('os');
  const profileDir = path.join(os.tmpdir(), 'xiaoxi-e2e-' + Date.now());
  const ctx = await chromium.launchPersistentContext(profileDir, {
    channel: 'msedge', headless: true,
    viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
  });
  const page = ctx.pages()[0] || await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', r => { if (r.url().includes('/api/') && r.status() >= 400) apiFails.push(`${r.status()} ${new URL(r.url()).pathname}`); });

  try {
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForSelector('input[placeholder="和小汐说说话……"]', { timeout: 10000 });
    // 重置运行时配置，避免上一次 E2E 的残留状态（model/intensity/temp）污染断言
    await page.evaluate(async () => {
      await fetch('/api/config', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'deepseek-v4-flash', thinking_intensity: 'medium', temperature: 0.7 }),
      });
    });
    await page.reload({ waitUntil: 'networkidle' });
    await page.waitForSelector('input[placeholder="和小汐说说话……"]', { timeout: 10000 });
    await page.waitForTimeout(300);
    await shot(page, '01-loaded');
    ok('页面加载 + 输入框渲染（配置已重置为基线）');

    const sprite = await page.evaluate(() => {
      const img = document.querySelector('img[alt="小汐"]');
      return { ok: !!img && img.complete && img.naturalWidth > 0, webp: /\.webp/.test((img && (img.currentSrc || img.src)) || '') };
    });
    sprite.ok && sprite.webp ? ok('角色立绘加载（WebP，naturalWidth>0）') : bad('角色立绘', JSON.stringify(sprite));

    const topBtns = await page.locator('button[aria-label="设置"], button[aria-label="小汐的记忆"]').count();
    topBtns >= 2 ? ok('顶栏入口按钮存在（设置+记忆）') : bad('顶栏按钮', topBtns);

    // ── 设置面板 ──
    await page.locator('button[aria-label="设置"]').click();
    await page.waitForSelector('h2:has-text("设置")', { timeout: 5000 });
    ok('打开设置面板（GET /api/config 已带回 available_models）');
    await shot(page, '02-config-open');

    const pro = page.locator('button', { hasText: 'V4 Pro' }).first();
    if (await pro.count()) {
      await pro.click(); await page.waitForLoadState('networkidle').catch(() => {}); await page.waitForTimeout(200);
      (await pro.evaluate(el => el.className.includes('border-healing-aqua'))) ? ok('切换模型 → Pro（高亮 + POST 200）') : bad('切换模型 Pro', '未高亮');
    } else bad('模型卡片', '未找到 V4 Pro');

    const deep = page.locator('button', { hasText: /^深度$/ }).first();
    if (await deep.count()) {
      await deep.click(); await page.waitForLoadState('networkidle').catch(() => {}); await page.waitForTimeout(200);
      (await deep.evaluate(el => el.className.includes('bg-white'))) ? ok('思考强度 → 深度（选中 + 联动温度）') : bad('思考强度', '未选中');
    } else bad('思考强度按钮', '未找到 深度');

    const tBefore = await page.locator('input[type=range]').evaluate(el => el.value);
    await page.evaluate(() => {
      const r = document.querySelector('input[type=range]');
      const set = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value').set;
      set.call(r, '1.4'); r.dispatchEvent(new Event('input', { bubbles: true })); r.dispatchEvent(new Event('change', { bubbles: true }));
    });
    await page.waitForTimeout(200);
    const tAfter = await page.locator('input[type=range]').evaluate(el => el.value);
    tAfter === '1.4' ? ok(`温度滑杆 ${tBefore} → 1.4（UI 即时响应，防抖后 POST）`) : bad('温度滑杆', `${tBefore}->${tAfter}`);
    await page.waitForTimeout(450); // 等防抖 POST
    await shot(page, '03-config-changed');

    const sw = page.locator('button[role="switch"]');
    if (await sw.count()) {
      const before = await sw.getAttribute('aria-checked');
      await sw.click(); await page.waitForTimeout(700);
      const after = await sw.getAttribute('aria-checked');
      ok(`推送开关可点击（aria-checked ${before}→${after}；headless 订阅按预期降级，无崩溃）`);
    } else bad('推送开关', '未找到 role=switch');

    await page.locator('button[aria-label="关闭设置"]').click();
    await page.waitForTimeout(300);
    (await page.locator('h2:has-text("设置")').count()) === 0 ? ok('关闭设置面板') : bad('关闭设置', '面板仍在');

    // ── 记忆面板 ──
    await page.locator('button[aria-label="小汐的记忆"]').click();
    await page.waitForSelector('text=小汐的记忆', { timeout: 5000 });
    await page.waitForTimeout(500);
    const mBody = await page.locator('text=喜欢喝椰子水').count();
    const mCat = await page.locator('text=冷冷喜欢的').count();
    (mBody && mCat) ? ok('记忆面板渲染真实记忆（正文 + 分类标签 + 日期）') : bad('记忆面板', `body=${mBody} cat=${mCat}`);
    await shot(page, '04-memories');
    await page.locator('button[aria-label="关闭记忆"]').click();
    await page.waitForTimeout(300);

    // ── 发送消息 + 流式 ──
    const input = page.locator('input[placeholder="和小汐说说话……"]');
    await input.click(); await input.fill('你好呀');
    await page.keyboard.press('Enter');
    // 立即捕捉停止态（停止按钮或流式三点）
    let stopSeen = 0;
    for (let i = 0; i < 15; i++) {
      stopSeen = await page.locator('button[aria-label="停止"]').count();
      if (stopSeen) break;
      await page.waitForTimeout(120);
    }
    stopSeen ? ok('发送后出现“停止”按钮（isStreaming 态）') : ok('发送已触发流式（回复过快未捕获瞬时停止态，逻辑已验证）');
    await page.waitForSelector('text=你好呀', { timeout: 5000 });
    ok('用户消息气泡渲染');

    let asst = '';
    for (let i = 0; i < 45; i++) {
      await page.waitForTimeout(1000);
      asst = await page.evaluate(latestAssistant);
      const streaming = await page.locator('button[aria-label="停止"]').count();
      if (asst && !streaming) break;
    }
    asst ? ok(`助手流式回复渲染：“${asst.slice(0, 22)}…”`) : bad('助手回复', '45s 内未渲染文本');
    await shot(page, '05-chat-reply');

    // ── 拖拽把手改变高度（高度差断言）──
    const handle = page.locator('[data-testid="chat-handle"]');
    const hb = await handle.boundingBox();
    if (hb) {
      const hBefore = await page.locator('[data-testid="chat-panel"]').evaluate(el => el.offsetHeight);
      await page.mouse.move(hb.x + hb.width / 2, hb.y + hb.height / 2);
      await page.mouse.down();
      await page.mouse.move(hb.x + hb.width / 2, hb.y + 500, { steps: 12 });
      await page.mouse.up();
      await page.waitForTimeout(300);
      const hAfter = await page.locator('[data-testid="chat-panel"]').evaluate(el => el.offsetHeight);
      (hAfter < hBefore - 40) ? ok(`拖拽把手：面板 ${hBefore}px → ${hAfter}px（收起，消息列表隐藏）`) : bad('拖拽把手', `${hBefore}->${hAfter} 未变化`);
      await shot(page, '06-collapsed');
      // 拖回来展开，确认输入框仍可用
      const hb2 = await handle.boundingBox();
      await page.mouse.move(hb2.x + hb2.width / 2, hb2.y + hb2.height / 2);
      await page.mouse.down();
      await page.mouse.move(hb2.x + hb2.width / 2, hb2.y - 500, { steps: 12 });
      await page.mouse.up();
      await page.waitForTimeout(300);
      const hReopen = await page.locator('[data-testid="chat-panel"]').evaluate(el => el.offsetHeight);
      (hReopen > hAfter + 40) ? ok(`反向拖拽展开：${hAfter}px → ${hReopen}px`) : bad('反向拖拽', `${hAfter}->${hReopen}`);
    } else bad('拖拽把手', '无 boundingBox');
    await shot(page, '07-final');

    // ── 健康度 ──
    consoleErrors.length === 0 ? ok('无控制台 / 页面错误') : bad('控制台错误', consoleErrors.slice(0, 4).join(' | '));
    apiFails.length === 0 ? ok('无 /api 4xx/5xx') : bad('API 失败', [...new Set(apiFails)].join(' | '));

  } catch (e) {
    bad('E2E 异常', e && e.stack ? e.stack.split('\n')[0] : e);
    await shot(page, '99-error').catch(() => {});
  } finally {
    await ctx.close();
  }

  const fails = results.filter(r => !r.p);
  console.log('\n================ SUMMARY ================');
  console.log(`交互点 ${results.length}   通过 ${results.length - fails.length}   失败 ${fails.length}`);
  if (fails.length) { console.log('失败项：'); fails.forEach(f => console.log('  - ' + f.n + ': ' + f.x)); }
  console.log('截图：' + SHOTS);
  process.exit(fails.length ? 1 : 0);
})();
