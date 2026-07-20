/**
 * ============================================
 * 映海 - 小汐聊天应用 v0.2.1
 * 前端交互逻辑 (Vanilla JS)
 *
 * 改进点：
 * - AbortController 超时控制
 * - 网络错误自动重试（指数退避）
 * - 健康检查自动恢复
 * - Markdown 渲染（marked.js）
 * - 流式输出中可取消
 * ============================================
 */

// === DOM 元素引用 ===
const chatArea = document.getElementById('chat-area');
const chatMessages = document.getElementById('chat-messages');
const messageInput = document.getElementById('message-input');
const sendBtn = document.getElementById('send-btn');
const typingIndicator = document.getElementById('typing-indicator');
const sessionIdEl = document.getElementById('session-id');

// === 状态管理 ===
let isStreaming = false;
let currentAbortController = null; // 用于取消正在进行的流式请求
let healthOk = false;              // 后端健康状态
let healthCheckTimer = null;        // 健康检查定时器

// === 配置 ===
const CONFIG = {
    STREAM_TIMEOUT_MS: 120_000,    // 流式请求超时：2 分钟
    MAX_RETRIES: 2,                // 最大自动重试次数
    RETRY_BASE_DELAY_MS: 1500,     // 重试基础延迟
    HEALTH_CHECK_INTERVAL_MS: 15_000, // 健康检查间隔：15 秒
    GPS_CACHE_MS: 30 * 60 * 1000,    // GPS 缓存有效期：30 分钟
};

// === GPS 定位 ===
let currentGPS = null;  // { latitude, longitude, time }

/**
 * 获取 GPS 坐标（带 sessionStorage 缓存，30 分钟有效）
 */
function initGeolocation() {
    if (!navigator.geolocation) {
        console.warn('浏览器不支持 Geolocation');
        return;
    }

    // 先检查缓存
    const cached = _getGPSFromCache();
    if (cached) {
        currentGPS = cached;
        console.log('GPS: 使用缓存坐标', cached);
        return;
    }

    // 请求新坐标
    navigator.geolocation.getCurrentPosition(
        (pos) => {
            currentGPS = {
                latitude: pos.coords.latitude,
                longitude: pos.coords.longitude,
                time: Date.now(),
            };
            // 存入 sessionStorage
            try {
                sessionStorage.setItem('xiaoxi_gps', JSON.stringify(currentGPS));
            } catch (e) { /* ignore */ }
            console.log('GPS: 获取新坐标', currentGPS);
        },
        (err) => {
            console.warn('GPS 定位失败:', err.message);
            // 不清除缓存，保持上次已知的坐标
        },
        {
            enableHighAccuracy: true,
            timeout: 10000,
            maximumAge: 5 * 60 * 1000, // 允许使用 5 分钟内的系统缓存
        }
    );
}

/**
 * 从 sessionStorage 读取未过期的 GPS 缓存
 */
function _getGPSFromCache() {
    try {
        const raw = sessionStorage.getItem('xiaoxi_gps');
        if (!raw) return null;
        const data = JSON.parse(raw);
        if (Date.now() - data.time > CONFIG.GPS_CACHE_MS) return null;
        return data;
    } catch (e) {
        return null;
    }
}

/**
 * 获取当前 GPS 坐标（用于附加到请求中）
 */
function getCurrentGPS() {
    // 检查是否过期
    if (currentGPS && Date.now() - currentGPS.time > CONFIG.GPS_CACHE_MS) {
        currentGPS = null;
    }
    return currentGPS;
}

// === 欢迎语 ===
const WELCOME_MESSAGE = `你好呀～我是小汐 🐋

欢迎来到映海——这片由人们的记忆和情感汇聚而成的海洋。

无论你是想聊聊天、分享开心的事，还是心里有些烦恼想倾诉，小汐都在这里陪你哦。

今天想跟小汐说些什么呢？ ✨`;

// === Markdown 渲染配置 ===
function setupMarkdown() {
    if (typeof marked !== 'undefined') {
        marked.setOptions({
            breaks: true,       // \n 转 <br>
            gfm: true,          // GitHub Flavored Markdown
            headerIds: false,   // 不生成 header id
            mangle: false,      // 不转义 header
        });
    }
}

/**
 * 将文本渲染为 HTML（Markdown 或纯文本）
 * @param {string} text - 原始文本
 * @returns {string} 渲染后的 HTML
 */
