/* eslint-disable no-console */
// 「奶甜草莓粉」多维度体检：dev server（:3000，支持 ?_sky）+ 系统 Edge headless。
// 纯读 + 本地戳一戳，不向 API 写任何东西（零污染）。
// 维度：视觉(四时段/房间/四面板/日历详情表单/桌面) · 时序(开面板连拍验扫掠) ·
//       动效安全(reduced-motion) · 离线(字体回落) · 控制台清洁。
// 用法：NODE_PATH=$(npm root -g)/@ejazullah/mcp-playwright/node_modules node scripts/e2e/pink_verify.cjs
const path = require('path');
const fs = require('fs');
const os = require('os');
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://127.0.0.1:3000';
const SHOTS = path.join(__dirname, 'shots', 'pink');
fs.mkdirSync(SHOTS, { recursive: true });

const consoleErrors = [];
// 预期噪音：dev 无后端时 /api 的 502；字体回落测试里被 abort 的 fonts.g* 请求
const isNoise = (t) => t.includes('502') || /fonts\.(googleapis|gstatic)/.test(t);
const shot = async (page, n) => { await page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); console.log(`  📷 ${n}`); };
// 开面板后的定时连拍：扫掠若存在会落在前 ~300ms
const burst = async (page, label, deltas = [30, 70, 90, 130]) => {
  for (let i = 0; i < deltas.length; i++) { await page.waitForTimeout(deltas[i]); await shot(page, `${label}-${i}`); }
};

