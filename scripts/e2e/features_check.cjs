/* eslint-disable no-console */
// 06 板块功能验证（2026-08-06 体检产品决策与优化）：
//   1. 天气事件上日历（pill 图标 + 详情只读「天气预报」，无编辑/删除/完成按钮）
//   2. 事件完成态（勾 ✓ → 划线 + 灰态 → 取消恢复）
//   3. 记忆面板导出按钮（download 触发 + JSON 可解析）
//   4. SW install 预缓存立绘（v7：caches 里直接有 /sprites/*.webp）
//   5. webp 重编码后立绘仍正常渲染（naturalWidth > 0）
//   6. 控制台零错误
// 前提：:8001 临时副本已启动，且库里种了未来两天的 weather 事件。
// 跑法：
//   NODE_PATH=$PWD/web/node_modules node scripts/e2e/features_check.cjs
const path = require('path');
const fs = require('fs');
const { chromium } = require('playwright');

const BASE = process.env.BASE || 'http://127.0.0.1:8001';
const SHOTS = path.join(__dirname, 'shots');
fs.mkdirSync(SHOTS, { recursive: true });

const results = [];
const ok = (n, x = '') => { results.push({ n, p: true }); console.log(`  ✓ ${n}${x ? '  — ' + x : ''}`); };
const bad = (n, e) => { results.push({ n, p: false }); console.log(`  ✗ ${n}  — ${e}`); };
const shot = async (page, n) => { await page.screenshot({ path: path.join(SHOTS, `${n}.png`) }); };

const consoleErrors = [];
const apiFails = [];