function renderMarkdown(text) {
    if (typeof marked !== 'undefined') {
        try {
            return marked.parse(text);
        } catch (e) {
            // marked 出错时回退到纯文本
        }
    }
    // 回退：转义 HTML + 保留换行
    return escapeHtml(text).replace(/\n/g, '<br>');
}

// === 初始化 ===
document.addEventListener('DOMContentLoaded', () => {
    setupMarkdown();
    initSession();
    initGeolocation();
    setupInputHandlers();
    showWelcomeMessage();
    startHealthChecks();
});

/**
 * 初始化会话
 */
function initSession() {
    let sessionId = sessionStorage.getItem('xiaoxi_session_id');
    if (!sessionId) {
        sessionId = generateSessionId(8);
        sessionStorage.setItem('xiaoxi_session_id', sessionId);
    }
    sessionIdEl.textContent = sessionId;
}

/**
 * 生成随机会话 ID
 */
function generateSessionId(length) {
    const chars = 'abcdefghijklmnopqrstuvwxyz0123456789';
    let result = '';
    const array = new Uint8Array(length);
    crypto.getRandomValues(array);
    for (let i = 0; i < length; i++) {
        result += chars[array[i] % chars.length];
    }
    return result;
}

// === 健康检查（带自动恢复） ===

/**
 * 启动周期性健康检查
 */
function startHealthChecks() {
    // 首次立即检查
    healthCheck();
    // 周期检查
    healthCheckTimer = setInterval(healthCheck, CONFIG.HEALTH_CHECK_INTERVAL_MS);
}

/**
 * 健康检查 — 如果后端恢复，自动清除错误提示
 */
async function healthCheck() {
    try {
        const controller = new AbortController();
        const timeoutId = setTimeout(() => controller.abort(), 5000);

        const response = await fetch('/api/health', { signal: controller.signal });
        clearTimeout(timeoutId);

        if (response.ok) {
            if (!healthOk) {
                // 从不可用恢复 → 清除错误提示
                healthOk = true;
                removeSystemMessages();
            }
        } else {
            throw new Error('health not ok');
        }
    } catch (error) {
        if (!healthOk) return; // 已经标记为不可用，不重复提示
        healthOk = false;
        // 只在第一次失败时提示
        const existing = document.querySelector('.system-message.health-warning');
        if (!existing) {
            addSystemMessage('小汐还在睡觉... 稍后再来看看吧 💤', 'health-warning');
        }
    }
}

/**
 * 清除系统提示消息（如健康检查恢复后）
 */
function removeSystemMessages() {
    document.querySelectorAll('.system-message.health-warning').forEach(el => {
        el.style.opacity = '0';
        setTimeout(() => el.remove(), 300);
    });
}

/**
 * 显示欢迎消息
 */
function showWelcomeMessage() {
    addMessage(WELCOME_MESSAGE, 'xiaoxi', true);
}

// === 输入处理 ===

function setupInputHandlers() {
    sendBtn.addEventListener('click', handleSendClick);

    messageInput.addEventListener('keydown', (e) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault();
            handleSendClick();
        }
    });

    messageInput.addEventListener('input', () => {
        messageInput.style.height = 'auto';
        messageInput.style.height = Math.min(messageInput.scrollHeight, 120) + 'px';
    });

    messageInput.focus();
}

/**
 * 点击发送按钮：如果正在流式输出，则取消；否则发送消息
 */
function handleSendClick() {
    if (isStreaming && currentAbortController) {
        // 正在流式输出 → 取消
        currentAbortController.abort();
        return;
    }
    handleSend();
}

/**
 * 处理发送消息（带自动重试）
 */
