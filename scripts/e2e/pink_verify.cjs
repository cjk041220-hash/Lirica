/* eslint-disable no-console */
// 「奶甜草莓粉」换肤视觉验证：dev server（:3000，支持 ?_sky 时段预览）+ 系统 Edge headless。
// 纯读操作 + 本地戳一戳，不向 API 写任何东西（零污染）。
// 用法：NODE_PATH=$(npm root -g)/@ejazullah/mcp-playwright/node_modules node scripts/e2e/pink_verify.cjs
const path = require('path');
const fs = require('fs');
const os = require('os');
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://127.0.0.1:3000';
const SHOTS = path.join(__dirname, 'shots', 'pink');
fs.mkdirSync(SHOTS, { recursive: true });

const consoleErrors = [];
const shot = async (page, n) => {
  await page.screenshot({ path: path.join(SHOTS, `${n}.png`) });
  console.log(`  📷 ${n}`);
};

(async () => {
  const profileDir = path.join(os.tmpdir(), 'xiaoxi-pink-' + Date.now());
  const ctx = await chromium.launchPersistentContext(profileDir, {
    channel: 'msedge', headless: true,
    viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
  });
  const page = ctx.pages()[0] || await ctx.newPage();
  // 无头浏览器常默认模拟 reduced-motion——显式关，否则爱心层/迸发被撤下，截不到
  await page.emulateMedia({ reducedMotion: 'no-preference' });
  // :3000 的 /api 代理指向未必在跑的 :8000，502 属预期噪音，不算错误
  page.on('console', m => { if (m.type() === 'error' && !m.text().includes('502')) consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));

  try {
    // ── 四时段天空（dev-only ?_sky 预览参数）──────────────
    for (const [hour, name] of [[6, 'sky-morning'], [12, 'sky-day'], [18, 'sky-evening'], [23, 'sky-night']]) {
      await page.goto(`${BASE}/?_sky=${hour}`, { waitUntil: 'networkidle' });
      await page.waitForTimeout(600);
      await shot(page, name);
    }

    // ── 主房间（当前真实时段 + 漂浮爱心）──────────────────
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForSelector('input[placeholder="和小汐说说话……"]', { timeout: 10000 });
    await page.waitForTimeout(800);
    await shot(page, 'room');

    // ── 戳一戳：气泡 + 爱心迸发 ──────────────────────────
    // 立绘永远在呼吸（transform 常动），click 的稳定性断言等不到——直接派发 click 事件
    const floatHearts = await page.evaluate(() => document.querySelectorAll('.floating-hearts span').length);
    console.log(`  · 漂浮爱心节点 ${floatHearts}`);
    await page.locator('img[data-testid="sprite-img"]').dispatchEvent('click');
    await page.waitForTimeout(180);   // 卡在 heartBurst 的 18% 满透明度峰值附近
    const burstHearts = await page.evaluate(() => document.querySelectorAll('.heart-burst').length);
    console.log(`  · 迸发爱心节点 ${burstHearts}`);
    await shot(page, 'poke-burst');
    const bubbleOk = await page.locator('[data-testid="poke-bubble"]').count();
    console.log(bubbleOk ? '  ✓ 戳一戳气泡出现' : '  ✗ 戳一戳气泡未出现');

    // ── 聊天面板展开 ─────────────────────────────────────
    await page.evaluate(() => {
      const h = document.querySelector('[data-testid="chat-handle"]');
      if (h) h.parentElement.style.height = '60dvh';
    });
    await page.waitForTimeout(400);
    await shot(page, 'chat-open');

    // ── 四个面板 ─────────────────────────────────────────
    for (const [label, name] of [['以往的对话', 'panel-sessions'], ['小汐的记忆', 'panel-memories'], ['小汐的日历', 'panel-calendar'], ['设置', 'panel-config']]) {
      await page.click(`button[aria-label="${label}"]`);
      await page.waitForTimeout(550);
      await shot(page, name);
      if (label === '小汐的日历') {
        // 日历详情 + 记一件事表单（网格仅在 API 可达时渲染，dev 无后端则跳过）
        const grid = page.locator('.grid.grid-rows-6 button');
        if (await grid.first().isVisible({ timeout: 3000 }).catch(() => false)) {
          await grid.nth(15).click();
          await page.waitForTimeout(450);
          await shot(page, 'calendar-detail');
          await page.click('text=＋ 记一件事');
          await page.waitForTimeout(450);
          await shot(page, 'calendar-form');
          await page.keyboard.press('Escape');
          await page.waitForTimeout(200);
          await page.keyboard.press('Escape');
          await page.waitForTimeout(200);
        } else {
          console.log('  · 日历网格未渲染（API 不可达），跳过详情/表单截图');
        }
      }
      await page.keyboard.press('Escape');
      await page.waitForTimeout(300);
    }

    // ── 减弱动态效果：爱心层应整体撤下 ───────────────────
    await page.emulateMedia({ reducedMotion: 'reduce' });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(500);
    const heartsHidden = await page.evaluate(() => {
      const el = document.querySelector('.floating-hearts');
      return !el || getComputedStyle(el).display === 'none';
    });
    console.log(heartsHidden ? '  ✓ reduced-motion：爱心层已隐藏' : '  ✗ reduced-motion：爱心层仍在');
    await shot(page, 'reduced-motion');
    await page.emulateMedia({ reducedMotion: null });

    // ── 桌面宽屏：梅子夜 letterbox + 居中房间列 ──────────
    await page.setViewportSize({ width: 1440, height: 900 });
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForTimeout(600);
    await shot(page, 'desktop-letterbox');
  } catch (e) {
    console.error('FATAL:', e);
    await shot(page, 'fatal').catch(() => {});
  } finally {
    if (consoleErrors.length) {
      console.log('\n控制台错误：');
      [...new Set(consoleErrors)].forEach(t => console.log('  ⚠ ' + t.slice(0, 200)));
    } else {
      console.log('\n✓ 控制台无错误');
    }
    await ctx.close();
    fs.rmSync(profileDir, { recursive: true, force: true });
  }
})();
