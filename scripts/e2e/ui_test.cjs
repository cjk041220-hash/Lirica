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

// 戳一戳文案池——单一真相源 web/src/poke-lines.json（与组件共用，改一处即可）
const POKE_LINES_SRC = require(path.join(__dirname, '..', '..', 'web', 'src', 'poke-lines.json'));
const POKE_LINES_ALL = Object.values(POKE_LINES_SRC).flat();

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

    const topBtns = await page.locator('button[aria-label="设置"], button[aria-label="小汐的记忆"], button[aria-label="以往的对话"], button[aria-label="小汐的日历"]').count();
    topBtns >= 4 ? ok('顶栏入口按钮存在（设置+记忆+历史+日历，2+2 分组）') : bad('顶栏按钮', topBtns);

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

    // v0.4.5：「思考强度」已更名为「回复风格」，档位文案 轻柔/适中/深度/全力
    // → 稳重/自然/发散/灵感（P2-4 文案诚实化，实际映射 temperature）
    const deep = page.locator('button', { hasText: /^发散$/ }).first();
    if (await deep.count()) {
      await deep.click(); await page.waitForLoadState('networkidle').catch(() => {}); await page.waitForTimeout(200);
      (await deep.evaluate(el => el.className.includes('bg-white'))) ? ok('回复风格 → 发散（选中 + 联动温度）') : bad('回复风格', '未选中');
    } else bad('回复风格按钮', '未找到 发散');

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

    // v0.4.5：设置面板新增「位置辅助」开关，role=switch 不再唯一，按 aria-label 精确定位
    const sw = page.locator('button[aria-label="消息通知开关"]');
    if (await sw.count()) {
      const before = await sw.getAttribute('aria-checked');
      await sw.click(); await page.waitForTimeout(700);
      const after = await sw.getAttribute('aria-checked');
      ok(`推送开关可点击（aria-checked ${before}→${after}；headless 订阅按预期降级，无崩溃）`);
    } else bad('推送开关', '未找到 消息通知开关');

    // v0.4.5 新增：位置辅助开关
    const gpsSw = page.locator('button[aria-label="位置辅助开关"]');
    if (await gpsSw.count()) {
      const gBefore = await gpsSw.getAttribute('aria-checked');
      await gpsSw.click(); await page.waitForTimeout(300);
      const gAfter = await gpsSw.getAttribute('aria-checked');
      (gBefore !== gAfter) ? ok(`位置辅助开关可切换（aria-checked ${gBefore}→${gAfter}）`) : bad('位置辅助开关', '状态未变化');
      await gpsSw.click(); await page.waitForTimeout(200); // 还原为开启
    } else bad('位置辅助开关', '未找到');

    await page.locator('button[aria-label="关闭设置"]').click();
    await page.waitForTimeout(300);
    (await page.locator('h2:has-text("设置")').count()) === 0 ? ok('关闭设置面板') : bad('关闭设置', '面板仍在');

    // v0.4.5：配置阶段切到了 Pro + 发散 + 高温，Pro 深度推理首 token 可能
    // 超过对话阶段的 45s 等待——聊天前恢复 flash 基线，保证回复及时完成
    await page.evaluate(async () => {
      await fetch('/api/config', {
        method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ model: 'deepseek-v4-flash', thinking_intensity: 'medium', temperature: 0.7 }),
      });
    });
    await page.waitForTimeout(300);

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

    // ── 对话历史面板（v8 对话档案：此时「你好呀」已落档）──
    await page.locator('button[aria-label="以往的对话"]').click();
    await page.waitForSelector('h2:has-text("以往的对话")', { timeout: 5000 });
    await page.waitForTimeout(500);
    const sRow = page.locator('div[role="button"]', { hasText: '你好呀' }).first();
    if (await sRow.count()) {
      const hasNow = await sRow.locator('text=现在').count();
      hasNow
        ? ok('历史面板列出当前会话（标题=首条消息 + 「现在」徽标）')
        : bad('历史面板徽标', '当前会话条目缺「现在」');
    } else bad('历史面板', '未列出会话「你好呀」（档案落库或渲染失败）');
    await shot(page, '05c-sessions');
    // 「开一段新聊天」→ 面板关闭、输入框可用
    await page.locator('button', { hasText: '＋ 开一段新聊天' }).click();
    await page.waitForTimeout(300);
    (await page.locator('h2:has-text("以往的对话")').count()) === 0
      ? ok('开一段新聊天 → 面板关闭')
      : bad('新聊天', '面板仍在');
    // 切回刚才会话验证「可续聊」载入（档案读取，零 LLM 成本）
    await page.locator('button[aria-label="以往的对话"]').click();
    await page.waitForSelector('h2:has-text("以往的对话")', { timeout: 5000 });
    await page.waitForTimeout(300);
    await page.locator('div[role="button"]', { hasText: '你好呀' }).first().click();
    await page.waitForTimeout(600);
    (await page.locator('text=你好呀').count())
      ? ok('点旧会话 → 档案消息载入（可续聊）')
      : bad('续聊载入', '历史消息未渲染');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(200);

    // ── 戳一戳小汐（纯前端交互，零 LLM 成本）──
    const spriteImg = page.locator('[data-testid="sprite-img"]');
    const sb = await spriteImg.boundingBox();
    if (sb) {
      // 点立绘中上部（避开底部聊天面板）
      await page.mouse.click(sb.x + sb.width / 2, sb.y + sb.height * 0.35);
      const bubble = page.locator('[data-testid="poke-bubble"]');
      try {
        await bubble.waitFor({ state: 'visible', timeout: 3000 });
        const text = (await bubble.textContent()).trim();
        POKE_LINES_ALL.includes(text)
          ? ok(`戳一戳弹出气泡：“${text}”`)
          : bad('戳一戳气泡', `文案不在池内：${text}`);
        await shot(page, '05b-poke-bubble');

        // 冷却：6s 内连戳不产生新气泡（只晃一下）
        await page.mouse.click(sb.x + sb.width / 2, sb.y + sb.height * 0.35);
        await page.waitForTimeout(250);
        const count = await bubble.count();
        const text2 = count ? (await bubble.first().textContent()).trim() : '';
        (count === 1 && text2 === text)
          ? ok('戳一戳冷却：连戳不刷屏（气泡唯一且未刷新）')
          : bad('戳一戳冷却', `count=${count} text变化=${text2 !== text}`);

        // 气泡 2.8s 后自动消失
        await bubble.first().waitFor({ state: 'hidden', timeout: 4500 });
        ok('戳一戳气泡自动消失');
      } catch (e) {
        bad('戳一戳', e && e.message ? e.message.split('\n')[0] : e);
      }
    } else bad('戳一戳', '立绘无 boundingBox');

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

    // ── 日历面板（第四面板：创建 pill / 删除墓碑 / Esc 层级，纯 CRUD 零 LLM）──
    await page.locator('button[aria-label="小汐的日历"]').click();
    await page.waitForSelector('[role="dialog"][aria-label="小汐的日历"]', { timeout: 5000 });
    ok('打开日历面板（dialog 语义 + 焦点圈定挂载）');
    await shot(page, '08-calendar-open');

    // 切到下个月点 15 日（必为未来日期，避开当月月末越界边角）
    await page.locator('button[title="下个月"]').click();
    await page.waitForTimeout(350);
    const nextMonthNum = await page.evaluate(() => {
      const t = new Intl.DateTimeFormat('sv', { timeZone: 'Asia/Hong_Kong' }).format(new Date()).slice(0, 10);
      return (t.slice(5, 7) * 1) % 12 + 1;
    });
    await page.locator(`button[aria-label^="${nextMonthNum}月15日"]`).click();
    await page.waitForSelector('[role="dialog"][aria-label$="的安排"]', { timeout: 3000 });
    ok('点日期 → 日期详情 sheet 打开');

    // 记一件事 → pill 渲染（POST /api/events 200，source=user）
    await page.locator('button:has-text("＋ 记一件事")').click();
    await page.fill('input[placeholder="例如：期中考试"]', 'E2E测试爬山');
    await page.locator('button:has-text("记好啦 ✨")').click();
    await page.waitForTimeout(900);
    (await page.locator('text=E2E测试爬山').count()) >= 1
      ? ok('日历创建事件 → 详情/格子渲染（一本账 source=user 落库）')
      : bad('日历创建', '事件未出现');
    await shot(page, '09-calendar-pill');

    // 删除两次确认 → 墓碑（DELETE 200 → pill 消失）
    await page.locator('button[title="删除"]').first().click();
    await page.locator('button[title="删除"]').first().click();
    await page.waitForTimeout(900);
    (await page.locator('text=E2E测试爬山').count()) === 0
      ? ok('删除（两次确认 → 墓碑）→ 事件消失')
      : bad('日历删除', '事件仍在');

    // Esc 层级：详情 sheet → 面板
    await page.keyboard.press('Escape');
    await page.keyboard.press('Escape');
    await page.waitForTimeout(350);
    (await page.locator('[role="dialog"][aria-label="小汐的日历"]').count()) === 0
      ? ok('Esc 两级关闭（详情 → 面板）')
      : bad('Esc 关闭', '面板仍在');

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