async function handleSend() {
    if (isStreaming) return;

    const text = messageInput.value.trim();
    if (!text) return;

    messageInput.value = '';
    messageInput.style.height = 'auto';

    addMessage(text, 'user');
    showTyping();
    setStreaming(true);

    let lastError = null;

    for (let attempt = 0; attempt <= CONFIG.MAX_RETRIES; attempt++) {
        try {
            if (attempt > 0) {
                // 重试前等待（指数退避）
                const delay = CONFIG.RETRY_BASE_DELAY_MS * Math.pow(2, attempt - 1);
                await sleep(delay);
                hideTyping();
                showTyping();
            }
            await streamChat(text);
            lastError = null;
            break; // 成功，跳出重试循环
        } catch (error) {
            lastError = error;
            const isAbort = error.name === 'AbortError';
            if (isAbort) break; // 用户主动取消，不重试

            console.warn(`发送失败 (第 ${attempt + 1} 次):`, error.message);
            if (attempt < CONFIG.MAX_RETRIES) continue;
        }
    }

    hideTyping();

    if (lastError) {
        const isAbort = lastError.name === 'AbortError';
        if (isAbort) {
            addSystemMessage('已取消发送');
        } else {
            const errMsg = lastError.message || String(lastError);
            addMessage(
                '哎呀，好像出了点问题... 再试一次吧？ 🥺\n\n（' + errMsg + '）',
                'xiaoxi'
            );
        }
    }

    setStreaming(false);
}

/**
 * 流式聊天请求（带超时和取消控制）
 */
async function streamChat(text) {
    const sessionId = sessionStorage.getItem('xiaoxi_session_id');

    // 创建 AbortController 用于超时和手动取消
    currentAbortController = new AbortController();
    const timeoutId = setTimeout(
        () => currentAbortController.abort(),
        CONFIG.STREAM_TIMEOUT_MS
    );

    let response;
    try {
        // 构建请求体（附带 GPS 坐标）
        const body = {
            message: text,
            session_id: sessionId,
        };
        const gps = getCurrentGPS();
        if (gps) {
            body.latitude = gps.latitude;
            body.longitude = gps.longitude;
        }

        response = await fetch('/api/chat/stream', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(body),
            signal: currentAbortController.signal,
        });
    } finally {
        clearTimeout(timeoutId);
    }

    if (!response.ok) {
        throw new Error(`HTTP ${response.status}: ${response.statusText}`);
    }

    hideTyping();

    const { bubble } = addMessage('', 'xiaoxi');
    let accumulatedText = '';

    const reader = response.body.getReader();
    const decoder = new TextDecoder('utf-8');
    let buffer = '';

    try {
        while (true) {
            const { done, value } = await reader.read();
            if (done) break;

            buffer += decoder.decode(value, { stream: true });
            const lines = buffer.split('\n');
            buffer = lines.pop() || '';

            for (const line of lines) {
                const trimmed = line.trim();
                if (!trimmed || trimmed.startsWith(':')) continue;

                if (trimmed.startsWith('data: ')) {
                    const data = trimmed.slice(6);

                    if (data === '[DONE]') {
                        finalizeMessage(bubble, accumulatedText);
                        currentAbortController = null;
                        return;
                    }

                    try {
                        const parsed = JSON.parse(data);

                        // 工具事件（静默处理，不显示给用户）
                        if (parsed.type === 'tool_start' || parsed.type === 'tool_end') {
                            continue;
                        }

                        const content = parsed.content
                            || parsed.delta
                            || parsed.text
                            || parsed.message
                            || (typeof parsed === 'string' ? parsed : '');

                        if (content) {
                            accumulatedText += content;
                            updateBubbleHtml(bubble, accumulatedText);
                            scrollToBottom();
                        }
                    } catch (e) {
                        if (data !== '[DONE]') {
                            accumulatedText += data;
                            updateBubbleHtml(bubble, accumulatedText);
                            scrollToBottom();
                        }
                    }
                }
            }
        }
    } finally {
        currentAbortController = null;
    }

    if (accumulatedText) {
        finalizeMessage(bubble, accumulatedText);
    }
}

// === DOM 操作 ===

/**
 * 添加消息到聊天区域
 */
function addMessage(text, sender, isWelcome = false) {
    const messageEl = document.createElement('div');
    messageEl.className = `message ${sender}${isWelcome ? ' welcome' : ''}`;

    const now = new Date();
    const timeStr = `${now.getHours().toString().padStart(2, '0')}:${now.getMinutes().toString().padStart(2, '0')}`;

    if (sender === 'xiaoxi') {
        messageEl.innerHTML = `
            <div class="message-avatar">🐋</div>
            <div class="message-content">
                <div class="message-bubble">${renderMarkdown(text)}</div>
                <div class="message-time">${timeStr}</div>
            </div>
        `;
    } else {
        // 用户消息用纯文本，不渲染 Markdown
        messageEl.innerHTML = `
            <div class="message-content">
                <div class="message-bubble">${escapeHtml(text)}</div>
                <div class="message-time">${timeStr}</div>
            </div>
        `;
    }

    chatMessages.appendChild(messageEl);
    scrollToBottom();

    const bubble = messageEl.querySelector('.message-bubble');
    return { element: messageEl, bubble };
}