(async () => {
  const profileDir = path.join(os.tmpdir(), 'xiaoxi-pink-' + Date.now());
  const ctx = await chromium.launchPersistentContext(profileDir, {
    channel: 'msedge', headless: true,
    viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
  });
  const page = ctx.pages()[0] || await ctx.newPage();
  await page.emulateMedia({ reducedMotion: 'no-preference' }); // 无头常默认 reduce，显式关
  page.on('console', m => { if (m.type() === 'error' && !isNoise(m.text())) consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));

  try {
    // ── 四时段天空 ───────────────────────────────────────
    for (const [hour, name] of [[6, 'sky-morning'], [12, 'sky-day'], [18, 'sky-evening'], [23, 'sky-night']]) {
      await page.goto(`${BASE}/?_sky=${hour}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(600);
      await shot(page, name);
    }

    // ── 主房间 + 漂浮圆点计数 ────────────────────────────
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForSelector('input[placeholder="和小汐说说话……"]', { timeout: 10000 });
    await page.waitForTimeout(800);
    const motes = await page.evaluate(() => document.querySelectorAll('.ambient-motes span').length);
    console.log(`  · 漂浮圆点节点 ${motes}（期望 6）`);
    await page.evaluate(() => document.fonts.ready);
    const kuaiLeOnline = await page.evaluate(() => [...document.fonts].some(f => /ZCOOL KuaiLe/i.test(f.family) && f.status === 'loaded'));
    console.log(`  · 在线 ZCOOL KuaiLe loaded？${kuaiLeOnline}（期望 true）`);
    await shot(page, 'room');

    // ── 戳一戳：气泡 + 爱心迸发 ──────────────────────────
    await page.locator('img[data-testid="sprite-img"]').dispatchEvent('click');
    await page.waitForTimeout(170);
    const burstN = await page.evaluate(() => document.querySelectorAll('.heart-burst').length);
    console.log(`  · 迸发爱心节点 ${burstN}（期望 6）`);
    const bubbleOk = await page.locator('[data-testid="poke-bubble"]').count();
    console.log(bubbleOk ? '  ✓ 戳一戳气泡出现' : '  ✗ 戳一戳气泡未出现');
    await shot(page, 'poke-burst');

    // ── 聊天面板展开 ─────────────────────────────────────
    await page.evaluate(() => { const h = document.querySelector('[data-testid="chat-handle"]'); if (h) h.parentElement.style.height = '60dvh'; });
    await page.waitForTimeout(300);
    await shot(page, 'chat-open');

    // ── 四个面板（含日历详情/表单） ──────────────────────
    for (const [label, name] of [['以往的对话', 'panel-sessions'], ['小汐的记忆', 'panel-memories'], ['小汐的日历', 'panel-calendar'], ['设置', 'panel-config']]) {
      await page.click(`button[aria-label="${label}"]`);
      await page.waitForTimeout(500);
      await shot(page, name);
      if (label === '小汐的日历') {
        const grid = page.locator('.grid.grid-rows-6 button');
        if (await grid.first().isVisible({ timeout: 3000 }).catch(() => false)) {
          await grid.nth(15).click(); await page.waitForTimeout(400); await shot(page, 'calendar-detail');
          await page.click('text=＋ 记一件事'); await page.waitForTimeout(400); await shot(page, 'calendar-form');
          await page.keyboard.press('Escape'); await page.waitForTimeout(200);
          await page.keyboard.press('Escape'); await page.waitForTimeout(200);
        } else console.log('  · 日历网格未渲染（API 不可达），跳过详情/表单');
      }
      await page.keyboard.press('Escape'); await page.waitForTimeout(300);
    }

    // ── 时序：开面板连拍，确认无「主页面自上而下扫掠」 ────
    await page.click('button[aria-label="设置"]');
    await burst(page, 'open-config');
    await page.keyboard.press('Escape'); await page.waitForTimeout(300);
    await page.click('button[aria-label="小汐的日历"]');
    await burst(page, 'open-cal');
    await page.keyboard.press('Escape'); await page.waitForTimeout(300);

    // ── 减弱动态效果：圆点层与迸发应整体撤下 ─────────────
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(500);
    const motesHidden = await page.evaluate(() => { const el = document.querySelector('.ambient-motes'); return !el || getComputedStyle(el).display === 'none'; });
    console.log(motesHidden ? '  ✓ reduced-motion：圆点层已隐藏' : '  ✗ reduced-motion：圆点层仍在');
    await shot(page, 'reduced-motion');
    await page.emulateMedia({ reducedMotion: 'no-preference' });

    // ── 离线：独立干净上下文，首帧即断网，验字体真回落（避免主上下文缓存误判）──
    const offDir = path.join(os.tmpdir(), 'xiaoxi-offline-' + Date.now());
    const ctx2 = await chromium.launchPersistentContext(offDir, {
      channel: 'msedge', headless: true, viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
    });
    const p2 = ctx2.pages()[0] || await ctx2.newPage();
    await p2.route('**/fonts.googleapis.com/**', r => r.abort());
    await p2.route('**/fonts.gstatic.com/**', r => r.abort());
    await p2.goto(BASE + '/', { waitUntil: 'domcontentloaded' });
    await p2.waitForSelector('button[aria-label="设置"]', { timeout: 8000 }).catch(() => {});
    await p2.click('button[aria-label="设置"]').catch(() => {});
    await p2.waitForTimeout(700);
    // check() 对集合里不存在的字体名会按本地字体误报 true——故直接查 FontFaceSet 里有无 loaded 的 ZCOOL 面
    const kuaiLeLoaded = await p2.evaluate(() => [...document.fonts].some(f => /ZCOOL KuaiLe/i.test(f.family) && f.status === 'loaded'));
    console.log(`  · 离线首载 ZCOOL KuaiLe loaded？${kuaiLeLoaded}（期望 false → 标题回落系统圆体）`);
    await p2.screenshot({ path: path.join(SHOTS, 'font-fallback.png') }); console.log('  📷 font-fallback');
    await ctx2.close(); fs.rmSync(offDir, { recursive: true, force: true });

    // ── 桌面宽屏：梅子夜 letterbox + 居中房间列 ──────────
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(600);
    await shot(page, 'desktop-letterbox');

    // ── a11y 抽查：交互元素须有可访问名 ──────────────────
    await page.setViewportSize({ width: 430, height: 900 });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(400);
    const a11y = await page.evaluate(() => {
      const els = [...document.querySelectorAll('button, [role="button"]')];
      const unlabeled = els.filter(e => !(e.getAttribute('aria-label') || e.textContent.trim())).length;
      const sprite = document.querySelector('img[data-testid="sprite-img"]');
      return { buttons: els.length, unlabeled, spriteRole: sprite?.getAttribute('role'), spriteLabel: !!sprite?.getAttribute('aria-label') };
    });
    console.log(`  · a11y：按钮/role=button ${a11y.buttons} 个，缺可访问名 ${a11y.unlabeled}；立绘 role=${a11y.spriteRole} label=${a11y.spriteLabel}`);
  } catch (e) {
    console.error('FATAL:', e.message);
    await shot(page, 'fatal').catch(() => {});
  } finally {
    if (consoleErrors.length) { console.log('\n控制台错误：'); [...new Set(consoleErrors)].forEach(t => console.log('  ⚠ ' + t.slice(0, 200))); }
    else console.log('\n✓ 控制台无错误');
    await ctx.close();
    fs.rmSync(profileDir, { recursive: true, force: true });
  }
})();
