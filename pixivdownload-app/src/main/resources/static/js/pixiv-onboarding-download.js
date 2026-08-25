/* 跨页新用户引导的下载页、网络检测与下载结果流程。 */
(function (global) {
    'use strict';

    var ctx = global.PixivOnboardingRuntime;
    if (!ctx || !ctx.overlay || !ctx.footHtml || !ctx.bindFoot) {
        return;
    }

    var EXAMPLE_ID = ctx.EXAMPLE_ID;
    var EXAMPLE_URL = ctx.EXAMPLE_URL;
    var t = ctx.t;
    var escapeHtml = ctx.escapeHtml;
    var loadState = ctx.loadState;
    var patchState = ctx.patchState;
    var markCompleted = ctx.markCompleted;
    var SKIP_BTN = ctx.SKIP_BTN;
    var overlay = ctx.overlay;
    var footHtml = ctx.footHtml;
    var bindFoot = ctx.bindFoot;
    var skip = ctx.skip;
    var finish = ctx.finish;
    var hook = ctx.hook;
    var callHook = ctx.callHook;
    var waitFor = ctx.waitFor;
    var waitForElement = ctx.waitForElement;
    var notifyCompletionStepDone = ctx.notifyCompletionStepDone;

    function phaseWelcome() {
        patchState({status: 'active', phase: 'welcome'});
        // 已保存过称呼（本地或服务端）则跳过称呼步，直接进入连通性检测
        if (hasSavedName()) {
            screenNetwork();
        } else {
            screenName();
        }
    }

    function hasSavedName() {
        var local = loadState().name;
        if (local && local.trim()) {
            return true;
        }
        return !!(ctx.config && ctx.config.savedName && ctx.config.savedName.trim());
    }

    function screenName() {
        var existing = loadState().name || '';
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.welcome.title', '👋 欢迎使用 PixivDownloader')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.welcome.intro', '初次见面！我会带你下载第一份示例作品，熟悉整个流程。先告诉我，怎么称呼你？')) + '</p>'
                + '<input type="text" class="po-input" id="po-name-input" maxlength="40" placeholder="'
                + escapeHtml(t('onboarding.welcome.name-placeholder', '输入你的称呼（可留空）')) + '" value="' + escapeHtml(existing) + '">'
                + '<div class="po-hint" id="po-name-hint"></div>'
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ])
        });
        var input = overlay.qs('#po-name-input');
        if (input) {
            input.focus();
            input.addEventListener('keydown', function (e) {
                if (e.key === 'Enter') {
                    submitName();
                }
            });
        }
        bindFoot({skip: skip, next: submitName});
    }

    function submitName() {
        var input = overlay.qs('#po-name-input');
        var name = input ? input.value.trim() : '';
        patchState({name: name});
        // 持久化到服务端（best-effort）并即时刷新当前页占位
        saveProfileName(name);
        callHook('applyName', name);
        screenNetwork();
    }

    function saveProfileName(name) {
        try {
            fetch('/api/onboarding/profile', {
                method: 'POST',
                headers: {'Content-Type': 'application/json'},
                credentials: 'same-origin',
                body: JSON.stringify({displayName: name})
            }).catch(function () { /* best-effort */ });
        } catch (e) {
            /* ignore */
        }
    }

    function greetName() {
        var name = loadState().name;
        if (name && name.trim()) {
            return name.trim();
        }
        if (ctx.config && ctx.config.savedName && ctx.config.savedName.trim()) {
            return ctx.config.savedName.trim();
        }
        return t('onboarding.welcome.default-name', '朋友');
    }

    function screenNetwork() {
        renderNetwork('checking', null);
        runNetworkCheck();
    }

    function renderNetwork(stateName, data) {
        var bodyTop = '<p>' + escapeHtml(t('onboarding.network.intro',
            '你好，{name}！开始之前，先检查后端能否连上 Pixiv（全程经你配置的代理）。', {name: greetName()})) + '</p>';
        var netRow;
        var buttons;
        if (stateName === 'checking') {
            netRow = '<div class="po-net"><span class="po-spinner"></span><span>'
                + escapeHtml(t('onboarding.network.checking', '正在检测网络…')) + '</span></div>';
            buttons = [SKIP_BTN(), {act: 'wait', label: t('onboarding.network.checking-btn', '检测中…'), variant: 'primary', disabled: true}];
        } else if (stateName === 'ok') {
            netRow = '<div class="po-net"><span class="po-net-ok">✓ '
                + escapeHtml(t('onboarding.network.ok', 'Pixiv 可达：{ping}ms', {ping: data.latencyMs})) + '</span></div>';
            buttons = [SKIP_BTN(), {act: 'next', label: t('onboarding.network.start-download', '开始下载第一份作品'), variant: 'primary'}];
        } else {
            netRow = '<div class="po-net"><span class="po-net-fail">✕ '
                + escapeHtml(t('onboarding.network.fail', '无法连接 Pixiv，请检查代理 / 网络设置')) + '</span></div>'
                + '<div class="po-hint">' + escapeHtml(t('onboarding.network.fail-hint',
                    '常见原因：代理未开启或端口不对。请在程序的「设置 / config.yaml」中配置 proxy.host / proxy.port 后重试。')) + '</div>';
            buttons = [SKIP_BTN(), {act: 'retry', label: t('onboarding.network.retry', '重试'), variant: 'primary'}];
        }
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.network.title', '① 检查网络是否可达')) + '</h3>'
                + '<div class="po-pop-body">' + bodyTop + netRow + '</div>'
                + footHtml(buttons)
        });
        bindFoot({
            skip: skip,
            retry: screenNetwork,
            next: function () {
                patchState({phase: 'download'});
                phaseDownload();
            },
            wait: function () { /* no-op */ }
        });
    }

    function runNetworkCheck() {
        fetch('/api/onboarding/connectivity', {credentials: 'same-origin'})
            .then(function (r) {
                if (!r.ok) {
                    throw new Error('HTTP ' + r.status);
                }
                return r.json();
            })
            .then(function (data) {
                if (data && data.reachable) {
                    renderNetwork('ok', data);
                } else {
                    renderNetwork('fail', data);
                }
            })
            .catch(function () {
                renderNetwork('fail', null);
            });
    }

    // 阶段：先逐区域认识下载页（Cookie / 油猴脚本 / Tab 模式），再引导下载第一份示例作品（聚光步骤）
    function phaseDownload() {
        stepCookie();
    }

    function orientProgress(step) {
        return t('onboarding.orient.progress', '认识下载页 · {step}/4', {step: step});
    }

    // 认识下载页 ①：Cookie 区域
    function stepCookie() {
        overlay.render({
            targetSelector: ctx.config.sel.cookieCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.cookie.title', '① Cookie 区域')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.cookie.body',
                    '这里填写你的 Pixiv Cookie。想下载需要登录才能看的作品（R-18、关注限定等）时，必须先在这里保存 Cookie；本次示例是全年龄作品，可以先不配置。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(1))
        });
        bindFoot({skip: skip, next: stepScripts});
    }

    // 认识下载页 ②：油猴脚本区域
    function stepScripts() {
        overlay.render({
            targetSelector: ctx.config.sel.scriptsCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.scripts.title', '② 油猴脚本区域')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.scripts.body',
                    '这里可以安装配套的油猴脚本：在 Pixiv 站内一键导入登录 Cookie、抓取作品链接、批量下载等。本次示例用不到，先了解即可。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(2))
        });
        bindFoot({skip: skip, next: stepTabs});
    }

    // 认识下载页 ③：下载方式 Tab
    function stepTabs() {
        overlay.render({
            targetSelector: ctx.config.sel.tabs,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.tabs.title', '③ 下载方式')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.tabs.body',
                    '这里切换不同的下载方式：快捷获取我的收藏 / 关注 / 作品、批量导入单作品链接、按画师 ID 下载、关键词搜索、整系列下载。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], orientProgress(3))
        });
        bindFoot({skip: skip, next: stepChooseExample});
    }

    // 认识下载页 ④：选择本次示例所用的下载方式（切到「批量导入单作品」）
    function stepChooseExample() {
        callHook('switchToSingleImport');
        // 切换 Tab 后单作品面板稍后才可见，延迟渲染让聚光定位到位
        global.setTimeout(function () {
            overlay.render({
                targetSelector: ctx.config.sel.singleImportTab,
                html:
                    '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.orient.choose.title', '④ 选择下载方式')) + '</h3>'
                    + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.orient.choose.body',
                        '第一次，我们来使用「批量导入单作品」作为示例。已为你切换到该模式，点「下一步」开始下载示例作品。')) + '</p></div>'
                    + footHtml([
                        SKIP_BTN(),
                        {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                    ], orientProgress(4))
            });
            bindFoot({skip: skip, next: stepPasteUrl});
        }, 120);
    }

    function stepPasteUrl() {
        // 模式已在「选择下载方式」步切到单作品；再幂等确认一次后直接渲染
        callHook('switchToSingleImport');
        renderPasteUrl();
    }

    function renderPasteUrl() {
        overlay.render({
            targetSelector: ctx.config.sel.importTextarea,
            interactiveSelector: ctx.config.sel.importTextarea,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.paste.title', '① 粘贴示例作品链接')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.download.paste.body', '复制下面这个示例作品链接，粘贴到高亮的输入框里：')) + '</p>'
                + '<div class="po-codeblock">'
                + '<code class="po-code" id="po-example-url">' + escapeHtml(EXAMPLE_URL) + '</code>'
                + '<button type="button" class="po-btn po-copy-btn" id="po-copy-btn">'
                + escapeHtml(t('onboarding.common.copy', '复制')) + '</button>'
                + '</div>'
                + '<div class="po-hint" id="po-paste-hint"></div>'
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary', disabled: true}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 1}))
        });
        var copyBtn = overlay.qs('#po-copy-btn');
        if (copyBtn) {
            copyBtn.addEventListener('click', function () {
                copyExampleUrl(copyBtn);
            });
        }
        bindFoot({skip: skip, next: stepAddQueue});
        watchPasteInput();
    }

    function copyExampleUrl(btn) {
        var done = function () {
            btn.classList.add('po-copied');
            btn.textContent = t('onboarding.common.copied', '已复制');
            global.setTimeout(function () {
                btn.classList.remove('po-copied');
                btn.textContent = t('onboarding.common.copy', '复制');
            }, 1500);
        };
        if (global.navigator && global.navigator.clipboard && global.navigator.clipboard.writeText) {
            global.navigator.clipboard.writeText(EXAMPLE_URL).then(done).catch(function () {
                legacyCopy(EXAMPLE_URL);
                done();
            });
        } else {
            legacyCopy(EXAMPLE_URL);
            done();
        }
    }

    function legacyCopy(text) {
        try {
            var ta = document.createElement('textarea');
            ta.value = text;
            ta.style.position = 'fixed';
            ta.style.opacity = '0';
            document.body.appendChild(ta);
            ta.select();
            document.execCommand('copy');
            document.body.removeChild(ta);
        } catch (e) {
            /* ignore */
        }
    }

    var _pasteWatchTimer = null;

    function watchPasteInput() {
        if (_pasteWatchTimer) {
            global.clearInterval(_pasteWatchTimer);
        }
        var check = function () {
            if (!overlay.pop) {
                global.clearInterval(_pasteWatchTimer);
                _pasteWatchTimer = null;
                return;
            }
            var ta = document.querySelector(ctx.config.sel.importTextarea);
            var nextBtn = overlay.qs('[data-act="next"]');
            var hint = overlay.qs('#po-paste-hint');
            if (!ta || !nextBtn) {
                return;
            }
            var verdict = classifyPasteValue(ta.value);
            nextBtn.disabled = !verdict.ok;
            if (hint) {
                if (verdict.ok) {
                    hint.className = 'po-hint po-hint-ok';
                    hint.textContent = t('onboarding.download.paste.ok', '✓ 已识别示例作品，点「下一步」继续');
                } else if (verdict.foreign) {
                    hint.className = 'po-hint po-hint-error';
                    hint.textContent = t('onboarding.download.paste.foreign', '检测到其它内容，请先完成指引哦～本步只粘贴上面的示例链接');
                } else {
                    hint.className = 'po-hint';
                    hint.textContent = '';
                }
            }
        };
        _pasteWatchTimer = global.setInterval(check, 300);
        check();
    }

    // 判定输入框内容：ok=含示例且无杂项；foreign=含与示例无关的内容
    function classifyPasteValue(value) {
        var lines = String(value || '').split('\n')
            .map(function (l) { return l.trim(); })
            .filter(function (l) { return l.length > 0; });
        if (!lines.length) {
            return {ok: false, foreign: false};
        }
        var hasExample = false;
        var hasForeign = false;
        lines.forEach(function (line) {
            if (lineRefersExample(line)) {
                hasExample = true;
            } else {
                hasForeign = true;
            }
        });
        return {ok: hasExample && !hasForeign, foreign: hasForeign};
    }

    function lineRefersExample(line) {
        // 接受：完整链接 / 形如 "url | title" / 纯 ID / "id | title"
        var head = line.split('|')[0].trim();
        if (head === EXAMPLE_ID) {
            return true;
        }
        var m = head.match(/artworks\/(\d+)/);
        if (m && m[1] === EXAMPLE_ID) {
            return true;
        }
        return false;
    }

    function stepAddQueue() {
        if (_pasteWatchTimer) {
            global.clearInterval(_pasteWatchTimer);
            _pasteWatchTimer = null;
        }
        overlay.render({
            targetSelector: ctx.config.sel.importButton,
            interactiveSelector: ctx.config.sel.importButton,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.queue.title', '② 加入下载队列')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.queue.body',
                    '点击高亮的「导入并加入队列」按钮，把示例作品加入下载队列。')) + '</p></div>'
                + footHtml([SKIP_BTN()], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 2}))
        });
        bindFoot({skip: skip});
        waitFor(function () {
            return !!callHook('isExampleQueued', EXAMPLE_ID);
        }, stepFilters);
    }

    // 入队后逐一介绍「附加筛选」「下载设置」两块卡片（仅讲解，遮罩拦截交互、本步不允许修改）
    function stepFilters() {
        overlay.render({
            targetSelector: ctx.config.sel.filtersCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.filters.title', '③ 附加筛选')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.filters.body',
                    '可按内容分级、AI、标签、类型、页数、收藏数等条件过滤要下载的作品，不符合条件的会在下载时自动跳过。先了解一下，这一步暂不修改。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 3}))
        });
        bindFoot({skip: skip, next: stepSettings});
    }

    function stepSettings() {
        overlay.render({
            targetSelector: ctx.config.sel.settingsCard,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.settings.title', '④ 下载设置')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.settings.body',
                    '这里设置作品间隔、并发数、是否跳过已下载、下载后自动收藏、文件名格式等。先了解一下，这一步暂不修改。')) + '</p></div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'next', label: t('onboarding.common.next', '下一步'), variant: 'primary'}
                ], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 4}))
        });
        bindFoot({skip: skip, next: stepStart});
    }

    function stepStart() {
        callHook('beforeStart');
        overlay.render({
            targetSelector: ctx.config.sel.startButton,
            interactiveSelector: ctx.config.sel.startButton,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.download.start.title', '⑤ 开始下载')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.download.start.body',
                    '点击高亮的「开始下载」按钮。下载期间请保持本页打开。')) + '</p></div>'
                + footHtml([SKIP_BTN()], t('onboarding.download.progress', '下载示例 · {step}/5', {step: 5}))
        });
        bindFoot({skip: skip});
        waitFor(function () {
            return !!callHook('isRunning');
        }, phaseMonitor);
    }

    // 阶段：监听示例作品下载结果（轮询后端状态，解耦于页面 SSE）
    function phaseMonitor() {
        renderMonitor();
        pollDownloadStatus();
    }

    function renderMonitor() {
        var monitorBody = findFirstDownloadResultEntry()
            ? t('onboarding.monitor.body',
                '正在下载，请稍候…下方的状态栏与下载队列会实时显示进度，完成后会自动带你去画廊查看。')
            : t('onboarding.monitor.body.no-result',
                '正在下载，请稍候…下方的状态栏与下载队列会实时显示进度，完成后会提示你查看下载结果。');
        // 高亮下载状态 + 队列区域，让用户实时看到下载进度（仅高亮、不可交互）
        overlay.render({
            targetSelector: ctx.config.sel.progressArea,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.title', '⏳ 正在下载示例作品')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<div class="po-net"><span class="po-spinner"></span><span>'
                + escapeHtml(monitorBody) + '</span></div>'
                + '</div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
    }

    var _monitorTimer = null;

    function pollDownloadStatus() {
        if (_monitorTimer) {
            global.clearInterval(_monitorTimer);
        }
        var attempts = 0;
        _monitorTimer = global.setInterval(function () {
            attempts++;
            if (!overlay.pop) {
                global.clearInterval(_monitorTimer);
                _monitorTimer = null;
                return;
            }
            fetch('/api/download/status/' + EXAMPLE_ID, {credentials: 'same-origin'})
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (data) {
                    if (!data) {
                        return;
                    }
                    if (data.completed) {
                        stopMonitor();
                        monitorSucceeded();
                    } else if (data.failed) {
                        stopMonitor();
                        monitorFailed(data.message || '');
                    }
                })
                .catch(function () { /* 网络抖动：下一轮再试 */ });
            if (attempts > 150) { // ~5 分钟保护，避免无限轮询
                stopMonitor();
            }
        }, 2000);
    }

    function stopMonitor() {
        if (_monitorTimer) {
            global.clearInterval(_monitorTimer);
            _monitorTimer = null;
        }
    }

    function firstDownloadResultEntrySelector() {
        if (!ctx.config || !ctx.config.sel) {
            return null;
        }
        return ctx.config.sel.firstDownloadResultEntry || ctx.config.sel.resultEntry || null;
    }

    function findFirstDownloadResultEntry() {
        var sel = firstDownloadResultEntrySelector();
        if (!sel) {
            return null;
        }
        try {
            return document.querySelector(sel);
        } catch (e) {
            return null;
        }
    }

    function waitForFirstDownloadResultEntry(done) {
        var immediate = findFirstDownloadResultEntry();
        if (immediate || !firstDownloadResultEntrySelector()) {
            done(immediate);
            return;
        }
        var settled = false;
        var settle = function (el) {
            if (settled) {
                return;
            }
            settled = true;
            done(el || findFirstDownloadResultEntry());
        };
        if (global.PixivNav && typeof global.PixivNav.ready === 'function') {
            try {
                global.PixivNav.ready().then(function () {
                    settle(findFirstDownloadResultEntry());
                }, function () {
                    settle(findFirstDownloadResultEntry());
                });
                global.setTimeout(function () {
                    settle(findFirstDownloadResultEntry());
                }, 3000);
                return;
            } catch (e) {
                // 回退到下方短轮询
            }
        }
        waitForElement(findFirstDownloadResultEntry, settle, function () {
            settle(null);
        }, 3000);
    }

    function monitorSucceeded() {
        waitForFirstDownloadResultEntry(function (entry) {
            if (entry) {
                renderFirstDownloadResultEntryPrompt();
            } else {
                renderDownloadCompletedWithoutResultEntry();
            }
        });
    }

    function renderFirstDownloadResultEntryPrompt() {
        var resultEntry = firstDownloadResultEntrySelector();
        patchState({phase: 'await-gallery', downloaded: true});
        overlay.render({
            targetSelector: resultEntry,
            interactiveSelector: resultEntry,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.success.title', '🎉 下载成功！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.monitor.success.body',
                    '第一份作品已下载到本地。点击高亮的「画廊」入口，去看看你的成果吧。')) + '</p></div>'
                + footHtml([SKIP_BTN()])
        });
        bindFoot({skip: skip});
        // 画廊在新标签打开，由那边的 boot 续跑；这里点击后保持提示即可
    }

    function renderDownloadCompletedWithoutResultEntry() {
        patchState({downloaded: true});
        markCompleted();
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.success.title', '🎉 下载成功！')) + '</h3>'
                + '<div class="po-pop-body"><p>' + escapeHtml(t('onboarding.monitor.success.no-result.body',
                    '第一份作品已下载到本地。当前没有可打开的下载结果入口，指引已完成。')) + '</p></div>'
                + footHtml([{act: 'done', label: t('onboarding.done.close', '开始使用'), variant: 'primary'}])
        });
        bindFoot({done: finish});
    }

    function monitorFailed(message) {
        overlay.render({
            centered: true,
            html:
                '<h3 class="po-pop-title">' + escapeHtml(t('onboarding.monitor.fail.title', '下载未成功')) + '</h3>'
                + '<div class="po-pop-body">'
                + '<p>' + escapeHtml(t('onboarding.monitor.fail.body',
                    '示例作品下载失败了。请检查 Cookie 与网络 / 代理后重试。')) + '</p>'
                + (message ? '<div class="po-hint po-hint-error">' + escapeHtml(message) + '</div>' : '')
                + '</div>'
                + footHtml([
                    SKIP_BTN(),
                    {act: 'retry', label: t('onboarding.monitor.fail.retry', '重新下载'), variant: 'primary'}
                ])
        });
        // 重试只回到「粘贴示例链接」，无需重走认识下载页的几步
        bindFoot({skip: skip, retry: stepPasteUrl});
    }

    // ════════════════════════════════════════════════════════════════════════════
    //  画廊阶段
    // ════════════════════════════════════════════════════════════════════════════

    ctx.download = {
        phaseWelcome: phaseWelcome,
        phaseDownload: phaseDownload,
        monitorSucceeded: monitorSucceeded
    };
})(window);