/**
 * 流式更新气泡内容（Markdown 渲染）
 */
function updateBubbleHtml(bubble, text) {
    bubble.innerHTML = renderMarkdown(text);
}

/**
 * 完成消息 — 最终 Markdown 渲染 + 添加 finalized 样式
 */
function finalizeMessage(bubble, text) {
    if (text) {
        bubble.innerHTML = renderMarkdown(text);
    }
    const messageEl = bubble.closest('.message');
    if (messageEl) {
        messageEl.classList.add('finalized');
    }
}

/**
 * 添加系统提示消息
 */
function addSystemMessage(text, extraClass = '') {
    const msgEl = document.createElement('div');
    msgEl.className = 'system-message' + (extraClass ? ' ' + extraClass : '');
    msgEl.textContent = text;
    chatMessages.appendChild(msgEl);
    scrollToBottom();
}

/**
 * HTML 转义，防止 XSS
 */
function escapeHtml(text) {
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

/**
 * 平滑滚动到底部
 */
function scrollToBottom() {
    requestAnimationFrame(() => {
        chatArea.scrollTop = chatArea.scrollHeight;
    });
}

// === 打字指示器 ===

function showTyping() {
    typingIndicator.style.display = 'flex';
    scrollToBottom();
}

function hideTyping() {
    typingIndicator.style.display = 'none';
}

// === 流式状态控制 ===

function setStreaming(streaming) {
    isStreaming = streaming;
    messageInput.disabled = streaming;

    if (streaming) {
        // 流式输出中：按钮变为"停止"样式
        sendBtn.classList.add('streaming');
        sendBtn.disabled = false; // 允许点击取消
        sendBtn.title = '停止生成';
    } else {
        sendBtn.classList.remove('streaming');
        sendBtn.disabled = false;
        sendBtn.title = '发送消息';
        messageInput.focus();
    }
}

// === 工具函数 ===

function sleep(ms) {
    return new Promise(resolve => setTimeout(resolve, ms));
}

// === 控制台设置面板 ===

const settingsBtn = document.getElementById('settings-btn');
const settingsOverlay = document.getElementById('settings-overlay');
const settingsClose = document.getElementById('settings-close');
const modelCards = document.getElementById('model-cards');
const statusModel = document.getElementById('status-model');
const statusTemp = document.getElementById('status-temp');
const statusTokens = document.getElementById('status-tokens');

let currentConfig = {
    model: 'deepseek-chat',
    temperature: 1.2,
    max_tokens: 8192,
    thinking_intensity: 'max',
    available_models: [],
};

// 打开/关闭设置面板
settingsBtn.addEventListener('click', () => {
    openSettings();
});

settingsClose.addEventListener('click', () => {
    closeSettings();
});

settingsOverlay.addEventListener('click', (e) => {
    if (e.target === settingsOverlay) {
        closeSettings();
    }
});

// ESC 键关闭
document.addEventListener('keydown', (e) => {
    if (e.key === 'Escape' && settingsOverlay.classList.contains('visible')) {
        closeSettings();
    }
});

async function openSettings() {
    settingsOverlay.classList.add('visible');
    await loadConfig();
    loadMemoryCount();
}

function closeSettings() {
    settingsOverlay.classList.remove('visible');
}

// 加载配置
async function loadConfig() {
    try {
        const res = await fetch('/api/config');
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
        }
    } catch (e) {
        console.warn('加载配置失败:', e);
    }
}

// 渲染设置面板
function renderSettings() {
    // 渲染模型卡片
    const models = currentConfig.available_models || [];
    modelCards.innerHTML = models.map(m => `
        <div class="model-card ${m.id === currentConfig.model ? 'active' : ''}" data-model="${m.id}">
            <div class="model-card-icon">${m.supports_thinking ? '🧠' : '💬'}</div>
            <div class="model-card-info">
                <div class="model-card-name">${m.name}</div>
                <div class="model-card-desc">${m.description}</div>
            </div>
            <div class="model-card-check"></div>
        </div>
    `).join('');

    // 绑定模型选择事件
    modelCards.querySelectorAll('.model-card').forEach(card => {
        card.addEventListener('click', () => {
            const modelId = card.dataset.model;
            selectModel(modelId);
        });
    });

    // 渲染思考强度
    document.querySelectorAll('.thinking-option').forEach(btn => {
        const intensity = btn.dataset.intensity;
        btn.classList.toggle('active', intensity === currentConfig.thinking_intensity);
        btn.onclick = () => selectThinkingIntensity(intensity);
    });

    // 更新状态显示
    const currentModel = models.find(m => m.id === currentConfig.model);
    statusModel.textContent = currentModel ? currentModel.name : currentConfig.model;
    statusTemp.textContent = currentConfig.temperature.toFixed(1);
    statusTokens.textContent = currentConfig.max_tokens + ' tokens';
}