(async () => {
  const os = require('os');
  const profileDir = path.join(os.tmpdir(), 'xiaoxi-features-' + Date.now());
  const ctx = await chromium.launchPersistentContext(profileDir, {
    channel: 'msedge', headless: true,
    viewport: { width: 430, height: 900 }, deviceScaleFactor: 2, isMobile: true, hasTouch: true,
  });
  const page = ctx.pages()[0] || await ctx.newPage();
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()); });
  page.on('pageerror', e => consoleErrors.push('pageerror: ' + e.message));
  page.on('response', r => { if (r.url().includes('/api/') && r.status() >= 400) apiFails.push(`${r.status()} ${new URL(r.url()).pathname}`); });
  const downloads = [];
  page.on('download', d => downloads.push(d));

  // HKT 明天/后天（与种库日期对齐）
  const hktDay = (n) => {
    const t = new Intl.DateTimeFormat('sv', { timeZone: 'Asia/Hong_Kong' }).format(new Date());
    const d = new Date(t + 'T00:00:00Z');
    d.setUTCDate(d.getUTCDate() + n);
    return d.toISOString().slice(0, 10);
  };

  try {
    await page.goto(BASE + '/', { waitUntil: 'networkidle' });
    await page.waitForSelector('input[placeholder="和小汐说说话……"]', { timeout: 10000 });
    ok('页面加载');

    // ── 立绘（webp 重编码后仍正常） ──
    const sprite = await page.evaluate(() => {
      const img = document.querySelector('img[alt="小汐"]');
      return { ok: !!img && img.complete && img.naturalWidth > 0, src: img && (img.currentSrc || img.src) };
    });
    sprite.ok ? ok('立绘渲染（webp 重编码后 naturalWidth>0）', sprite.src) : bad('立绘', JSON.stringify(sprite));

    // ── 天气事件上日历 ──
    await page.locator('button[aria-label="小汐的日历"]').click();
    await page.waitForSelector('[role="dialog"][aria-label="小汐的日历"]', { timeout: 5000 });
    const tomorrow = hktDay(1);
    const weatherPill = await page.locator(`button[aria-label^="${tomorrow.slice(5, 7) * 1}月${tomorrow.slice(8, 10) * 1}日"]`).innerText().catch(() => '');
    if (weatherPill.includes('有雷暴') || weatherPill.includes('⚡')) {
      ok(`天气事件上日历（明天格子里可见「有雷暴」+ ⚡）`, weatherPill.replace(/\n/g, ' / '));
    } else bad('天气事件上日历', `明天格子内容: ${JSON.stringify(weatherPill)}`);
    await shot(page, 'f1-weather-on-calendar');

    // 详情：只读徽标「天气预报」，无编辑/删除/完成按钮
    await page.locator(`button[aria-label^="${tomorrow.slice(5, 7) * 1}月${tomorrow.slice(8, 10) * 1}日"]`).click();
    await page.waitForSelector('[role="dialog"][aria-label$="的安排"]', { timeout: 3000 });
    const wxDetail = await page.locator('[role="dialog"][aria-label$="的安排"]').innerText();
    const wxButtons = await page.locator('[role="dialog"][aria-label$="的安排"] button[title="编辑"], [role="dialog"][aria-label$="的安排"] button[title="删除"], [role="dialog"][aria-label$="的安排"] button[title="标记完成"]').count();
    (wxDetail.includes('天气预报') && wxButtons === 0)
      ? ok('天气事件详情只读（「天气预报」徽标，无编辑/删除/完成按钮）')
      : bad('天气事件详情只读', `hasBadge=${wxDetail.includes('天气预报')} actionBtns=${wxButtons}`);
    await shot(page, 'f2-weather-detail-readonly');
    await page.keyboard.press('Escape');

    // ── 完成态 ──
    await page.locator('button[title="下个月"]').click();
    await page.waitForTimeout(350);
    const nextMonthNum = (new Date().getMonth() + 1) % 12 + 1;  // 当前月份 + 1
    await page.locator(`button[aria-label^="${nextMonthNum}月15日"]`).click();
    await page.waitForSelector('[role="dialog"][aria-label$="的安排"]', { timeout: 3000 });
    await page.locator('button:has-text("＋ 记一件事")').click();
    await page.fill('input[placeholder="例如：期中考试"]', 'E2E考完试');
    await page.locator('button:has-text("记好啦 ✨")').click();
    await page.waitForTimeout(900);
    (await page.locator('text=E2E考完试').count()) >= 1
      ? ok('创建事件（完成态测试用）')
      : bad('创建事件', '未出现');

    await page.locator('button[aria-label="完成E2E考完试"]').click();
    await page.waitForTimeout(900);
    const doneState = await page.evaluate(() => {
      const titleP = [...document.querySelectorAll('p')].find(p => p.textContent.includes('E2E考完试'));
      return {
        struck: titleP ? titleP.className.includes('line-through') : false,
        unBtn: !!document.querySelector('button[aria-label="取消完成E2E考完试"]'),
        toast: document.body.innerText.includes('办完啦'),
      };
    });
    (doneState.struck && doneState.unBtn && doneState.toast)
      ? ok('勾完成 → 标题划线 + ✓ 变 ↺ + toast（小汐不惦记了）')
      : bad('勾完成', JSON.stringify(doneState));
    await shot(page, 'f3-event-completed');

    await page.locator('button[aria-label="取消完成E2E考完试"]').click();
    await page.waitForTimeout(900);
    const undone = await page.evaluate(() => {
      const titleP = [...document.querySelectorAll('p')].find(p => p.textContent.includes('E2E考完试'));
      return { struck: titleP ? titleP.className.includes('line-through') : false,
               doneBtn: !!document.querySelector('button[aria-label="完成E2E考完试"]') };
    });
    (!undone.struck && undone.doneBtn) ? ok('取消完成 → 恢复划线前状态（按钮回 ✓）') : bad('取消完成', JSON.stringify(undone));
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');

    // ── 记忆导出 ──
    await page.locator('button[aria-label="小汐的记忆"]').click();
    await page.waitForSelector('[role="dialog"][aria-label="小汐的记忆"]', { timeout: 5000 });
    await page.locator('button:has-text("↧ 导出")').click();
    await page.waitForTimeout(1500);
    if (downloads.length > 0) {
      const dl = downloads[0];
      const p = await dl.path();
      const json = JSON.parse(fs.readFileSync(p, 'utf8'));
      (json && typeof json === 'object' && 'memories' in json)
        ? ok(`记忆导出 → download 触发，JSON 可解析（memories 键存在，${dl.suggestedFilename()}）`)
        : bad('记忆导出', 'JSON 缺少 memories 键');
    } else bad('记忆导出', 'download 事件未触发');
    await shot(page, 'f4-export-memories');
    await page.locator('button[aria-label="关闭记忆"]').click();

    // ── SW 预缓存 ──
    const precached = await page.evaluate(async () => {
      try {
        await navigator.serviceWorker.ready;
        const cache = await caches.open('xiaoxi-room-v7');
        const keys = (await cache.keys()).map(k => new URL(k.url).pathname);
        return { listening: keys.includes('/sprites/listening.webp'), count: keys.length };
      } catch (e) { return { error: String(e) }; }
    });
    (precached.listening && precached.count >= 10)
      ? ok(`SW install 预缓存立绘（v7 缓存 ${precached.count} 项，含 /sprites/listening.webp）`)
      : bad('SW 预缓存', JSON.stringify(precached));

    // ── 健康度 ──
    consoleErrors.length === 0 ? ok('无控制台 / 页面错误') : bad('控制台错误', consoleErrors.slice(0, 4).join(' | '));
    apiFails.length === 0 ? ok('API 无 4xx/5xx（含完成/导出调用）') : bad('API 错误', apiFails.slice(0, 4).join(' | '));

  } catch (e) {
    bad('执行中断', e.message);
  } finally {
    await ctx.close();
  }

  const failed = results.filter(r => !r.p);
  console.log(`\n==== 结果：${results.length - failed.length}/${results.length} 通过 ====`);
  process.exit(failed.length ? 1 : 0);
})();
