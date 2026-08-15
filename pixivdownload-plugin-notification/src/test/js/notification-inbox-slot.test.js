'use strict';

const assert = require('assert');
const fs = require('fs');
const path = require('path');

const SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'batch-inbox-slot.js'), 'utf8');
const PAGE_SOURCE = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'pixiv-notifications.js'), 'utf8');
const PAGE_HTML = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications.html'), 'utf8');
const CSS = fs.readFileSync(path.join(__dirname, '..', '..', 'main', 'resources', 'static',
    'pixiv-notifications', 'pixiv-notifications.css'), 'utf8');

assert.ok(SOURCE.includes('queueTypes.registerUiModule(function (context)'),
    '站内信顶栏入口应使用受控下载页 uiSlot initializer');
assert.ok(SOURCE.includes("response.status === 401 || response.status === 403")
    && SOURCE.includes('removeRendered();'),
    '非管理员响应必须移除入口');
assert.ok(SOURCE.includes("context.onCleanup(function ()")
    && SOURCE.includes("removeEventListener('pixivbatch:slotsrendered'")
    && SOURCE.includes('removeRendered();'),
    '插件 publication 清理时必须移除监听器与 DOM');
assert.ok(SOURCE.includes("popover.setAttribute('popover', 'auto')")
    && SOURCE.includes("button.setAttribute('aria-label'"),
    '入口应使用原生 popover 并提供可访问名称');