// 选择模型
async function selectModel(modelId) {
    if (modelId === currentConfig.model) return;

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ model: modelId }),
        });
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
            addSystemMessage(`已切换到 ${currentConfig.available_models.find(m => m.id === modelId)?.name || modelId}`);
        }
    } catch (e) {
        console.warn('切换模型失败:', e);
        addSystemMessage('切换模型失败，请稍后重试');
    }
}

// 选择思考强度
async function selectThinkingIntensity(intensity) {
    if (intensity === currentConfig.thinking_intensity) return;

    try {
        const res = await fetch('/api/config', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ thinking_intensity: intensity }),
        });
        if (res.ok) {
            currentConfig = await res.json();
            renderSettings();
            const labels = { low: '轻柔', medium: '适中', high: '深度', max: '全力' };
            addSystemMessage(`思考强度已调整为「${labels[intensity] || intensity}」`);
        }
    } catch (e) {
        console.warn('调整思考强度失败:', e);
        addSystemMessage('调整失败，请稍后重试');
    }
}

// 页面加载时初始化配置
loadConfig();

// === 记忆管理 ===

const btnExportMemories = document.getElementById('btn-export-memories');
const btnClearMemories = document.getElementById('btn-clear-memories');
const memoryCountEl = document.getElementById('memory-count');

btnExportMemories.addEventListener('click', exportMemories);
btnClearMemories.addEventListener('click', clearMemories);

/**
 * 加载记忆数量
 */
async function loadMemoryCount() {
    try {
        const res = await fetch('/api/health');
        if (res.ok) {
            const data = await res.json();
            const count = data.memory_count ?? 0;
            memoryCountEl.textContent = count > 0 ? `${count} 条` : '空空如也';
        }
    } catch (e) {
        memoryCountEl.textContent = '无法获取';
    }
}

/**
 * 导出所有记忆为 JSON 文件
 */
async function exportMemories() {
    try {
        btnExportMemories.disabled = true;
        btnExportMemories.textContent = '导出中…';

        const res = await fetch('/api/memories/export');
        if (!res.ok) throw new Error('导出失败');

        const blob = await res.blob();
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a');
        a.href = url;
        a.download = `xiaoxi_memories_${new Date().toISOString().slice(0, 10)}.json`;
        document.body.appendChild(a);
        a.click();
        document.body.removeChild(a);
        URL.revokeObjectURL(url);

        addSystemMessage('记忆已导出 ✨');
    } catch (e) {
        console.warn('导出记忆失败:', e);
        addSystemMessage('导出记忆失败了，请稍后重试');
    } finally {
        btnExportMemories.disabled = false;
        btnExportMemories.textContent = '📥 导出记忆';
    }
}

/**
 * 清空所有记忆（二次确认）
 */
async function clearMemories() {
    const confirmed = confirm('确定要清空小汐的所有记忆吗？\n\n这个操作不可撤销，映海里将变得空空的……');
    if (!confirmed) return;

    try {
        btnClearMemories.disabled = true;
        btnClearMemories.textContent = '清空中…';

        const res = await fetch('/api/memories/clear', { method: 'POST' });
        if (!res.ok) throw new Error('清空失败');

        const data = await res.json();
        memoryCountEl.textContent = '空空如也';
        addSystemMessage(`已清空 ${data.cleared} 条记忆`);
    } catch (e) {
        console.warn('清空记忆失败:', e);
        addSystemMessage('清空记忆失败了，请稍后重试');
    } finally {
        btnClearMemories.disabled = false;
        btnClearMemories.textContent = '🗑️ 清空记忆';
    }
}

// 页面加载时也加载记忆数量
loadMemoryCount();
