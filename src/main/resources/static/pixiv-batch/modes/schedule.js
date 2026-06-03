'use strict';
    // ── 计划任务（管理员专用） ────────────────────────────────────────────────────
    // 方案：删除独立的「创建表单」，改为在 User / Search / 系列模式的工作区底部用
    // 「存为计划任务」卡片，直接快照当前模式来源 + 上方全部下载 / 筛选设置；第 5 个
    // Tab 仅做任务列表与管理（运行 / 授权 / 启停 / 编辑 / 删除）。
    let scheduleEditingId = null;
    // 编辑「快捷获取来源」类任务（收藏 / 关注新作 / 珍藏集）时锁定的来源 {type, source, kind, label}：
    // 这类任务无专属模式标签页，编辑时来源只读（换来源请删除重建），保存快照取此值而非当前 quick 视图。
    let scheduleEditingQuickSource = null;
    let scheduleTasksCache = [];
    // 计划任务列表轮询：进入第 5 Tab 时定时刷新，让「正在运行 / 排队中」等瞬时状态灯能实时更新。
    let schedulePollTimer = null;
    const SCHEDULE_POLL_MS = 4000;
    // 当前展开了「本轮队列详情」的任务 id 集合：列表重渲染会重建 DOM，据此恢复展开态。
    // 未展开的任务不请求 / 不渲染队列；展开后从本地缓存即时渲染，再按需向后端拉取最新队列。
    const scheduleExpandedQueues = new Set();
    // 上一次「整列空/非空 + 横幅」与「按卡片」的渲染签名。整列 innerHTML 重建会销毁展开的队列 DOM、
    // 中断 SSE 平滑刷新、把队列正文滚回顶部；改成按卡片 diff —— 仅在「该卡片的卡片级数据」真正变化时替换
    // 该卡片，并在替换前后保留其内部「队列正文/待重试面板」的 innerHTML / 滚动位置 / 展开折叠态。
    // 队列正文照常由 SSE / 快照单独更新。
    let scheduleBannerSignature = null;
    // 初始为 true：静态 HTML 的 #schedule-list 自带 .schedule-empty 占位符，首次加载到任务时需要先清空它再 diff。
    let scheduleEmptyStateRendered = true;
    const scheduleCardSignatures = new Map();
    // 上次成功 diff 渲染计划任务列表时的 UI 语言。卡片签名只看任务数据、不含语言，所以仅靠
    // signature diff 无法在语言切换后重渲染卡片；loadScheduleTasks 用这个变量比对，当语言变化时
    // 强制丢弃签名 / 已渲染态，让卡片走 replace 路径，再补刷展开的「本轮队列详情」与待重试面板。
    let scheduleLastRenderedLang = null;

    function startSchedulePolling() {
        stopSchedulePolling();
        schedulePollTimer = setInterval(() => {
            if (state.mode === 'schedule' && document.visibilityState !== 'hidden') {
                loadScheduleTasks();
            }
        }, SCHEDULE_POLL_MS);
    }

    function stopSchedulePolling() {
        if (schedulePollTimer) {
            clearInterval(schedulePollTimer);
            schedulePollTimer = null;
        }
        // 离开计划任务 Tab：解绑全部队列 SSE 监听；若工作区也未在下载且无其它监听，顺手关掉聚合连接。
        unsubscribeAllScheduleQueueSse();
        if (!state.isRunning && state.sharedSse && Object.keys(state.sseListeners).length === 0) {
            closeSharedSSE();
        }
    }

    // 哪些模式可以创建计划任务（单作品导入无对应来源类型）
    const SCHEDULE_MODE_TYPE = {user: 'USER_NEW', search: 'SEARCH', series: 'SERIES'};
    // 编辑回灌时的目标模式：USER_NEW/SEARCH/SERIES 有专属标签页；收藏 / 关注新作 / 珍藏集这三类
    // 无专属标签页，回到「快捷获取」并锁定来源（scheduleEditingQuickSource）。
    const SCHEDULE_TYPE_MODE = {
        USER_NEW: 'user', SEARCH: 'search', SERIES: 'series',
        MY_BOOKMARKS: QUICK_FETCH_MODE, FOLLOW_LATEST: QUICK_FETCH_MODE, COLLECTION: QUICK_FETCH_MODE
    };

    function onScheduleTriggerChange() {
        const trigger = (document.getElementById('sch-trigger') || {}).value || 'interval';
        document.querySelectorAll('#save-as-schedule-card .sch-trigger-field').forEach(el => {
            el.style.display = el.classList.contains('sch-trigger-' + trigger) ? '' : 'none';
        });
    }

    // 「首次抓取上限」对某来源的封顶语义：
    //   'watermark' = 首轮封顶（USER_NEW / FOLLOW_LATEST / date_d 翻页到底 SEARCH，有 ID 水位线，首轮抓最新 N 个后只追新）；
    //   'per-run'   = 每轮上限（MY_BOOKMARKS / COLLECTION / 非 date_d 翻页到底 SEARCH，无水位线，每轮各抓 N 个新作抽干积压）；
    //   null        = 不支持（SERIES / 固定页 SEARCH，前端隐藏该字段）。
    function scheduleFetchLimitMode(type, source) {
        if (type === 'USER_NEW' || type === 'FOLLOW_LATEST') return 'watermark';
        if (type === 'MY_BOOKMARKS' || type === 'COLLECTION') return 'per-run';
        if (type === 'SEARCH' && source && source.maxPages === -1) {
            return (source.order || 'date_d') === 'date_d' ? 'watermark' : 'per-run';
        }
        return null; // SERIES / 固定页 SEARCH
    }

    function scheduleTypeSupportsFetchLimit(type, source) {
        return scheduleFetchLimitMode(type, source) !== null;
    }

    // Search 模式当前 UI 折算出的 maxPages（与 buildScheduleSnapshot 同口径）：
    // 🔍 搜索 = 1；📦 批量 = 结束页输入（管理员 -1 = 翻页到底哨兵）。
    function currentScheduleSearchMaxPages() {
        const batchSubmode = searchState.submode === 'batch'
            || ((document.querySelector('input[name="search-submode"]:checked') || {}).value === 'batch');
        if (!batchSubmode) return 1;
        const raw = parseInt((document.getElementById('batch-end-page') || {}).value, 10);
        return (isAdmin && raw === -1) ? -1 : Math.max(1, Number.isFinite(raw) ? raw : 3);
    }

    function currentScheduleSearchOrder() {
        return (document.querySelector('input[name="search-order"]:checked') || {}).value || 'date_d';
    }

    // 当前模式 / 来源解析出的「将要保存的任务类型 + 来源」，用于决定首次抓取上限字段显隐与提示文案。
    function currentScheduleLimitContext() {
        if (state.mode === QUICK_FETCH_MODE) {
            const qs = scheduleEditingQuickSource || quickScheduleSource();
            return qs ? {type: qs.type, source: qs.source || {}} : null;
        }
        const type = SCHEDULE_MODE_TYPE[state.mode];
        if (!type) return null;
        if (type === 'SEARCH') {
            return {type, source: {maxPages: currentScheduleSearchMaxPages(), order: currentScheduleSearchOrder()}};
        }
        return {type, source: {}};
    }

    // 首次抓取上限字段显隐 + 提示文案按情况切换：仅显示与当前来源封顶语义匹配的那一条提示，不全部堆出来。
    function updateScheduleFetchLimitVisibility() {
        const row = document.getElementById('sch-fetch-limit-row');
        if (!row) return;
        const card = document.getElementById('save-as-schedule-card');
        const cardHidden = !card || card.style.display === 'none';
        const ctx = cardHidden ? null : currentScheduleLimitContext();
        const mode = ctx ? scheduleFetchLimitMode(ctx.type, ctx.source) : null;
        row.style.display = mode ? '' : 'none';
        const wm = document.getElementById('sch-fetch-limit-hint-watermark');
        const pr = document.getElementById('sch-fetch-limit-hint-per-run');
        if (wm) wm.style.display = mode === 'watermark' ? '' : 'none';
        if (pr) pr.style.display = mode === 'per-run' ? '' : 'none';
    }

    // 「存为计划任务」卡片显隐：非快捷模式沿用「管理员 + 可创建模式」；快捷获取下对管理员**常驻**，
    // 但仅当能解析出来源（已展开的收藏/我的作品/关注新作，或点进的画师/珍藏集；编辑时为锁定来源）才启用「创建」。
    function updateSaveScheduleCardVisibility() {
        const card = document.getElementById('save-as-schedule-card');
        if (!card) return;
        const inQuick = state.mode === QUICK_FETCH_MODE;
        // 在快捷获取里若残留「非快捷来源」的编辑态（编辑从 user/search/series 发起后切到了快捷获取），先退出编辑。
        if (inQuick && scheduleEditingId != null && !scheduleEditingQuickSource) resetScheduleForm();
        const submit = document.getElementById('sch-submit');
        if (inQuick) {
            card.style.display = isAdmin ? '' : 'none';
            const quickSrc = scheduleEditingQuickSource || quickScheduleSource();
            updateScheduleQuickSourceNote(quickSrc);
            if (submit) submit.disabled = isAdmin && !quickSrc;
            updateScheduleFetchLimitVisibility();
            return;
        }
        const eligible = isAdmin && !!SCHEDULE_MODE_TYPE[state.mode];
        card.style.display = eligible ? '' : 'none';
        updateScheduleQuickSourceNote(null);
        if (submit) submit.disabled = false;
        if (!eligible && scheduleEditingId != null) resetScheduleForm();
        updateScheduleFetchLimitVisibility();
    }

    // 快捷获取下「存为计划任务」卡片顶部的来源说明 / 提示：
    // 有来源 → 说明将创建的任务类型与来源（编辑态额外标注只读）；无来源 → 提示先展开具体作品列表。
    function updateScheduleQuickSourceNote(quickSrc) {
        const el = document.getElementById('sch-quick-source');
        if (!el) return;
        if (state.mode !== QUICK_FETCH_MODE || !isAdmin) {
            el.textContent = '';
            el.style.display = 'none';
            return;
        }
        if (!quickSrc) {
            el.textContent = bt('schedule.save.quick-hint',
                '请先展开具体的作品列表（点开收藏 / 我的作品 / 关注新作，或点进某个画师 / 珍藏集）后再创建计划任务');
            el.style.display = '';
            return;
        }
        const typeLabel = scheduleTypeLabel(quickSrc.type);
        el.textContent = scheduleEditingQuickSource
            ? bt('schedule.save.quick-source-editing', '编辑中（来源只读，换来源请删除重建）：{type} · {label}',
                {type: typeLabel, label: quickSrc.label})
            : bt('schedule.save.quick-source', '将创建计划任务：{type} · {label}',
                {type: typeLabel, label: quickSrc.label});
        el.style.display = '';
    }

    function setScheduleFormStatus(msg, type = 'info') {
        const el = document.getElementById('sch-form-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 账号级 / 横幅级操作反馈（如过度访问账号恢复），与「存为计划任务」卡片的表单状态分开
    function setScheduleListStatus(msg, type = 'info') {
        const el = document.getElementById('schedule-list-status');
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 单个任务卡片内的操作反馈：显示在该卡片顶部的 tips 区域，互不干扰
    function setScheduleCardTip(id, msg, type = 'info') {
        const el = document.getElementById(`schedule-card-tip-${id}`);
        if (!el) return;
        el.textContent = msg || '';
        el.style.color = STATUS_COLORS[type] || '#666';
    }

    // 按当前模式来源 + 上方全部下载 / 筛选设置，快照成 params v2。
    // 附加筛选（内容分级 / AI / 标签 / 收藏 / 页数 / 字数 / 类型）现为 User / Search / Series 共享的卡片，
    // 三种模式均携带；内容分级在 Search 还会派生出 Pixiv 查询的 source.mode。
    function buildScheduleSnapshot() {
        const mode = state.mode;
        let type = SCHEDULE_MODE_TYPE[mode];
        let kind, source;
        if (mode === QUICK_FETCH_MODE) {
            // 快捷获取：编辑这类无专属标签页的任务时来源已锁定（scheduleEditingQuickSource）；
            // 新建时按当前已展开的来源解析（收藏 / 我的作品 / 关注新作，或点进的画师 / 珍藏集）。
            const qs = scheduleEditingQuickSource || quickScheduleSource();
            if (!qs || !qs.type) {
                throw new Error(bt('schedule.error.quick-source',
                    '请先展开具体的作品列表（点开收藏 / 我的作品 / 关注新作，或点进某个画师 / 珍藏集）后再存为计划任务'));
            }
            type = qs.type;
            // COLLECTION 为插画+小说混合，kind 记 "mixed"，后端按 type 特判分别下两类。
            kind = qs.kind === 'novel' ? 'novel' : (qs.kind === 'mixed' ? 'mixed' : 'illust');
            source = qs.source || {};
        } else if (!type) {
            throw new Error(bt('schedule.error.mode', '当前模式不支持创建计划任务'));
        } else if (mode === 'user') {
            kind = state.settings.userKind === 'novel' ? 'novel' : 'illust';
            const userId = parseUserIdInput(document.getElementById('user-id-input').value);
            if (!userId) throw new Error(bt('schedule.error.user-id', '请填写有效的画师 ID 或画师主页链接'));
            source = {userId};
        } else if (mode === 'search') {
            kind = state.settings.searchKind === 'novel' ? 'novel' : 'illust';
            const word = (document.getElementById('search-word').value || '').trim();
            if (!word) throw new Error(bt('schedule.error.word', '请填写搜索关键词'));
            const uiMode = (document.getElementById('search-content-filter') || {}).value || 'all';
            const sMode = (document.querySelector('input[name="search-smode"]:checked') || {}).value || 's_tag';
            const order = (document.querySelector('input[name="search-order"]:checked') || {}).value || 'date_d';
            const pixivMode = (uiMode === 'r18' || uiMode === 'r18g' || uiMode === 'r18plus') ? 'r18' : uiMode;
            // 子模式语义：🔍 搜索模式 = 只取第一页（maxPages 恒为 1）；📦 作品批量获取模式 = 读结束页输入框。
            // 结束页 = -1 是「直到已下载作品为止」哨兵，仅管理员可用（计划任务本就 admin-only）。
            source = {word, order, mode: pixivMode, sMode, maxPages: currentScheduleSearchMaxPages()};
        } else {
            kind = seriesState.kind === 'novel' ? 'novel' : 'illust';
            if (!seriesState.seriesId) throw new Error(bt('schedule.error.series-id', '请先在上方解析并预览系列'));
            source = {seriesId: String(seriesState.seriesId)};
        }
        syncSettings();
        const f = getSearchFiltersFromUI();
        const filters = {
            content: f.content,
            aiFilter: f.ai,
            tagsExact: f.tagsExact,
            tagsFuzzy: f.tagsFuzzy,
            typeFilter: f.type,
            pagesMin: f.pageMin,
            pagesMax: f.pageMax,
            wordsMin: f.wordsMin,
            wordsMax: f.wordsMax,
            bookmarksMin: f.bookmarkMin,
            bookmarksMax: f.bookmarkMax
        };
        const download = {
            fileNameTemplate: state.settings.fileNameTemplate,
            bookmark: !!state.settings.bookmark,
            collectionId: state.settings.collectionId,
            // 队列调度项也纳入快照（统一存毫秒整数，避免 s/ms 单位歧义）：
            concurrent: Math.max(1, parseInt(state.settings.concurrent, 10) || 1),
            intervalMs: getIntervalMs(),
            imageDelayMs: getImageDelayMs(),
            verifyFiles: !!state.settings.verifyHistoryFiles,
            novelFormat: state.settings.novelFormat || 'txt',
            novelMerge: !!state.settings.mergeNovelSeries,
            novelMergeFormat: state.settings.mergeNovelFormat || 'epub'
        };
        // 首次抓取上限：仅对支持封顶的来源类型携带（0 = 全量 / 不限；后端按来源是否水位线决定「首轮封顶」或「每轮上限」）。
        let fetchLimit = 0;
        if (scheduleTypeSupportsFetchLimit(type, source)) {
            const rawLimit = parseInt((document.getElementById('sch-fetch-limit') || {}).value, 10);
            fetchLimit = Number.isFinite(rawLimit) && rawLimit > 0 ? rawLimit : 0;
        }
        return {type, kind, params: {kind, source, filters, download, fetchLimit}};
    }

    async function submitScheduleTask() {
        const name = (document.getElementById('sch-name').value || '').trim();
        if (!name) {
            setScheduleFormStatus(bt('schedule.error.name', '请填写任务名称'), 'error');
            return;
        }
        let snap;
        try {
            snap = buildScheduleSnapshot();
        } catch (e) {
            setScheduleFormStatus(e.message, 'error');
            return;
        }
        // N=0（全量）风险确认：仅对支持封顶的来源类型提示——首轮全量可能触发 Pixiv 过度访问警告甚至封号。
        if (scheduleTypeSupportsFetchLimit(snap.type, snap.params.source)
            && !(snap.params.fetchLimit > 0)
            && !uiConfirmKey('schedule.confirm.full-fetch',
                '「首次抓取上限」为 0 表示首次运行会尝试抓取该来源的全部历史作品。作品很多时可能触发 Pixiv 的过度访问警告甚至封号风险。确定要全量抓取吗？')) {
            return;
        }
        const triggerKind = document.getElementById('sch-trigger').value;
        const body = {name, type: snap.type, paramsJson: JSON.stringify(snap.params), triggerKind};
        if (triggerKind === 'interval') {
            body.intervalMinutes = parseInt(document.getElementById('sch-interval').value, 10) || 0;
        } else {
            body.cronExpr = (document.getElementById('sch-cron').value || '').trim();
        }
        const editing = scheduleEditingId != null;
        const url = editing ? `${BASE}/api/schedule/tasks/${scheduleEditingId}` : `${BASE}/api/schedule/tasks`;
        try {
            const res = await fetch(url, {
                method: editing ? 'PUT' : 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                // 后端错误体是 {"error": "..."}（ErrorResponse）：Cron 表达式无效等具体原因都在 error 字段，
                // 读对字段才能把「Cron 表达式无效」这类原因透给用户，而不是一律退回泛化的「保存失败」。
                setScheduleFormStatus(err.error || err.message || bt('schedule.error.save', '保存失败'), 'error');
                return;
            }
            // solo 模式下新建任务时，用当前输入框里的 Cookie 自动授权绑定，省去手动「授权 Cookie」一步。
            let autoAuthResult = null;
            if (!editing && appMode === 'solo') {
                const created = await res.json().catch(() => null);
                if (created && created.id != null) {
                    autoAuthResult = await autoAuthorizeScheduleCookie(created.id);
                }
            }
            // 先重置表单（resetScheduleForm 末尾会清空状态），再写入成功提示，
            // 否则成功提示会被 resetScheduleForm 的 setScheduleFormStatus('') 立刻清掉。
            resetScheduleForm();
            if (autoAuthResult === 'authorized') {
                setScheduleFormStatus(bt('schedule.status.saved-authorized', '已保存并自动授权 Cookie'), 'success');
            } else if (autoAuthResult === 'no-cookie') {
                setScheduleFormStatus(bt('schedule.status.saved-no-cookie', '已保存；当前无含 PHPSESSID 的 Cookie，任务将以受限模式运行，请在列表中「授权 Cookie」'), 'success');
            } else {
                setScheduleFormStatus(bt('schedule.status.saved', '已保存'), 'success');
            }
            loadScheduleTasks();
        } catch (e) {
            setScheduleFormStatus(bt('schedule.error.save', '保存失败'), 'error');
        }
    }

    function resetScheduleForm() {
        scheduleEditingId = null;
        scheduleEditingQuickSource = null;
        const nameEl = document.getElementById('sch-name');
        if (nameEl) nameEl.value = '';
        const cronEl = document.getElementById('sch-cron');
        if (cronEl) cronEl.value = '';
        const intEl = document.getElementById('sch-interval');
        if (intEl) intEl.value = '1440';
        const flEl = document.getElementById('sch-fetch-limit');
        if (flEl) flEl.value = '0';
        const trgEl = document.getElementById('sch-trigger');
        if (trgEl) trgEl.value = 'interval';
        const subEl = document.getElementById('sch-submit');
        if (subEl) subEl.textContent = bt('schedule.action.create', '➕ 创建任务');
        const canEl = document.getElementById('sch-cancel');
        if (canEl) canEl.style.display = 'none';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = 'none';
            srcEl.textContent = '';
        }
        onScheduleTriggerChange();
        setScheduleFormStatus('');
        // 退出编辑后刷新卡片显隐 / 来源提示 / 创建按钮禁用态（scheduleEditingId 已置空，不会重入本函数）。
        updateSaveScheduleCardVisibility();
    }

    function setScheduleRadio(name, value) {
        if (value == null) return;
        const el = document.querySelector(`input[name="${name}"][value="${value}"]`);
        if (el) el.checked = true;
    }

    function applyScheduleKind(modePrefix, kind) {
        const k = kind === 'novel' ? 'novel' : 'illust';
        if (modePrefix === 'user') state.settings.userKind = k;
        else state.settings.searchKind = k;
        const radio = document.querySelector(`input[name="${modePrefix}-kind"][value="${k}"]`);
        if (radio) radio.checked = true;
        applyKindSwitcherUI(`${modePrefix}-kind-switcher`, k);
    }

    // 编辑：切到对应模式，把快照回灌进该模式来源 + 共享下载 / 筛选设置，再进入编辑态。
    function startEditScheduleTask(id) {
        const task = scheduleTasksCache.find(t => t.id === id);
        if (!task) return;
        // 进入编辑态时清掉上一次创建 / 保存留下的成功或失败提示。
        setScheduleFormStatus('');
        let params = {};
        try { params = JSON.parse(task.paramsJson || '{}'); } catch (e) { params = {}; }
        const kind = scheduleKindFromParams(params) || 'illust'; // 'illust' | 'novel' | 'mixed'
        const source = params.source || {};
        const filters = params.filters || {};
        const download = params.download || {};
        const targetMode = SCHEDULE_TYPE_MODE[task.type] || 'user';

        // 默认清掉「快捷来源锁定」；仅 quick-fetch 类型（收藏/关注新作/珍藏集）在下方重新设置。
        scheduleEditingQuickSource = null;
        switchMode(targetMode);

        // 1) 来源 + kind
        if (task.type === 'USER_NEW') {
            applyScheduleKind('user', kind);
            const u = document.getElementById('user-id-input');
            if (u) u.value = source.userId || '';
        } else if (task.type === 'SEARCH') {
            applyScheduleKind('search', kind);
            const w = document.getElementById('search-word');
            if (w) w.value = source.word || '';
            setScheduleRadio('search-order', source.order || 'date_d');
            setScheduleRadio('search-smode', source.sMode || 's_tag');
            // 内容分级回灌走下方 setSearchFiltersUI(filters.content)；source.mode 仅是据其派生的 Pixiv 查询档位。
            // maxPages=1 ↔ 🔍 搜索模式（只取第一页）；-1 或 >=2 ↔ 📦 作品批量获取模式（回填结束页，-1 为哨兵）
            const mp = source.maxPages;
            const batchSubmode = mp === -1 || (typeof mp === 'number' && mp >= 2);
            applySubmodeUI(batchSubmode ? 'batch' : 'search', {clear: false});
            const endP = document.getElementById('batch-end-page');
            if (endP && batchSubmode) endP.value = mp;
        } else if (task.type === 'SERIES') {
            // 回填系列 URL 并自动解析预览（loadSeriesPreview 会同步 seriesState.kind / seriesId）
            const sid = source.seriesId || '';
            const urlInput = document.getElementById('series-input-url');
            if (urlInput) {
                urlInput.value = sid
                    ? (kind === 'novel' ? `https://www.pixiv.net/novel/series/${sid}` : String(sid))
                    : '';
            }
            seriesState.kind = kind;
            seriesState.seriesId = sid ? Number(sid) : null;
            if (sid) loadSeriesPreview();
        } else if (targetMode === QUICK_FETCH_MODE) {
            // 收藏 / 关注新作 / 珍藏集：无专属模式标签页，回到快捷获取并锁定来源（只读，换来源请删除重建）。
            scheduleEditingQuickSource = {
                type: task.type, source, kind,
                label: scheduleQuickSourceLabel(task.type, source, kind)
            };
        }

        // 2) 共享下载设置 + 筛选回灌（附加筛选现为三种模式共享，统一回灌）
        applyScheduleDownloadUI(download);
        setSearchFiltersUI(normalizeSearchFilters({
            content: filters.content, ai: filters.aiFilter, type: filters.typeFilter,
            pageMin: filters.pagesMin, pageMax: filters.pagesMax,
            bookmarkMin: filters.bookmarksMin, bookmarkMax: filters.bookmarksMax,
            wordsMin: filters.wordsMin, wordsMax: filters.wordsMax,
            tagsExact: filters.tagsExact, tagsFuzzy: filters.tagsFuzzy
        }));
        syncSettings();
        applyNovelSettingsVisibility();
        updateExtraFiltersCardVisibility();

        // 3) 触发 + 名称，进入编辑态
        scheduleEditingId = task.id;
        document.getElementById('sch-name').value = task.name || '';
        document.getElementById('sch-trigger').value = task.triggerKind || 'interval';
        document.getElementById('sch-interval').value = task.intervalMinutes || 1440;
        document.getElementById('sch-cron').value = task.cronExpr || '';
        const flEl = document.getElementById('sch-fetch-limit');
        if (flEl) flEl.value = (Number.isFinite(params.fetchLimit) && params.fetchLimit > 0) ? params.fetchLimit : 0;
        document.getElementById('sch-submit').textContent = bt('schedule.action.save', '💾 保存修改');
        document.getElementById('sch-cancel').style.display = '';
        const srcEl = document.getElementById('sch-edit-source');
        if (srcEl) {
            srcEl.style.display = '';
            srcEl.textContent = bt('schedule.save.editing', '正在编辑：{name}', {name: task.name || ''});
        }
        onScheduleTriggerChange();
        updateSaveScheduleCardVisibility();
        const card = document.getElementById('save-as-schedule-card');
        if (card) card.scrollIntoView({behavior: 'smooth', block: 'center'});
    }

    // 把快照的下载设置写回共享控件（不含内容分级等附加筛选，它们在 filters 段）
    function applyScheduleDownloadUI(d) {
        if (!d) return;
        if (typeof d.fileNameTemplate === 'string' && d.fileNameTemplate) {
            document.getElementById('s-file-name-template').value = d.fileNameTemplate;
        }
        const bm = document.getElementById('s-bookmark');
        if (bm) bm.checked = !!d.bookmark;
        const col = document.getElementById('s-collection');
        if (col) col.value = d.collectionId != null ? String(d.collectionId) : '';
        const fmt = document.getElementById('s-novel-format');
        if (fmt && d.novelFormat) fmt.value = d.novelFormat;
        const mg = document.getElementById('s-novel-merge');
        if (mg) mg.checked = !!d.novelMerge;
        const mgf = document.getElementById('s-novel-merge-format');
        if (mgf && d.novelMergeFormat) mgf.value = d.novelMergeFormat;
        // 队列调度项回灌：最大并发数 / 作品间隔 / 图片间隔（快照存毫秒，统一以 ms 单位回填并把单位按钮切到 ms）/ 实际目录检测。
        const conc = document.getElementById('s-concurrent');
        if (conc && Number.isFinite(d.concurrent) && d.concurrent >= 1) {
            conc.value = d.concurrent;
            state.settings.concurrent = d.concurrent;
        }
        if (Number.isFinite(d.intervalMs) && d.intervalMs >= 0) {
            const iv = document.getElementById('s-interval');
            const ivUnit = document.getElementById('s-interval-unit');
            const ms = Math.round(d.intervalMs);
            if (iv) iv.value = ms;
            if (ivUnit) ivUnit.textContent = 'ms';
            state.settings.intervalUnit = 'ms';
            state.settings.interval = ms;
        }
        if (Number.isFinite(d.imageDelayMs) && d.imageDelayMs >= 0) {
            const im = document.getElementById('s-image-delay');
            const imUnit = document.getElementById('s-image-delay-unit');
            const ms = Math.round(d.imageDelayMs);
            if (im) im.value = ms;
            if (imUnit) imUnit.textContent = 'ms';
            state.settings.imageDelayUnit = 'ms';
            state.settings.imageDelay = ms;
        }
        const vf = document.getElementById('s-verify-files');
        if (vf) {
            vf.checked = !!d.verifyFiles;
            state.settings.verifyHistoryFiles = !!d.verifyFiles;
        }
        updateMergeFormatVisibility();
    }

    function scheduleStatusLabel(code) {
        if (!code) return bt('schedule.run-status.none', '尚未运行');
        if (code === 'OK') return bt('schedule.run-status.ok', '正常');
        if (code === 'AUTH_EXPIRED') return bt('schedule.run-status.auth-expired', '登录态失效，请重新授权 Cookie');
        if (code === 'ERROR') return bt('schedule.run-status.error', '运行出错');
        if (code === 'PAUSED') return bt('schedule.run-status.paused', '已手动暂停');
        if (code === 'OVERUSE_PAUSED') return bt('schedule.run-status.overuse-paused', '已暂停：检测到过度访问警告');
        return code;
    }

    /**
     * 计算任务卡片右上角「状态灯」：返回 {tone, text}。
     * tone ∈ green / yellow / red / gray，决定灯色；text 为本地化的状态说明。
     * 优先级：瞬时运行态（运行中 / 排队中）> 已停用 > 上一轮持久化结果（cookie 失效 / 失败 / 成功）> 首次未运行。
     */
    function scheduleStatusLight(t) {
        if (t.runState === 'RUNNING') {
            return {tone: 'green', live: true, text: bt('schedule.light.running', '正在运行')};
        }
        if (t.runState === 'QUEUED') {
            return {tone: 'yellow', live: true, text: bt('schedule.light.queued', '排队中')};
        }
        if (!t.enabled) {
            return {tone: 'gray', live: false, text: bt('schedule.light.disabled', '已停用，不会自动运行')};
        }
        // 挂起态优先于「中断」哨兵：挂起任务不会被自动重排，若残留 runStartedTime（暂停/挂起途中被强杀）
        // 仍显示中断红灯「已重新排期补齐」会与事实矛盾，故先判挂起态。
        if (t.lastStatus === 'OVERUSE_PAUSED') {
            return {tone: 'red', live: false, text: bt('schedule.light.overuse-paused', '已暂停：检测到过度访问警告（账号级）')};
        }
        if (t.lastStatus === 'PAUSED') {
            return {tone: 'gray', live: false, text: bt('schedule.light.paused', '已手动暂停')};
        }
        if (t.lastStatus === 'AUTH_EXPIRED') {
            return {tone: 'red', live: false, text: bt('schedule.light.auth-expired', '运行失败，Cookie 失效，请重新授权有效 Cookie')};
        }
        if (t.runStartedTime != null) {
            // 残留的开始时刻 = 上次运行未走到结果落库（进程被强杀中断）；重跑会刷新并最终补齐后清除。
            return {tone: 'red', live: false, text: bt('schedule.light.interrupted', '运行失败，上次运行被中断，已重新排期补齐')};
        }
        if (t.lastStatus === 'ERROR') {
            const reason = (t.lastMessage || '').trim();
            return {
                tone: 'red',
                live: false,
                text: reason
                    ? bt('schedule.light.error-reason', '运行失败，因为：{reason}', {reason})
                    : bt('schedule.light.error', '运行失败')
            };
        }
        if (t.lastStatus === 'OK') {
            return {tone: 'green', live: false, text: bt('schedule.light.ok', '运行成功，等待下次运行')};
        }
        // last_status 为空：恢复 / 账号级恢复后清空了上轮结果。已运行过则显示「等待下次运行」，
        // 从未运行过才是「等待首次运行」，避免恢复一个跑过多次的任务后误显示成首次。
        if (t.lastRunTime != null) {
            return {tone: 'gray', live: false, text: bt('schedule.light.idle', '等待下次运行')};
        }
        return {tone: 'gray', live: false, text: bt('schedule.light.never', '等待首次运行')};
    }

    function fmtScheduleTime(ms) {
        if (!ms) return '—';
        try { return new Date(ms).toLocaleString(); } catch (e) { return '—'; }
    }

    function parseScheduleParams(task) {
        try {
            const parsed = JSON.parse((task || {}).paramsJson || '{}');
            return parsed && typeof parsed === 'object' ? parsed : {};
        } catch (e) {
            return null;
        }
    }

    function scheduleTypeLabel(type) {
        return {
            USER_NEW: bt('mode.user', '👤 User 模式'),
            SEARCH: bt('mode.search', '🔍 Search 模式'),
            SERIES: bt('mode.series', '📚 系列下载'),
            MY_BOOKMARKS: bt('schedule.type.my-bookmarks', '⭐ 我的收藏'),
            FOLLOW_LATEST: bt('schedule.type.follow-latest', '📰 已关注用户的新作'),
            COLLECTION: bt('schedule.type.collection', '🗂 珍藏集')
        }[type] || type || bt('schedule.snapshot.value.unknown', '未知');
    }

    function scheduleKindFromParams(params) {
        if (!params || !params.kind) return null;
        if (params.kind === 'novel') return 'novel';
        if (params.kind === 'mixed') return 'mixed';
        return 'illust';
    }

    function scheduleKindLabel(kind) {
        if (kind === 'novel') return bt('schedule.kind.novel', '小说');
        if (kind === 'illust') return bt('schedule.kind.illust', '插画');
        if (kind === 'mixed') return bt('schedule.kind.mixed', '插画+小说');
        return bt('schedule.snapshot.value.unknown', '未知');
    }

    // 由快照来源（编辑 quick-fetch 类任务时）派生只读来源说明文案。
    function scheduleQuickSourceLabel(type, source, kind) {
        if (type === 'MY_BOOKMARKS') {
            const kindLabel = kind === 'novel' ? bt('schedule.kind.novel', '小说') : bt('schedule.kind.illust', '插画');
            const restLabel = source.rest === 'hide'
                ? bt('quick.schedule.rest.hide', '不公开') : bt('quick.schedule.rest.show', '公开');
            return bt('quick.schedule.source.bookmarks', '我的收藏（{kind}，{rest}）',
                {kind: kindLabel, rest: restLabel});
        }
        if (type === 'FOLLOW_LATEST') {
            return bt('quick.title.following-new', '已关注的用户的新作');
        }
        if (type === 'COLLECTION') {
            return bt('quick.schedule.source.collection', '珍藏集 {name}（ID {id}）',
                {name: source.collectionId, id: source.collectionId});
        }
        return '';
    }

    function scheduleTriggerLabel(t) {
        const minutes = t.intervalMinutes || 0;
        return t.triggerKind === 'cron'
            ? `${bt('schedule.trigger.cron', 'Cron 表达式')} ${t.cronExpr || ''}`
            : `${bt('schedule.trigger.interval', '固定周期')} ${bt('schedule.time.minutes', '{count} 分钟', {count: minutes})}`;
    }

    function scheduleBoolLabel(value) {
        return value
            ? bt('schedule.snapshot.value.yes', '是')
            : bt('schedule.snapshot.value.no', '否');
    }

    function scheduleUnsetLabel() {
        return bt('schedule.snapshot.value.unset', '未设置');
    }

    function scheduleUnlimitedLabel() {
        return bt('schedule.snapshot.value.unlimited', '不限');
    }

    function scheduleValueOrUnset(value) {
        return value == null || value === '' ? scheduleUnsetLabel() : String(value);
    }

    function scheduleListValue(value) {
        const list = Array.isArray(value)
            ? value.map(v => String(v).trim()).filter(Boolean)
            : String(value || '').split(',').map(v => v.trim()).filter(Boolean);
        return list.length ? list.join(', ') : scheduleUnsetLabel();
    }

    function scheduleRangeValue(min, max) {
        const hasMin = min != null && min !== '';
        const hasMax = max != null && max !== '';
        if (hasMin && hasMax) {
            return bt('schedule.snapshot.value.range', '{min} - {max}', {min, max});
        }
        if (hasMin) {
            return bt('schedule.snapshot.value.at-least', '≥ {value}', {value: min});
        }
        if (hasMax) {
            return bt('schedule.snapshot.value.at-most', '≤ {value}', {value: max});
        }
        return scheduleUnlimitedLabel();
    }

    function scheduleContentLabel(value) {
        const labels = {
            all: bt('search.content.all', '全部'),
            safe: bt('search.content.safe', '全年龄'),
            r18plus: bt('search.content.r18plus', 'R18+(R-18 + R-18G)'),
            r18: bt('search.content.r18', 'R-18'),
            r18g: bt('search.content.r18g', 'R-18G')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleAiLabel(value) {
        const labels = {
            all: bt('search.filter.all', '全部'),
            exclude: bt('search.filter.exclude-ai', '排除 AI'),
            only: bt('search.filter.only-ai', '仅 AI')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleWorkTypeLabel(value) {
        const labels = {
            all: bt('search.filter.all', '全部'),
            illust: bt('search.type.illust', '插画'),
            manga: bt('search.type.manga', '漫画'),
            ugoira: bt('search.type.ugoira', '动图')
        };
        return labels[value || 'all'] || scheduleValueOrUnset(value);
    }

    function scheduleSearchModeLabel(value) {
        const labels = {
            s_tag: bt('search.mode.tag', '标签'),
            s_tc: bt('search.mode.title-desc', '标题/描述')
        };
        return labels[value || 's_tag'] || scheduleValueOrUnset(value);
    }

    function scheduleSearchOrderLabel(value) {
        const labels = {
            date_d: bt('search.order.latest', '最新'),
            date: bt('search.order.oldest', '最旧'),
            popular_d: bt('search.order.popular', '热门 ⚠')
        };
        return labels[value || 'date_d'] || scheduleValueOrUnset(value);
    }

    function scheduleNovelFormatLabel(value) {
        const labels = {
            txt: bt('novel:format.txt', '纯文本（TXT）'),
            html: bt('novel:format.html', '网页（HTML）'),
            epub: bt('novel:format.epub', '电子书（EPUB）')
        };
        return labels[value || 'txt'] || scheduleValueOrUnset(value);
    }

    function scheduleMaxPagesLabel(value) {
        if (Number(value) === -1) {
            return bt('schedule.snapshot.value.until-downloaded', '直到遇到已下载作品为止');
        }
        const parsed = Number(value);
        const count = Number.isFinite(parsed) && parsed > 0 ? parsed : 1;
        return bt('schedule.snapshot.value.pages-count', '{count} 页', {count});
    }

    function scheduleCollectionLabel(collectionId) {
        if (collectionId == null || collectionId === '') {
            return bt('option.collection.none', '（不加入收藏夹）');
        }
        const id = String(collectionId);
        const select = document.getElementById('s-collection');
        let optionText = '';
        if (select && select.options) {
            const opt = Array.from(select.options).find(o => String(o.value) === id);
            optionText = opt ? (opt.textContent || '').trim() : '';
        }
        const idText = bt('schedule.snapshot.value.id', 'ID');
        return optionText ? `${optionText} (${idText} ${id})` : `${idText} ${id}`;
    }

    function scheduleSnapshotRow(label, value) {
        return `<div class="schedule-snapshot-key">${escHtml(label)}</div>` +
            `<div class="schedule-snapshot-value">${escHtml(value)}</div>`;
    }

    function scheduleSnapshotSection(title, rows) {
        return `<section class="schedule-snapshot-section">` +
            `<div class="schedule-snapshot-section-title">${escHtml(title)}</div>` +
            `<div class="schedule-snapshot-grid">${rows.map(row => scheduleSnapshotRow(row[0], row[1])).join('')}</div>` +
            `</section>`;
    }

    function buildScheduleSnapshotSourceRows(type, source) {
        if (type === 'USER_NEW') {
            return [[bt('schedule.snapshot.field.user-id', '画师 ID'), scheduleValueOrUnset(source.userId)]];
        }
        if (type === 'SEARCH') {
            return [
                [bt('schedule.snapshot.field.keyword', '搜索关键词'), scheduleValueOrUnset(source.word)],
                [bt('schedule.snapshot.field.search-order', '排序'), scheduleSearchOrderLabel(source.order)],
                [bt('schedule.snapshot.field.search-mode', '搜索方式'), scheduleSearchModeLabel(source.sMode)],
                [bt('schedule.snapshot.field.pixiv-mode', 'Pixiv 内容范围'), scheduleContentLabel(source.mode)],
                [bt('schedule.snapshot.field.max-pages', '发现页数'), scheduleMaxPagesLabel(source.maxPages)]
            ];
        }
        if (type === 'SERIES') {
            return [[bt('schedule.snapshot.field.series-id', '系列 ID'), scheduleValueOrUnset(source.seriesId)]];
        }
        if (type === 'MY_BOOKMARKS') {
            const rest = source.rest === 'hide'
                ? bt('quick.schedule.rest.hide', '不公开') : bt('quick.schedule.rest.show', '公开');
            return [[bt('schedule.snapshot.field.bookmark-visibility', '收藏可见性'), rest]];
        }
        if (type === 'FOLLOW_LATEST') {
            return [[bt('schedule.snapshot.field.source', '来源'),
                bt('schedule.type.follow-latest', '📰 已关注用户的新作')]];
        }
        if (type === 'COLLECTION') {
            return [[bt('schedule.snapshot.field.collection-id', '珍藏集 ID'), scheduleValueOrUnset(source.collectionId)]];
        }
        return [[
            bt('schedule.snapshot.field.source-json', '来源快照'),
            JSON.stringify(source || {})
        ]];
    }

    function scheduleConcurrentValue(n) {
        return (typeof n === 'number' && n >= 1)
            ? bt('schedule.snapshot.value.concurrent', '{n} 路并发', {n})
            : bt('schedule.snapshot.value.unset', '未设置');
    }

    function scheduleFetchLimitValue(n) {
        return (typeof n === 'number' && n > 0)
            ? bt('schedule.snapshot.value.fetch-limit', '{n} 个作品', {n})
            : bt('schedule.snapshot.value.fetch-limit-all', '全量（不限）');
    }

    function scheduleMsValue(ms) {
        return (typeof ms === 'number' && ms >= 0)
            ? bt('schedule.snapshot.value.ms', '{ms} ms', {ms})
            : bt('schedule.snapshot.value.unset', '未设置');
    }

    function renderScheduleSnapshotBody(t) {
        const params = parseScheduleParams(t);
        const kind = scheduleKindFromParams(params);
        const basicRows = [
            [bt('schedule.snapshot.field.name', '任务名称'), scheduleValueOrUnset(t.name)],
            [bt('schedule.snapshot.field.type', '任务类型'), scheduleTypeLabel(t.type)],
            [bt('schedule.snapshot.field.kind', '作品类型'), scheduleKindLabel(kind)],
            [bt('schedule.snapshot.field.trigger', '触发方式'), scheduleTriggerLabel(t)],
            [bt('schedule.snapshot.field.cookie', 'Cookie 模式'), t.cookieBound ? bt('schedule.cookie.bound', '已绑定 Cookie') : bt('schedule.cookie.restricted', '受限模式（无 Cookie）')],
            [bt('schedule.snapshot.field.enabled', '启用状态'), t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用')],
            [bt('schedule.snapshot.field.next-run', '下次运行'), fmtScheduleTime(t.nextRunTime)],
            [bt('schedule.snapshot.field.last-run', '上次运行'), fmtScheduleTime(t.lastRunTime)],
            [bt('schedule.snapshot.field.last-status', '运行状态'), scheduleStatusLabel(t.lastStatus)]
        ];
        const basicSection = scheduleSnapshotSection(bt('schedule.snapshot.section.basic', '基本信息'), basicRows);
        if (!params) {
            return basicSection +
                `<div class="schedule-snapshot-empty">${escHtml(bt('schedule.snapshot.error.parse', '任务快照解析失败'))}</div>`;
        }
        const source = params.source || {};
        const filters = params.filters || {};
        const download = params.download || {};
        const sourceRows = buildScheduleSnapshotSourceRows(t.type, source);
        // 首次抓取上限：仅对支持封顶的来源类型展示（与表单显隐口径一致）。
        if (scheduleTypeSupportsFetchLimit(t.type, source)) {
            sourceRows.push([
                bt('schedule.snapshot.field.fetch-limit', '首次抓取上限'),
                scheduleFetchLimitValue(params.fetchLimit)
            ]);
        }
        const sourceSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.source', '来源快照'),
            sourceRows
        );
        const filterSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.filters', '筛选快照'),
            [
                [bt('label.search-content-rating', '内容分级'), scheduleContentLabel(filters.content)],
                [bt('label.search-ai', 'AI 作品'), scheduleAiLabel(filters.aiFilter)],
                [bt('label.search-tags-exact', '标签(精确匹配)'), scheduleListValue(filters.tagsExact)],
                [bt('label.search-tags-fuzzy', '标签(模糊匹配)'), scheduleListValue(filters.tagsFuzzy)],
                [bt('label.search-type', '作品类型'), scheduleWorkTypeLabel(filters.typeFilter)],
                [bt('schedule.snapshot.field.pages-range', '页数范围'), scheduleRangeValue(filters.pagesMin, filters.pagesMax)],
                [bt('schedule.snapshot.field.words-range', '字数范围'), scheduleRangeValue(filters.wordsMin, filters.wordsMax)],
                [bt('schedule.snapshot.field.bookmarks-range', '收藏数范围'), scheduleRangeValue(filters.bookmarksMin, filters.bookmarksMax)]
            ]
        );
        const downloadRows = [
            [bt('label.settings.skip', '跳过已下载作品'), bt('schedule.snapshot.value.always-on', '始终开启')],
            [bt('label.settings.filename-template', '文件名格式:'), scheduleValueOrUnset(download.fileNameTemplate)],
            [bt('label.settings.bookmark', '下载后自动收藏'), scheduleBoolLabel(!!download.bookmark)],
            [bt('label.settings.collection', '收藏到:'), scheduleCollectionLabel(download.collectionId)],
            [bt('label.settings.concurrent', '最大并发数:'), scheduleConcurrentValue(download.concurrent)],
            [bt('label.settings.interval', '作品间隔:'), scheduleMsValue(download.intervalMs)]
        ];
        // 图片间隔与实际目录检测仅对插画生效，小说快照不展示。
        if (kind !== 'novel') {
            downloadRows.push([bt('label.settings.image-delay', '图片间隔:'), scheduleMsValue(download.imageDelayMs)]);
            downloadRows.push([bt('label.settings.verify', '实际目录检测'), scheduleBoolLabel(!!download.verifyFiles)]);
        }
        downloadRows.push([bt('novel:batch.format-label', '小说格式'), scheduleNovelFormatLabel(download.novelFormat)]);
        downloadRows.push([bt('novel:batch.merge-label', '系列下载完成后生成合订本'), scheduleBoolLabel(!!download.novelMerge)]);
        downloadRows.push([bt('novel:batch.merge-format-label', '合订本格式'), scheduleNovelFormatLabel(download.novelMergeFormat || 'epub')]);
        const downloadSection = scheduleSnapshotSection(
            bt('schedule.snapshot.section.download', '下载设置快照'),
            downloadRows
        );
        return basicSection + sourceSection + filterSection + downloadSection;
    }

    function showScheduleSnapshot(id) {
        const modal = document.getElementById('schedule-snapshot-modal');
        const body = document.getElementById('schedule-snapshot-body');
        if (!modal || !body) return;
        const task = scheduleTasksCache.find(t => Number(t.id) === Number(id));
        body.innerHTML = task
            ? renderScheduleSnapshotBody(task)
            : `<div class="schedule-snapshot-empty">${escHtml(bt('schedule.snapshot.error.not-found', '未找到任务，请重新加载列表'))}</div>`;
        modal.dataset.taskId = String(id);
        modal.hidden = false;
        document.body.classList.add('schedule-modal-open');
        const closeBtn = modal.querySelector('.schedule-snapshot-close');
        if (closeBtn) closeBtn.focus();
    }

    function closeScheduleSnapshotModal() {
        const modal = document.getElementById('schedule-snapshot-modal');
        if (!modal) return;
        modal.hidden = true;
        delete modal.dataset.taskId;
        document.body.classList.remove('schedule-modal-open');
    }

    document.addEventListener('keydown', function (e) {
        if (e.key === 'Escape') closeScheduleSnapshotModal();
    });

    // 整列渲染所依据的卡片级数据签名：仅当这些字段变化时才需要重建整列 DOM。
    // 不含队列正文 / 展开态——它们由 SSE / 快照单独更新、由用户操作单独切换，不应触发整列重建。
    // 单卡片的渲染签名：仅当该任务的卡片级数据（状态灯/动作按钮/徽章/触发与时间/参数快照）变化时才需要替换 DOM。
    // 不含队列正文 / 待重试面板内容——那两个面板的 DOM 在 diff 替换时被「内 HTML + 滚动位置」整体迁移到新卡片上。
    function scheduleCardRenderSignature(t) {
        return JSON.stringify([
            t.name, t.enabled, t.type, t.cookieBound, t.runState,
            t.lastStatus, t.lastMessage, t.runStartedTime, t.nextRunTime, t.lastRunTime, t.paramsJson,
            t.accountId, t.ackWarningTime, t.pendingRetryArmed
        ]);
    }

    // 过度访问横幅是一个独立区段（按 accountId 分组、行为是账号级，不绑定任何具体卡片）；
    // 签名只看「哪些账号挂起 + 各账号挂起任务数」，签名相同就不重建横幅 DOM。
    function scheduleBannerRenderSignature(tasks) {
        const counts = new Map();
        tasks.forEach(t => {
            if (t.lastStatus === 'OVERUSE_PAUSED' && t.accountId) {
                counts.set(t.accountId, (counts.get(t.accountId) || 0) + 1);
            }
        });
        return JSON.stringify([...counts.entries()].sort());
    }

    async function loadScheduleTasks() {
        const list = document.getElementById('schedule-list');
        if (!list) return;
        // 语言切换路径：scheduleLastRenderedLang 与当前不一致时，先把签名清空，让本轮所有卡片
        // 都通过 replaceScheduleCardPreservingInner 走 replace，确保头/徽章/状态灯/动作按钮换语言；
        // 等列表渲染完，再为展开的「本轮队列详情」与「待重试」面板补一次重渲染（preserve-inner 会
        // 把这两块的旧 innerHTML 搬到新卡片，不主动刷一次会停留在旧语言）。
        const currentLang = uiLang();
        const langChanged = scheduleLastRenderedLang != null && scheduleLastRenderedLang !== currentLang;
        if (langChanged) {
            scheduleCardSignatures.clear();
            scheduleBannerSignature = null;
        }
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks`, {credentials: 'same-origin'});
            if (!res.ok) {
                renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
                return;
            }
            const tasks = await res.json();
            scheduleTasksCache = Array.isArray(tasks) ? tasks : [];

            // 不论列表是否为空：清理已不存在任务的 SSE 监听 / 模型 / 缓存，
            // 否则旧 handler 残留在 state.sseListeners，可能消费同 artworkId 的事件、并阻止
            // stopSchedulePolling 关闭共享 SSE 连接（条件含 sseListeners 为空）。
            const liveIds = new Set(scheduleTasksCache.map(t => Number(t.id)));
            releaseStaleScheduleQueueIds(liveIds);

            if (scheduleTasksCache.length === 0) {
                renderScheduleListPlaceholder(list, bt('schedule.list.empty', '暂无计划任务'));
            } else {
                if (scheduleEmptyStateRendered) {
                    // 上一拍是空态占位符，清掉后才能开始按卡片 diff。
                    list.innerHTML = '';
                    scheduleEmptyStateRendered = false;
                    scheduleBannerSignature = null;
                    scheduleCardSignatures.clear();
                }
                renderScheduleBannersDiff(list);
                renderScheduleCardsDiff(list);
                applyCookieDependentUi();
                if (langChanged) {
                    // 展开的「本轮队列详情」：先用 localizer 即时重渲染（已含 rawStatus 的新模型立即切语言），
                    // 再异步触发一次 fetchScheduleQueue 让后端有新数据时重建模型；旧版本 bake 过翻译的
                    // 缓存项需要靠这次重建才能跟随语言变化。
                    scheduleExpandedQueues.forEach(id => {
                        renderScheduleQueueBodyInto(id);
                        fetchScheduleQueue(id);
                    });
                    scheduleTasksCache.forEach(t => {
                        const panel = document.getElementById(`schedule-pending-${t.id}`);
                        if (panel && !panel.hidden) loadPendingPanel(t.id);
                    });
                }
            }
            scheduleLastRenderedLang = currentLang;
            // 无论是否重建整列：运行 / 排队中的展开卡片随本轮轮询拉取最新队列快照，非运行态保持缓存。
            refreshExpandedScheduleQueues();
        } catch (e) {
            renderScheduleListPlaceholder(list, bt('schedule.list.load-failed', '加载失败'));
        }
    }

    function renderScheduleListPlaceholder(list, text) {
        list.innerHTML = `<div class="schedule-empty">${escHtml(text)}</div>`;
        scheduleEmptyStateRendered = true;
        scheduleBannerSignature = null;
        scheduleCardSignatures.clear();
    }

    // 横幅按 accountId 分组、与卡片解耦：签名只看挂起账号的集合与各账号挂起任务计数，签名不变就不动 DOM。
    function renderScheduleBannersDiff(list) {
        const sig = scheduleBannerRenderSignature(scheduleTasksCache);
        if (sig === scheduleBannerSignature) return;
        scheduleBannerSignature = sig;
        const html = renderOveruseBanners(scheduleTasksCache);
        let wrap = list.querySelector(':scope > .schedule-overuse-banners');
        if (html) {
            const wrapped = `<div class="schedule-overuse-banners">${html}</div>`;
            if (wrap) {
                wrap.outerHTML = wrapped;
            } else {
                list.insertAdjacentHTML('afterbegin', wrapped);
            }
        } else if (wrap) {
            wrap.remove();
        }
    }

    // 按卡片 diff：仅对签名变化的卡片做替换，替换时把内部「队列正文/待重试面板」的 innerHTML、scrollTop、
    // 展开折叠态原样迁移到新卡片，避免「点暂停/恢复 → 整列 innerHTML 重建 → 展开的队列 DOM 被销毁 →
    // 队列滚动条回到顶部」这条问题路径。
    function renderScheduleCardsDiff(list) {
        const existing = new Map();
        list.querySelectorAll(':scope > .schedule-card').forEach(card => {
            existing.set(Number(card.dataset.taskId), card);
        });
        const liveIds = new Set();
        // 锚点：插入卡片的位置紧跟横幅之后（或在列表最前端，如无横幅）。
        const banners = list.querySelector(':scope > .schedule-overuse-banners');
        let prev = banners;
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            liveIds.add(id);
            const sig = scheduleCardRenderSignature(t);
            let card = existing.get(id);
            if (!card) {
                card = buildScheduleCardElement(t);
                scheduleCardSignatures.set(id, sig);
            } else if (sig !== scheduleCardSignatures.get(id)) {
                card = replaceScheduleCardPreservingInner(card, t);
                scheduleCardSignatures.set(id, sig);
            }
            const expected = prev ? prev.nextElementSibling : list.firstElementChild;
            if (expected !== card) {
                if (prev) prev.insertAdjacentElement('afterend', card);
                else list.insertAdjacentElement('afterbegin', card);
            }
            prev = card;
        });
        existing.forEach((card, id) => {
            if (!liveIds.has(id)) {
                card.remove();
                scheduleCardSignatures.delete(id);
            }
        });
    }

    function buildScheduleCardElement(t) {
        const temp = document.createElement('div');
        temp.innerHTML = renderScheduleTaskCard(t).trim();
        return temp.firstElementChild;
    }

    // 替换一张卡片但保留两个有状态子区块（队列正文 / 待重试面板）的 DOM 状态：内 HTML / 隐藏态 / 滚动位置。
    // 队列正文的折叠 caret / aria-expanded 同步矫正为保留下来的展开态。
    function replaceScheduleCardPreservingInner(existingCard, t) {
        const oldQueueBody = existingCard.querySelector('.schedule-queue-body');
        const oldPending = existingCard.querySelector('.schedule-pending-panel');
        // 卡片顶部 tips 区域：保留刚刚因操作写入的反馈，避免轮询重渲染把它清掉。
        const oldTip = existingCard.querySelector('.schedule-card-tip');
        const tipState = oldTip ? {text: oldTip.textContent, color: oldTip.style.color} : null;
        // 真正的滚动容器是 .schedule-queue-body 内部的 .schedule-queue-list（见 renderScheduleQueueBodyInto），
        // 因此 scrollTop 取内层 list 的值，替换后再写回新 list 上，避免队列滚动条跳回顶部。
        const oldQueueList = oldQueueBody ? oldQueueBody.querySelector('.schedule-queue-list') : null;
        const queueState = oldQueueBody ? {
            inner: oldQueueBody.innerHTML,
            scrollTop: oldQueueList ? oldQueueList.scrollTop : 0,
            expanded: !oldQueueBody.hasAttribute('hidden')
        } : null;
        const pendingState = oldPending ? {
            inner: oldPending.innerHTML,
            expanded: !oldPending.hasAttribute('hidden')
        } : null;

        const newCard = buildScheduleCardElement(t);

        const newQueueBody = newCard.querySelector('.schedule-queue-body');
        if (newQueueBody && queueState) {
            newQueueBody.innerHTML = queueState.inner;
            if (queueState.expanded) newQueueBody.removeAttribute('hidden');
            else newQueueBody.setAttribute('hidden', '');
            const toggle = newCard.querySelector('.schedule-queue-toggle');
            if (toggle) {
                toggle.setAttribute('aria-expanded', String(queueState.expanded));
                const caret = toggle.querySelector('.schedule-queue-caret');
                if (caret) caret.textContent = queueState.expanded ? '▾' : '▸';
            }
        }
        const newPending = newCard.querySelector('.schedule-pending-panel');
        if (newPending && pendingState) {
            newPending.innerHTML = pendingState.inner;
            if (pendingState.expanded) newPending.removeAttribute('hidden');
            else newPending.setAttribute('hidden', '');
        }
        const newTip = newCard.querySelector('.schedule-card-tip');
        if (newTip && tipState && tipState.text) {
            newTip.textContent = tipState.text;
            newTip.style.color = tipState.color;
        }

        existingCard.replaceWith(newCard);

        // scrollTop 必须在 element 已经在 document 中之后设置，否则浏览器会丢弃。
        if (newQueueBody && queueState && queueState.scrollTop) {
            const newQueueList = newQueueBody.querySelector('.schedule-queue-list');
            if (newQueueList) newQueueList.scrollTop = queueState.scrollTop;
        }
        return newCard;
    }

    function renderScheduleTaskCard(t) {
        const params = parseScheduleParams(t);
        const kind = scheduleKindFromParams(params);
        const typeLabel = scheduleTypeLabel(t.type);
        const kindLabel = scheduleKindLabel(kind);
        const triggerLabel = scheduleTriggerLabel(t);
        const cookieLabel = t.cookieBound
            ? bt('schedule.cookie.bound', '已绑定 Cookie')
            : bt('schedule.cookie.restricted', '受限模式（无 Cookie）');
        const enabledLabel = t.enabled ? bt('schedule.state.enabled', '已启用') : bt('schedule.state.disabled', '已停用');
        const light = scheduleStatusLight(t);

        // 功能区按钮的状态门（与后端 ScheduleService 守卫一致）：
        // busy=运行/排队中；suspended=暂停/过度访问/cookie 失效。
        const busy = t.runState === 'RUNNING' || t.runState === 'QUEUED';
        const paused = t.lastStatus === 'PAUSED';
        const suspended = paused || t.lastStatus === 'OVERUSE_PAUSED' || t.lastStatus === 'AUTH_EXPIRED';
        const busyTip = bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作');
        const runTip = busy ? busyTip
            : (!t.enabled ? bt('schedule.disabled.run-disabled', '任务已停用，请先启用')
                : bt('schedule.disabled.run-suspended', '任务暂停 / 挂起中，请先恢复或重新授权'));
        const pauseTip = bt('schedule.disabled.pause-idle', '任务未在运行，无需暂停；如需停止自动运行请用「停用」');
        const runAttr = (t.enabled && !busy && !suspended) ? '' : `disabled title="${escHtml(runTip)}"`;
        const resumeAttr = !busy ? '' : `disabled title="${escHtml(busyTip)}"`;
        const pauseAttr = busy ? '' : `disabled title="${escHtml(pauseTip)}"`;
        const busyAttr = busy ? `disabled title="${escHtml(busyTip)}"` : '';
        return `
        <div class="schedule-card${t.enabled ? '' : ' schedule-card-disabled'}" data-task-id="${t.id}">
            <div class="schedule-card-tip" id="schedule-card-tip-${t.id}" role="status" aria-live="polite"></div>
            <div class="schedule-card-head">
                <div class="schedule-card-head-main">
                    <span class="schedule-card-name">${escHtml(t.name)}</span>
                    <span class="schedule-badge">${escHtml(typeLabel)}</span>
                    <span class="schedule-badge">${escHtml(kindLabel)}</span>
                    <span class="schedule-badge${t.cookieBound ? ' schedule-badge-ok' : ''}">${escHtml(cookieLabel)}</span>
                    <span class="schedule-badge${t.enabled ? ' schedule-badge-ok' : ' schedule-badge-disabled'}">${escHtml(enabledLabel)}</span>
                </div>
                <span class="schedule-status-light schedule-status-light-${light.tone}${light.live ? ' schedule-status-light-live' : ''}" title="${escHtml(light.text)}">
                    <span class="schedule-light-dot" aria-hidden="true"></span>
                    <span class="schedule-light-text">${escHtml(light.text)}</span>
                </span>
            </div>
            <div class="schedule-card-meta">
                <div>${escHtml(bt('schedule.meta.trigger', '触发：'))}${escHtml(triggerLabel)}</div>
                <div>${escHtml(bt('schedule.meta.next', '下次运行：'))}${escHtml(fmtScheduleTime(t.nextRunTime))}</div>
                <div>${escHtml(bt('schedule.meta.last', '上次运行：'))}${escHtml(fmtScheduleTime(t.lastRunTime))}</div>
                <div class="schedule-meta-actions">
                    <button type="button" class="btn btn-blue" onclick="showScheduleSnapshot(${t.id})">${escHtml(bt('schedule.snapshot.action.view', '查看任务快照信息'))}</button>
                </div>
            </div>
            <div class="schedule-card-actions">
                <button class="btn btn-cyan" ${runAttr} onclick="runScheduleTask(${t.id})">${escHtml(bt('schedule.action.run', '▶ 立即运行'))}</button>
                <span class="cookie-dependent-action"><button class="btn btn-blue js-authorize-cookie-btn" data-busy="${busy ? '1' : '0'}" onclick="authorizeScheduleCookie(${t.id})">${escHtml(bt('schedule.action.authorize', '🔑 授权 Cookie'))}</button></span>
                ${paused
                    ? `<button class="btn btn-green" ${resumeAttr} onclick="resumeScheduleTask(${t.id})">${escHtml(bt('schedule.action.resume', '▶ 恢复'))}</button>`
                    : `<button class="btn btn-yellow" ${pauseAttr} onclick="pauseScheduleTask(${t.id})">${escHtml(bt('schedule.action.pause', '⏸ 暂停'))}</button>`}
                <button class="btn ${t.enabled ? 'btn-red' : 'btn-green'}" ${busyAttr} onclick="toggleScheduleTask(${t.id}, ${t.enabled ? 'false' : 'true'})">${escHtml(t.enabled ? bt('schedule.action.disable', '⏸ 停用') : bt('schedule.action.enable', '✔ 启用'))}</button>
                <button class="btn btn-purple" ${busyAttr} onclick="startEditScheduleTask(${t.id})">${escHtml(bt('schedule.action.edit', '✏ 编辑'))}</button>
                <button class="btn btn-gray" onclick="togglePendingPanel(${t.id})">${escHtml(bt('schedule.action.pending', '🧩 待重试'))}</button>
                <button class="btn btn-red" ${busyAttr} onclick="deleteScheduleTask(${t.id})">${escHtml(bt('schedule.action.delete', '🗑 删除'))}</button>
            </div>
            <div class="schedule-pending-panel" id="schedule-pending-${t.id}" hidden></div>
            ${renderScheduleQueueSection(t)}
        </div>`;
    }

    // 卡片底部「本轮队列详情」可折叠区域：默认折叠；展开态在列表重渲染后从 scheduleExpandedQueues 恢复，
    // 并直接用本地缓存预填充内容（避免闪烁），随后 refreshExpandedScheduleQueues / 展开动作再拉取最新数据。
    function renderScheduleQueueSection(t) {
        const id = Number(t.id);
        const expanded = scheduleExpandedQueues.has(id);
        const title = escHtml(bt('schedule.queue.title', '本轮队列详情'));
        const bodyHtml = expanded ? renderScheduleQueueBody(id) : '';
        return `
            <div class="schedule-queue" data-task-id="${id}">
                <button type="button" class="schedule-queue-toggle" aria-expanded="${expanded}" onclick="toggleScheduleQueue(${id})">
                    <span class="schedule-queue-caret" aria-hidden="true">${expanded ? '▾' : '▸'}</span>
                    <span>${title}</span>
                </button>
                <div class="schedule-queue-body"${expanded ? '' : ' hidden'}>${bodyHtml}</div>
            </div>`;
    }

    function scheduleQueueCacheKey(id) {
        return 'pixiv_schedule_queue_' + Number(id);
    }

    function readScheduleQueueCache(id) {
        try {
            const raw = storeGet(scheduleQueueCacheKey(id));
            if (!raw) return null;
            const parsed = JSON.parse(raw);
            return parsed && Array.isArray(parsed.items) ? parsed : null;
        } catch (e) {
            return null;
        }
    }

    function writeScheduleQueueCache(id, data) {
        try {
            storeSet(scheduleQueueCacheKey(id), JSON.stringify(data));
        } catch (e) { /* 存储不可用时忽略：内存渲染仍可工作 */ }
    }

    // 计划任务「本轮队列详情」的客户端模型：taskId → 队列项数组（与下载工作区 state.queue 同形，
    // 直接喂给 buildQueueItemHtml 渲染，保证两处队列完全一致）。后端 4s 快照提供权威的发现/终态，
    // SSE 提供运行中的逐图实时进度。
    const scheduleQueueModels = {};
    // 已登记的 SSE 监听器：taskId → { artworkIdKey: fn }，用于精确解绑、避免重复注册或误删工作区监听。
    const scheduleSseHandlers = {};
    // 上一轮轮询时仍在运行的展开任务：用于在运行结束的那一拍补拉一次最终终态快照。
    const scheduleQueueWasRunning = new Set();

    // ── 「本轮队列详情」高频刷新合批 ─────────────────────────────────────────────
    // 并发下载时 SSE 逐图进度事件会高频到达。若每个事件都整块重建 .schedule-queue-body 的 innerHTML
    //（含全部队列行），主线程会被反复的 DOM 拆建占满，交互延迟（INP）随之飙高。
    // 改为：SSE 只 patch 内存模型 + 标记脏行 id，再用节流合批，只替换发生变化的单行 outerHTML；
    // 统计栏 / 当前下载项区域用更低频的独立节流刷新。折叠 / 解绑 / 整块重渲染时清理待执行刷新与脏集合。
    const scheduleQueueDirtyRows = new Map();        // taskId → Set<queueId>：待局部刷新的脏行
    const scheduleQueueRowFlushHandles = new Map();  // taskId → setTimeout 句柄：脏行合批刷新
    const scheduleQueueMetaFlushHandles = new Map(); // taskId → setTimeout 句柄：统计/当前项低频刷新
    const SCHEDULE_QUEUE_ROW_FLUSH_MS = 150;         // 脏行批量刷新节流（100-250ms 区间）
    const SCHEDULE_QUEUE_META_FLUSH_MS = 350;        // 统计栏 / 当前下载项低频刷新（250-500ms 区间）

    // 取某任务的队列模型：内存优先，缺失时从 localStorage 缓存恢复（支持刷新 / 服务重启后继续展示）。
    function getScheduleQueueModel(id) {
        id = Number(id);
        if (scheduleQueueModels[id]) return scheduleQueueModels[id];
        const cache = readScheduleQueueCache(id);
        if (cache && Array.isArray(cache.items)) {
            scheduleQueueModels[id] = cache.items;
            return cache.items;
        }
        return null;
    }

    function getScheduleQueueMeta(id) {
        const cache = readScheduleQueueCache(id);
        return {
            startedTime: cache ? cache.startedTime : null,
            truncated: cache ? !!cache.truncated : false,
            total: cache && typeof cache.total === 'number' ? cache.total : null
        };
    }

    function scheduleTaskById(id) {
        return scheduleTasksCache.find(t => Number(t.id) === Number(id)) || null;
    }

    // 任务类型 → 队列项来源标识，复用工作区队列项的来源色块（user / search / series）。
    function scheduleSourceForType(type) {
        if (type === 'SEARCH') return 'search';
        if (type === 'SERIES') return 'series';
        return 'user';
    }

    // 后端队列项状态 → 工作区队列状态 + 原始状态码（未翻译，渲染时再 bt()）。
    // 不在这里 bake bt() 结果：模型会落到 localStorage 与跨语言切换的渲染轮次，bake 后无法跟随语言变化。
    function scheduleStatusToQueue(it) {
        switch (it.status) {
            case 'downloaded':
                return {status: 'completed', rawStatus: 'downloaded'};
            case 'skipped-downloaded':
                return {status: 'skipped', rawStatus: 'skipped-downloaded'};
            case 'skipped-filter':
                return {status: 'skipped', rawStatus: 'skipped-filter'};
            case 'failed':
                return {status: 'failed', rawStatus: 'failed', failureMessage: it.message || null};
            default:
                return {status: 'pending', rawStatus: 'pending'};
        }
    }

    // 后端队列项 → 工作区队列项（同 state.queue 形状），供 buildQueueItemHtml 渲染。
    // 注意：title / lastMessage 不在这里写入；只存 rawTitle / rawStatus / failureMessage 这些与语言
    // 无关的原始字段，渲染时由 localizeScheduleQueueItem 用当前 bt() 派生 title / lastMessage。
    function scheduleItemToQueue(it, type) {
        const isNovel = it.kind === 'novel';
        const rawId = String(it.id == null ? '' : it.id);
        const mapped = scheduleStatusToQueue(it);
        return {
            id: isNovel ? ('n' + rawId) : rawId,
            novelId: isNovel ? rawId : undefined,
            kind: isNovel ? 'novel' : 'illust',
            rawTitle: it.title && String(it.title).trim() ? String(it.title) : null,
            source: scheduleSourceForType(type),
            xRestrict: it.xRestrict == null ? null : it.xRestrict,
            isAi: it.ai === true,
            status: mapped.status,
            rawStatus: mapped.rawStatus,
            failureMessage: mapped.failureMessage || null,
            totalImages: 0,
            downloadedCount: 0,
            imageProgress: null,
            ugoiraProgress: null
        };
    }

    // 渲染前根据当前 UI 语言派生显示字段。模型里禁止 bake i18n 字符串（会被持久化到 localStorage、
    // 跨语言切换继续读到旧译文），所有翻译都集中在这里。
    // 兼容旧缓存：若 rawTitle / rawStatus 不存在（旧版烤过 title / lastMessage 的缓存项），回退用旧字段，
    // 这些条目要到下一次后端拉取重建模型后才会跟随语言切换。
    function localizeScheduleQueueItem(q) {
        const hasRawTitle = Object.prototype.hasOwnProperty.call(q, 'rawTitle');
        const title = hasRawTitle
            ? (q.rawTitle || bt('schedule.queue.no-title', '（暂无标题信息）'))
            : q.title;
        let lastMessage;
        switch (q.rawStatus) {
            case 'skipped-downloaded':
                lastMessage = bt('schedule.queue.status.skipped-downloaded', '已存在，跳过');
                break;
            case 'skipped-filter':
                lastMessage = bt('schedule.queue.status.skipped-filter', '被筛选条件跳过');
                break;
            case 'failed':
                lastMessage = q.failureMessage || bt('schedule.queue.status.failed', '失败');
                break;
            case 'downloaded':
            case 'pending':
                lastMessage = null;
                break;
            default:
                // 旧缓存或 SSE 中途置位（如 downloading / completed-from-pending）没有 rawStatus；
                // lastMessage 留给共享渲染器用 queueStatusText(status) 兜底。
                lastMessage = q.lastMessage != null ? q.lastMessage : null;
        }
        return Object.assign({}, q, {title, lastMessage});
    }

    // 用后端快照重建模型，同时保留 SSE 实时进度：后端仍为 pending 而本地正在下载时沿用本地实时态，
    // 避免每 4s 快照把进行中的进度条打回原形。
    function mergeScheduleQueueModel(id, incoming, type) {
        const prev = scheduleQueueModels[Number(id)] || [];
        const prevById = {};
        prev.forEach(q => { prevById[q.id] = q; });
        return incoming.map(it => {
            const q = scheduleItemToQueue(it, type);
            const old = prevById[q.id];
            if (old && q.status === 'pending' && old.status === 'downloading') {
                q.status = 'downloading';
                q.totalImages = old.totalImages || 0;
                q.downloadedCount = old.downloadedCount || 0;
                q.imageProgress = old.imageProgress || null;
                q.ugoiraProgress = old.ugoiraProgress || null;
            } else if (old) {
                q.totalImages = q.totalImages || old.totalImages || 0;
                q.downloadedCount = q.downloadedCount || old.downloadedCount || 0;
            }
            return q;
        });
    }

    function renderScheduleQueueBodyInto(id) {
        if (!scheduleExpandedQueues.has(Number(id))) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${Number(id)}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body) return;
        // 保留滚动位置：SSE / 快照刷新会替换正文 innerHTML，不保留则滚动条每次跳回顶部。
        const prevList = body.querySelector('.schedule-queue-list');
        const prevScroll = prevList ? prevList.scrollTop : 0;
        body.innerHTML = renderScheduleQueueBody(id);
        const newList = body.querySelector('.schedule-queue-list');
        if (newList) newList.scrollTop = prevScroll;
        // 整块已重渲染（含状态/统计/当前项/全部行）：丢弃此前累积的脏行与待执行的局部/低频刷新，避免重复刷新。
        cancelScheduleQueueFlush(id);
    }

    // 展开某任务的队列：切换箭头，先用缓存模型即时渲染，再向后端拉取最新一轮队列；折叠则不请求并解绑 SSE。
    function toggleScheduleQueue(id) {
        id = Number(id);
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        const toggleBtn = wrap.querySelector('.schedule-queue-toggle');
        const caret = wrap.querySelector('.schedule-queue-caret');
        if (scheduleExpandedQueues.has(id)) {
            scheduleExpandedQueues.delete(id);
            unsubscribeScheduleQueueSse(id);
            cancelScheduleQueueFlush(id); // 折叠：取消待执行的局部刷新，隐藏视图不再消耗主线程
            if (body) body.hidden = true;
            if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'false');
            if (caret) caret.textContent = '▸';
            return;
        }
        scheduleExpandedQueues.add(id);
        if (body) {
            body.hidden = false;
            body.innerHTML = renderScheduleQueueBody(id); // 缓存模型即时渲染（可能为空）
        }
        if (toggleBtn) toggleBtn.setAttribute('aria-expanded', 'true');
        if (caret) caret.textContent = '▾';
        fetchScheduleQueue(id); // 访问即拉取最新
    }

    // 列表重渲染后：运行 / 排队中的展开卡片拉取最新队列（实现「运行中展开则自动刷新」），
    // 运行刚结束的那一拍补拉一次终态快照，其余非运行态保持缓存渲染、撤掉 SSE 监听。
    function refreshExpandedScheduleQueues() {
        scheduleTasksCache.forEach(t => {
            const id = Number(t.id);
            const running = t.runState === 'RUNNING' || t.runState === 'QUEUED';
            if (!scheduleExpandedQueues.has(id)) {
                scheduleQueueWasRunning.delete(id);
                return;
            }
            if (running) {
                scheduleQueueWasRunning.add(id);
                fetchScheduleQueue(id);
            } else if (scheduleQueueWasRunning.has(id)) {
                scheduleQueueWasRunning.delete(id);
                fetchScheduleQueue(id); // 运行刚结束：拉取最终终态快照
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        });
    }

    async function fetchScheduleQueue(id) {
        id = Number(id);
        const task = scheduleTaskById(id);
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/queue`, {credentials: 'same-origin'});
            if (!res.ok) return; // 失败时保留已有模型渲染
            const data = await res.json();
            const incoming = Array.isArray(data.items) ? data.items : [];
            const cached = readScheduleQueueCache(id);
            // 后端无当轮队列（如进程重启后）而本地仍有缓存时，保留缓存继续展示，不被空队列覆盖。
            const keepCache = incoming.length === 0 && data.startedTime == null
                && cached && Array.isArray(cached.items) && cached.items.length > 0;
            if (!keepCache) {
                const model = mergeScheduleQueueModel(id, incoming, task ? task.type : null);
                scheduleQueueModels[id] = model;
                writeScheduleQueueCache(id, {
                    startedTime: data.startedTime != null ? data.startedTime : null,
                    truncated: !!data.truncated,
                    total: typeof data.total === 'number' ? data.total : model.length,
                    items: model,
                    savedAt: Date.now()
                });
            }
            renderScheduleQueueBodyInto(id);
            // 运行中 + 展开：订阅 SSE 逐图实时进度；否则解绑。
            if (scheduleExpandedQueues.has(id) && task && (task.runState === 'RUNNING' || task.runState === 'QUEUED')) {
                subscribeScheduleQueueSse(id);
            } else {
                unsubscribeScheduleQueueSse(id);
            }
        } catch (e) { /* 网络异常：保留模型渲染 */ }
    }

    // 按工作区口径统计模型各状态计数（与 updateStats 同义）。
    function computeScheduleQueueStats(model) {
        const count = s => model.filter(q => q.status === s).length;
        return {
            success: count('completed'),
            failed: count('failed'),
            active: count('downloading'),
            skipped: count('skipped'),
            pending: model.filter(q => ['idle', 'pending', 'paused'].includes(q.status)).length
        };
    }

    // 状态栏文案（对应工作区 #status-bar）：任务运行状态文案 + 本轮开始时间 + 截断提示。
    // 抽成独立函数，让整块渲染与低频 meta 刷新（refreshScheduleQueueMeta）共用一处口径。
    function buildScheduleQueueStatusText(id, model) {
        const task = scheduleTaskById(id);
        const meta = getScheduleQueueMeta(id);
        let statusText = task ? scheduleStatusLight(task).text : bt('schedule.light.never', '等待首次运行');
        if (meta.startedTime) {
            statusText += ' · ' + bt('schedule.queue.started', '本轮开始：{time}', {time: fmtScheduleTime(meta.startedTime)});
        }
        if (meta.truncated) {
            statusText += ' · ' + bt('schedule.queue.truncated', '作品过多，仅记录并展示前 {count} 项', {count: model.length});
        }
        return statusText;
    }

    // 完整照搬下载工作区底部的「状态栏 + 统计栏 + 当前下载卡 + 下载队列」四段结构，
    // 仅把数据源换成本任务的队列模型；各段分别复用 #status-bar / #stats-bar / #current-card / #queue-list 的样式与格式化函数。
    function renderScheduleQueueBody(id) {
        const model = getScheduleQueueModel(id) || [];
        const statusText = buildScheduleQueueStatusText(id, model);
        const s = computeScheduleQueueStats(model);
        // 渲染前用 localizeScheduleQueueItem 派生 title / lastMessage，确保跟随当前 UI 语言；
        // 模型本身仍是 raw 字段，下次语言切换重渲染再次派生即可。
        const localized = model.map(localizeScheduleQueueItem);
        const current = localized.find(q => q.status === 'downloading') || null;

        const statusLine = `<div class="schedule-queue-status">${escHtml(statusText)}</div>`;
        const statsLine = `<div class="schedule-queue-stats">${escHtml(formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped))}</div>`;
        const currentCard = `<div class="schedule-queue-current">${formatCurrentCardHtml(current)}</div>`;
        // 每行带上 data-queue-id（= 模型项 id），供 flushScheduleQueueRows 局部替换单行 outerHTML 时定位。
        const listInner = localized.length
            ? localized.map(q => buildQueueItemHtml(q, {removable: false, queueId: q.id})).join('')
            : `<div class="queue-empty">${escHtml(bt('status.queue-empty', '队列为空'))}</div>`;
        const listCard = `<div class="schedule-queue-list">${listInner}</div>`;
        return statusLine + statsLine + currentCard + listCard;
    }

    // 标记某任务的某行待刷新：只 patch 完模型后调用，合批后由 flushScheduleQueueRows 局部替换该行。
    function markScheduleQueueRowDirty(id, qId) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return; // 已折叠：无可见 DOM，丢弃
        let set = scheduleQueueDirtyRows.get(id);
        if (!set) { set = new Set(); scheduleQueueDirtyRows.set(id, set); }
        set.add(String(qId));
        if (!scheduleQueueRowFlushHandles.has(id)) {
            scheduleQueueRowFlushHandles.set(id,
                setTimeout(() => flushScheduleQueueRows(id), SCHEDULE_QUEUE_ROW_FLUSH_MS));
        }
        armScheduleQueueMetaFlush(id);
    }

    // 安排一次低频的统计栏 / 当前下载项刷新（已排程则复用，避免每行都重算整块统计/当前卡）。
    function armScheduleQueueMetaFlush(id) {
        id = Number(id);
        if (scheduleQueueMetaFlushHandles.has(id)) return;
        scheduleQueueMetaFlushHandles.set(id, setTimeout(() => {
            scheduleQueueMetaFlushHandles.delete(id);
            refreshScheduleQueueMeta(id);
        }, SCHEDULE_QUEUE_META_FLUSH_MS));
    }

    // 合批刷新脏行：只对发生变化的行重新生成单行 HTML 并替换其 outerHTML；
    // 找不到对应 DOM 行（如刚展开还没渲染过 list / 行被快照重建移除）时退化为一次整块渲染。
    function flushScheduleQueueRows(id) {
        id = Number(id);
        scheduleQueueRowFlushHandles.delete(id);
        const dirty = scheduleQueueDirtyRows.get(id);
        scheduleQueueDirtyRows.delete(id);
        if (!dirty || dirty.size === 0) return;
        if (!scheduleExpandedQueues.has(id)) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        const listEl = wrap ? wrap.querySelector('.schedule-queue-list') : null;
        const model = scheduleQueueModels[id];
        if (!wrap || !listEl || !model) {
            renderScheduleQueueBodyInto(id); // 列表尚未渲染或模型缺失：整块兜底（频率低，可接受）
            return;
        }
        const byId = {};
        model.forEach(q => { byId[q.id] = q; });
        let needFull = false;
        dirty.forEach(qId => {
            const q = byId[qId];
            if (!q) return; // 模型里已无此项（被快照重建移除）：留给后续整块渲染
            const row = listEl.querySelector(`.queue-item[data-queue-id="${qId}"]`);
            if (!row) { needFull = true; return; }
            row.outerHTML = buildQueueItemHtml(localizeScheduleQueueItem(q), {removable: false, queueId: q.id});
        });
        if (needFull) {
            renderScheduleQueueBodyInto(id);
        } else {
            armScheduleQueueMetaFlush(id); // 行已就地更新；统计 / 当前下载项交给低频 meta 刷新
        }
    }

    // 低频刷新统计栏 + 当前下载项 + 状态栏（不触碰队列列表 DOM，避免整块重建）。
    function refreshScheduleQueueMeta(id) {
        id = Number(id);
        if (!scheduleExpandedQueues.has(id)) return;
        const wrap = document.querySelector(`.schedule-queue[data-task-id="${id}"]`);
        if (!wrap) return;
        const body = wrap.querySelector('.schedule-queue-body');
        if (!body || body.hidden) return;
        const model = getScheduleQueueModel(id) || [];
        const statusEl = body.querySelector('.schedule-queue-status');
        if (statusEl) statusEl.textContent = buildScheduleQueueStatusText(id, model);
        const statsEl = body.querySelector('.schedule-queue-stats');
        if (statsEl) {
            const s = computeScheduleQueueStats(model);
            statsEl.textContent = formatStatsText(s.pending, s.success, s.failed, s.active, s.skipped);
        }
        const currentEl = body.querySelector('.schedule-queue-current');
        if (currentEl) {
            const cur = model.find(q => q.status === 'downloading');
            currentEl.innerHTML = formatCurrentCardHtml(cur ? localizeScheduleQueueItem(cur) : null);
        }
    }

    // 取消某任务待执行的局部刷新并清空脏集合：折叠 / 解绑 SSE / 整块重渲染 / 任务下线时调用，
    // 避免隐藏视图或陈旧任务继续占用主线程做无意义的刷新。
    function cancelScheduleQueueFlush(id) {
        id = Number(id);
        const rowHandle = scheduleQueueRowFlushHandles.get(id);
        if (rowHandle != null) { clearTimeout(rowHandle); scheduleQueueRowFlushHandles.delete(id); }
        const metaHandle = scheduleQueueMetaFlushHandles.get(id);
        if (metaHandle != null) { clearTimeout(metaHandle); scheduleQueueMetaFlushHandles.delete(id); }
        scheduleQueueDirtyRows.delete(id);
    }

    // ── 计划任务队列的 SSE 实时进度同步（复用工作区的聚合 EventSource） ──────────────────────────
    // 管理员的聚合 SSE 会收到全部下载进度事件（含计划任务后台下载，userUuid=null）。运行中展开队列时，
    // 为每个插画项按 artworkId 注册监听，把逐图进度并入模型并重渲染；折叠 / 运行结束即解绑。
    function subscribeScheduleQueueSse(id) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model) return;
        ensureSharedSSE();
        if (!scheduleSseHandlers[id]) scheduleSseHandlers[id] = {};
        const handlers = scheduleSseHandlers[id];
        model.forEach(q => {
            if (q.kind === 'novel') return; // 小说无逐图 SSE，靠 4s 快照同步终态
            const key = String(q.id);
            if (handlers[key]) return; // 已注册
            const fn = data => applyScheduleQueueSse(id, key, data);
            handlers[key] = fn;
            addSSEListener(key, fn);
        });
    }

    function unsubscribeScheduleQueueSse(id) {
        id = Number(id);
        // 不再有逐图事件进来：取消待执行的局部 / 低频刷新合批，避免遗留定时器空转。
        cancelScheduleQueueFlush(id);
        const handlers = scheduleSseHandlers[id];
        if (!handlers) return;
        Object.keys(handlers).forEach(key => removeSSEListener(key, handlers[key]));
        delete scheduleSseHandlers[id];
    }

    function unsubscribeAllScheduleQueueSse() {
        Object.keys(scheduleSseHandlers).forEach(id => unsubscribeScheduleQueueSse(id));
    }

    // 计划任务列表刷新后，对已不在 liveIds 中的任务连带清理：解绑 SSE / 删除内存模型 / 撤掉展开态 /
    // 移除本地缓存。覆盖 scheduleExpandedQueues、scheduleQueueModels、scheduleSseHandlers、
    // scheduleQueueWasRunning 四张表，避免任一处残留导致旧 handler 继续消费事件或阻止
    // stopSchedulePolling 关闭共享 SSE 连接。
    function releaseStaleScheduleQueueIds(liveIds) {
        const stale = new Set();
        for (const id of scheduleExpandedQueues) if (!liveIds.has(id)) stale.add(id);
        Object.keys(scheduleQueueModels).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        Object.keys(scheduleSseHandlers).forEach(k => {
            const id = Number(k);
            if (!liveIds.has(id)) stale.add(id);
        });
        for (const id of scheduleQueueWasRunning) if (!liveIds.has(id)) stale.add(id);
        stale.forEach(id => {
            unsubscribeScheduleQueueSse(id);
            scheduleExpandedQueues.delete(id);
            delete scheduleQueueModels[id];
            scheduleQueueWasRunning.delete(id);
            try { storeRemove(scheduleQueueCacheKey(id)); } catch (e) { /* 存储不可用：忽略 */ }
        });
    }

    function applyScheduleQueueSse(id, qId, data) {
        id = Number(id);
        const model = scheduleQueueModels[id];
        if (!model || !data) return;
        const q = model.find(x => x.id === qId);
        if (!q || data.cancelled) return;
        // SSE 同步对齐 rawStatus，让 localizeScheduleQueueItem 在渲染时派生出正确语言的 lastMessage；
        // downloading 不对应后端 raw 状态，置为 'downloading' 与 q.status 同步，localizer 走默认分支
        // 让共享渲染器用 queueStatusText(status) 兜底显示「下载中」。
        if (data.completed) {
            q.status = 'completed';
            q.rawStatus = 'downloaded';
        } else if (data.failed) {
            q.status = 'failed';
            q.rawStatus = 'failed';
        } else if (data.downloadedCount !== undefined || data.totalImages !== undefined) {
            q.status = 'downloading';
            q.rawStatus = 'downloading';
            if (data.totalImages !== undefined) q.totalImages = data.totalImages;
            if (data.downloadedCount !== undefined) q.downloadedCount = data.downloadedCount;
            q.imageProgress = data.imageProgress || q.imageProgress || null;
            q.ugoiraProgress = mergeUgoiraProgress(q.ugoiraProgress, data.ugoiraProgress);
        }
        // 只 patch 模型 + 标记脏行：不在每个事件里整块重建 DOM。合批后只替换变化的单行，
        // 统计 / 当前下载项由更低频的 meta 刷新处理，使高频进度事件不再阻塞主线程。
        markScheduleQueueRowDirty(id, qId);
    }

    async function runScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/run`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.run-started', '已开始后台运行'), 'success');
                // 立即刷新：后端 runOnce 同步阶段已把 runState 置为 QUEUED，刷新后状态灯立刻切到「排队中 → 运行中」，
                // 不必等下一拍 4s 轮询。
                await loadScheduleTasks();
            } else {
                // 状态门拒绝（陈旧 UI / 竞态：点击瞬间任务刚进入运行 / 挂起态）。刷新让按钮回到正确禁用态。
                setScheduleCardTip(id, bt('schedule.error.run', '当前状态不允许立即运行'), 'error');
                await loadScheduleTasks();
            }
        } catch (e) { /* ignore */ }
    }

    async function toggleScheduleTask(id, enabled) {
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}/enabled?enabled=${enabled}`,
                {method: 'POST', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

    // 过度访问账号级暂停横幅：把 OVERUSE_PAUSED 的任务按 accountId 分组，每组给两个账号级恢复按钮。
    function renderOveruseBanners(tasks) {
        const groups = new Map();
        tasks.forEach(t => {
            if (t.lastStatus !== 'OVERUSE_PAUSED' || !t.accountId) return;
            const list = groups.get(t.accountId) || [];
            list.push(t);
            groups.set(t.accountId, list);
        });
        if (groups.size === 0) return '';
        const defaultMinutes = 60;
        let html = '';
        groups.forEach((list, accountId) => {
            const acc = encodeURIComponent(accountId);
            html += `
            <div class="schedule-overuse-banner">
                <div class="schedule-overuse-title">${escHtml(bt('schedule.overuse.banner.title', '⚠️ 过度访问暂停'))}</div>
                <div class="schedule-overuse-desc">${escHtml(bt('schedule.overuse.banner.desc',
                    '账号 {account} 有 {count} 个计划任务因检测到 Pixiv 过度访问警告被暂停。',
                    {account: accountId, count: list.length}))}</div>
                <div class="schedule-overuse-actions">
                    <button class="btn btn-red" onclick="resumeScheduleAccountIgnore('${acc}')">${escHtml(bt('schedule.overuse.action.ignore', '无视风险，继续下载！(可能会导致删号)'))}</button>
                    <button class="btn btn-blue" onclick="resumeScheduleAccountDefer('${acc}')">${escHtml(bt('schedule.overuse.action.defer', '我已知晓，在 {minutes} 分钟后继续所有同账号任务', {minutes: defaultMinutes}))}</button>
                </div>
            </div>`;
        });
        return html;
    }

    async function pauseScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pause`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.paused', '已暂停该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.pause', '暂停失败'), 'error');
        }
    }

    async function resumeScheduleTask(id) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/resume`, {method: 'POST', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.resumed', '已恢复该任务'), 'success');
                loadScheduleTasks();
            } else {
                setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    async function resumeScheduleAccount(accountId, mode, minutes) {
        const body = {mode};
        if (mode === 'defer') body.minutes = minutes;
        try {
            const res = await fetch(`${BASE}/api/schedule/account/${accountId}/resume`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify(body)
            });
            if (res.ok) {
                setScheduleListStatus(bt('schedule.status.account-resumed', '已恢复该账号的所有任务'), 'success');
                loadScheduleTasks();
            } else {
                const err = await res.json().catch(() => ({}));
                setScheduleListStatus(err.error || err.message || bt('schedule.error.resume', '恢复失败'), 'error');
            }
        } catch (e) {
            setScheduleListStatus(bt('schedule.error.resume', '恢复失败'), 'error');
        }
    }

    function resumeScheduleAccountIgnore(accountId) {
        if (!confirm(bt('schedule.overuse.confirm.ignore',
            '确定无视过度访问警告并立即继续下载吗？短时间内继续大量下载可能导致账号被封禁。'))) return;
        resumeScheduleAccount(accountId, 'ignore');
    }

    function resumeScheduleAccountDefer(accountId) {
        const input = prompt(bt('schedule.overuse.prompt.minutes', '延迟多少分钟后继续？（最低 60）'), '60');
        if (input == null) return;
        let minutes = parseInt(input, 10);
        if (!Number.isFinite(minutes) || minutes < 60) minutes = 60;
        resumeScheduleAccount(accountId, 'defer', minutes);
    }

    async function togglePendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        if (!panel.hidden) {
            panel.hidden = true;
            return;
        }
        panel.hidden = false;
        await loadPendingPanel(id);
    }

    async function loadPendingPanel(id) {
        const panel = document.getElementById(`schedule-pending-${id}`);
        if (!panel) return;
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending`, {credentials: 'same-origin'});
            if (!res.ok) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
                return;
            }
            const items = await res.json();
            if (!Array.isArray(items) || items.length === 0) {
                panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.empty', '暂无待重试作品'))}</div>`;
                return;
            }
            const task = scheduleTaskById(id);
            const busy = !!task && (task.runState === 'RUNNING' || task.runState === 'QUEUED');
            const clearAttr = busy
                ? `disabled title="${escHtml(bt('schedule.disabled.busy', '任务运行 / 排队中，暂不可操作'))}"`
                : '';
            const rows = items.map(p => {
                const manual = p.needsManual ? bt('schedule.pending.needs-manual', '（需人工）') : '';
                const line = escHtml(bt('schedule.pending.item', '作品 {workId}：已重试 {attempts} 次{manual}',
                    {workId: p.workId, attempts: p.attempts, manual}));
                const reason = p.reason
                    ? `<div class="schedule-pending-reason">${escHtml(bt('schedule.pending.reason', '原因：{reason}', {reason: p.reason}))}</div>`
                    : '';
                return `<li class="schedule-pending-item${p.needsManual ? ' schedule-pending-manual' : ''}">
                    <div class="schedule-pending-line">${line}
                        <button class="btn btn-gray btn-xs" ${clearAttr} onclick="clearPendingItem(${id}, ${p.workId})">${escHtml(bt('schedule.pending.action.clear', '清除'))}</button>
                    </div>${reason}
                </li>`;
            }).join('');
            panel.innerHTML = `<div class="schedule-pending-head">${escHtml(bt('schedule.pending.title', '待重试 / 需人工'))}</div><ul class="schedule-pending-list">${rows}</ul>`;
        } catch (e) {
            panel.innerHTML = `<div class="schedule-pending-empty">${escHtml(bt('schedule.pending.load-failed', '加载待重试列表失败'))}</div>`;
        }
    }

    async function clearPendingItem(id, workId) {
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/pending/${workId}`, {method: 'DELETE', credentials: 'same-origin'});
            if (res.ok) {
                setScheduleCardTip(id, bt('schedule.status.pending-cleared', '已清除该条待重试记录'), 'success');
                await loadPendingPanel(id);
            }
        } catch (e) { /* ignore */ }
    }

    async function deleteScheduleTask(id) {
        if (!confirm(bt('schedule.confirm.delete', '确定删除这个计划任务吗？（绑定的 Cookie 也会被清除）'))) return;
        try {
            await fetch(`${BASE}/api/schedule/tasks/${id}`, {method: 'DELETE', credentials: 'same-origin'});
            loadScheduleTasks();
        } catch (e) { /* ignore */ }
    }

    async function authorizeScheduleCookie(id) {
        const cookie = getCookieInputHeaderString().trim();
        if (!cookie) {
            setScheduleCardTip(id, bt('schedule.error.no-cookie', '请先在上方 Cookie 卡片保存含 PHPSESSID 的 Cookie'), 'error');
            return;
        }
        if (!/(?:^|;\s*)PHPSESSID=/.test(cookie)) {
            setScheduleCardTip(id, bt('schedule.error.cookie-no-phpsessid', '当前 Cookie 不含 PHPSESSID，无法授权'), 'error');
            return;
        }
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/authorize-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({cookie})
            });
            if (!res.ok) {
                const err = await res.json().catch(() => ({}));
                setScheduleCardTip(id, err.error || err.message || bt('schedule.error.authorize', '授权失败'), 'error');
                return;
            }
            setScheduleCardTip(id, bt('schedule.status.authorized', 'Cookie 已授权绑定到该任务'), 'success');
            loadScheduleTasks();
        } catch (e) {
            setScheduleCardTip(id, bt('schedule.error.authorize', '授权失败'), 'error');
        }
    }

    // solo 模式创建任务后自动用当前输入框里的 Cookie 绑定该任务；best-effort，不阻断创建流程。
    // 返回值：'authorized' 成功 / 'no-cookie' 当前无可用含 PHPSESSID 的 Cookie / 'failed' 请求失败。
    async function autoAuthorizeScheduleCookie(id) {
        const cookie = getCookieInputHeaderString().trim();
        if (!cookie || !/(?:^|;\s*)PHPSESSID=/.test(cookie)) return 'no-cookie';
        try {
            const res = await fetch(`${BASE}/api/schedule/tasks/${id}/authorize-cookie`, {
                method: 'POST',
                credentials: 'same-origin',
                headers: {'Content-Type': 'application/json'},
                body: JSON.stringify({cookie})
            });
            return res.ok ? 'authorized' : 'failed';
        } catch (e) {
            return 'failed';
        }
    }


// ---- PixivBatch facade ----
window.PixivBatch.modes = window.PixivBatch.modes || {};
window.PixivBatch.modes.schedule = window.PixivBatch.modes.schedule || {};
window.PixivBatch.modes.schedule = Object.assign(window.PixivBatch.modes.schedule, { submitScheduleTask, resetScheduleForm, closeScheduleSnapshotModal, startEditScheduleTask, loadScheduleTasks });