assert.ok(CSS.includes('.notification-page {') && !/(^|\n)body\s*\{/.test(CSS),
    '插件页面样式不得通过裸 body 选择器污染下载工作台');
assert.ok(CSS.includes('--notification-brand: var(--brand, #0096fa);')
    && /html\[data-theme="dark"\]\s*\{[^}]*--notification-brand:\s*var\(--brand, #4bb3ff\);/s.test(CSS),
    '站内信独立页必须使用蓝白配色并保留宿主品牌色覆盖');
assert.ok(SOURCE.includes("data-i18n-title', 'notification:inbox.open'")
    && SOURCE.includes("data-i18n', 'notification:inbox.latest'"),
    '动态顶栏入口与弹窗必须跟随下载页语言切换');
assert.ok(SOURCE.includes('var snapshot = {messages: [], unreadCount: 0};')
    && SOURCE.includes('global.PixivVue.prepareSlotHosts(document);')
    && /createPopover\(\);[\s\S]*prepareSlotHosts\(document\);[\s\S]*render\(\);[\s\S]*refresh\(\);/.test(SOURCE),
    '入口必须先建立共享槽位并渲染，不能依赖首次消息请求成功');
assert.strictEqual((CSS.match(/--notification-topbar-icon:\s*var\(--brand-text, var\(--text,/g) || []).length, 2,
    '顶栏图标必须兼容旧版品牌色与新版明暗主题文本色');
assert.ok(/\.ab-topbar \.notification-inbox-button\s*\{[^}]*border-radius:\s*var\(--r-sm\);[^}]*background:\s*transparent;/s.test(CSS)
    && /\.ab-topbar \.notification-inbox-button:hover\s*\{[^}]*background:\s*var\(--hover-bg\);/s.test(CSS),
    '新版顶栏站内信入口必须使用纯图标区的透明底与悬停样式');
assert.ok(PAGE_SOURCE.includes('state.selectedMessage && !matchesCategory(state.selectedMessage)')
    && PAGE_SOURCE.includes('clearSelection(true);'),
    '切换分类时必须清除不属于新分类的右侧详情');
assert.ok(/if \(state\.selectedId !== id\) return;[\s\S]*if \(!matchesCategory\(message\)\) \{[\s\S]*clearSelection\(true\);/s.test(PAGE_SOURCE),
    '异步加载完成时必须拒绝过期或分类不匹配的详情');
assert.ok(PAGE_SOURCE.includes('function isoTime(epochMillis)')
    && PAGE_SOURCE.includes("Number.isFinite(date.getTime()) ? date.toISOString() : ''")
    && (PAGE_SOURCE.match(/time\.dateTime = isoTime\(message\.createdTime\);/g) || []).length === 2,
    '非法公告时间不得中断列表或详情渲染');
assert.ok(PAGE_SOURCE.includes('snapshot.categoryUnreadCount')
    && PAGE_SOURCE.includes("query.set('unreadOnly', 'true')")
    && PAGE_SOURCE.includes("api('/api/notifications/read-all' + query"),
    '分类页必须使用当前分类未读数并支持仅看未读与当前分类全部已读');
assert.ok(PAGE_SOURCE.includes("var autoRead = message.category === 'survey' || message.category === 'announcement'")
    && PAGE_SOURCE.includes('if (!autoRead) toolbar.appendChild(markRead)')
    && PAGE_SOURCE.includes('markSelectedRead(null, true, true)')
    && PAGE_SOURCE.includes('if (!keepSelection)')
    && !SOURCE.includes("encodeURIComponent(message.id) + '/read'"),
    '调查与公告详情必须自动标记已读且仅看未读时保留已打开详情，弹窗点击不等待冗余请求');
assert.ok(PAGE_SOURCE.includes('requestSequence !== loadSequence')
    && (PAGE_SOURCE.match(/\+\+loadSequence;/g) || []).length >= 4
    && /response\.status === 401[\s\S]*?\+\+loadSequence;[\s\S]*?location\.href = '\/login\.html/.test(PAGE_SOURCE)
    && (PAGE_SOURCE.match(/\+\+loadSequence;\s*if \(state\.selectedId !== id\) return;/g) || []).length === 2
    && PAGE_SOURCE.includes('if (id) selectMessage(id, false); else clearSelection(false);'),
    '列表、写操作与登录跳转必须拒绝过期响应，历史导航移除 id 时必须清空详情');
assert.ok(PAGE_SOURCE.includes("window.matchMedia('(max-width: 760px)').matches")
    && PAGE_SOURCE.includes("document.visibilityState === 'visible'")
    && SOURCE.includes('global.clearInterval(refreshTimer)'),
    '移动端打开详情应定位内容，可见页面定时刷新且插件卸载时清理计时器');
assert.ok(PAGE_SOURCE.includes("severity === 'warning' || severity === 'error'")
    && CSS.includes('.notification-list-item.severity-warning')
    && CSS.includes('.notification-detail-panel.severity-error h2'),
    '警告与错误消息必须有可辨识的严重程度样式');
assert.ok(PAGE_HTML.includes("frame-src 'self'; child-src 'self'")
    && PAGE_HTML.includes("default-src 'self'; script-src 'self'; style-src 'self'")
    && PAGE_HTML.includes("connect-src 'self'")
    && PAGE_HTML.includes("object-src 'none'; base-uri 'none'; form-action 'none'"),
    '详细页 CSP 必须限制自身资源、网络连接和 iframe，并禁止对象、表单与 base 改写');
assert.ok(PAGE_HTML.includes('src="/js/pixiv-navigation.js"')
    && PAGE_HTML.includes('data-nav-preferred-href-marker="preferred-download-workbench"'),
    '站内信返回入口必须复用共享导航记录的最近下载工作台地址');
assert.ok(PAGE_SOURCE.includes('(!message.hasHtmlContent && !message.embeddedContentUrl)')
    && PAGE_SOURCE.includes("'/api/notifications/' + encodeURIComponent(message.id) + '/content'")
    && PAGE_SOURCE.includes("return withLanguage('/api/notifications/'")
    && !PAGE_SOURCE.includes('sywyar.github.io')
    && !PAGE_SOURCE.includes('PixivDownloader-Remote-Content'),
    '任意分类的 HTML 正文必须只从本地鉴权端点按当前语言加载');
assert.ok(PAGE_SOURCE.includes("frame.setAttribute('sandbox', 'allow-scripts')")
    && PAGE_SOURCE.includes("frame.setAttribute('referrerpolicy', 'no-referrer')")
    && PAGE_SOURCE.includes("camera 'none'; clipboard-read 'none'; clipboard-write 'none'")
    && !PAGE_SOURCE.includes("frame.setAttribute('credentialless', '')")
    && !PAGE_SOURCE.includes('allow-same-origin'),
    '所有正文 iframe 必须只允许运行脚本、保持不同源隔离和敏感能力禁用');
assert.ok(PAGE_SOURCE.includes("data.type !== 'pixiv-external-link'")
    && PAGE_SOURCE.includes("candidate.contentWindow === event.source")
    && PAGE_SOURCE.includes("event.origin !== 'null'")
    && PAGE_SOURCE.includes("frame.getAttribute('data-embedded-survey') === 'true'")
    && PAGE_SOURCE.includes("window.PixivFeedback.followLink(data.href")
    && PAGE_SOURCE.includes("window.addEventListener('message', handleContentMessage)"),
    'HTML 正文链接只能由当前 iframe 消息桥接到全站外链确认');
assert.ok(PAGE_SOURCE.includes("data.type === 'pixiv-content-height'")
    && PAGE_SOURCE.includes('if (!activeContentFrame(frame)')
    && PAGE_SOURCE.includes('Math.min(2000, Math.max(160, Math.ceil(height + 2)))')
    && PAGE_SOURCE.includes('window.setTimeout(applyHeight, 50)')
    && PAGE_SOURCE.includes('if (frame.style.height !== frameHeight) frame.style.height = frameHeight;')
    && PAGE_SOURCE.includes("frame.setAttribute('scrolling', 'no')")
    && /\.notification-detail-content-frame\s*\{[^}]*width:\s*100%;[^}]*height:\s*1px;[^}]*border:\s*0;[^}]*overflow:\s*hidden;/s.test(CSS)
    && !/\.notification-detail-content-frame\s*\{[^}]*min-height:/s.test(CSS),
    'HTML 正文必须仅按当前 frame 的限频消息在 160-2000px 内自适应高度且不显示独立滚动框');
assert.ok(PAGE_SOURCE.includes("frame.setAttribute('data-embedded-survey', 'true')")
    && PAGE_SOURCE.includes("data.type === 'pixiv-survey-unavailable'")
    && PAGE_HTML.includes('src="/js/pixiv-survey-frame-bridge.js"')
    && PAGE_SOURCE.includes('window.PixivSurveyFrameBridge.createHost({')
    && PAGE_SOURCE.includes('surveyFrameHost.attach(frame, source)')
    && PAGE_SOURCE.includes('isActive: activeContentFrame')
    && PAGE_SOURCE.includes("'/survey-unavailable'")
    && PAGE_SOURCE.includes('message.deletable !== false')
    && PAGE_SOURCE.includes("query.set('lang', pageI18n.lang)")
    && SOURCE.includes("query.set('lang', pageI18n.lang)"),
    '插件调查应在不同源 sandbox 中通过受限通道展示、隐藏删除入口并跟随当前语言');
assert.ok(PAGE_HTML.includes('id="notificationContentFrames"')
    && PAGE_SOURCE.includes('var contentFrames = new Map();')
    && !PAGE_SOURCE.includes("frame.setAttribute('loading', 'eager')")
    && !PAGE_SOURCE.includes('preloadEmbeddedFrames')
    && PAGE_SOURCE.includes("frame.setAttribute('loading', 'lazy')")
    && PAGE_SOURCE.includes("frame.getAttribute('data-content-source') === source")
    && /if \(state\.selectedMessage\) \{[\s\S]*?return;\s*\}\s*try \{/s.test(PAGE_SOURCE),
    '调查 iframe 必须仅在打开消息后创建并按消息与语言复用，重复选择不得重新请求详情或重建正文');
assert.ok(/\.notification-detail-content-frame\[hidden\]\s*\{\s*display:\s*none;\s*\}/s.test(CSS),
    '缓存多封 HTML 正文时必须显式隐藏非当前 iframe，避免正文拼接显示');
assert.ok(/\.notification-page\s*\{[^}]*height:\s*100dvh;[^}]*display:\s*flex;[^}]*overflow:\s*hidden;/s.test(CSS)
    && /\.notification-page-grid\s*\{[^}]*flex:\s*1;[^}]*min-height:\s*0;/s.test(CSS)
    && /\.notification-list\s*\{[^}]*flex:\s*1;[^}]*overflow-y:\s*auto;/s.test(CSS)
    && /\.notification-detail-panel\s*\{[^}]*overflow-y:\s*auto;/s.test(CSS)
    && /@media \(max-width:\s*760px\)[\s\S]*\.notification-page\s*\{[^}]*height:\s*auto;[^}]*overflow:\s*visible;/s.test(CSS),
    '桌面端消息列表与详情必须独立滚动，移动端恢复自然页面滚动');

console.log('notification-inbox-slot.test.js: 25 assertions passed ✓');
