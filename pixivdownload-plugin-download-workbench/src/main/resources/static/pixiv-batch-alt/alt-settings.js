'use strict';
/* ============================================================
   alt-settings — 下载设置模型 + 设置抽屉
   设置键 / 默认值 / 单位换算与 batch-settings.js 对齐；文件名模板
   （sanitize / 变量替换 / 去重）逐字移植自 batch-download.js；
   「收藏到」数据源 /api/collections 与 refreshBatchCollections 同语义。
   ============================================================ */
function getIntervalMs() {
    const {interval, intervalUnit} = state.settings;
    return intervalUnit === 's' ? Math.round(interval * 1000) : Math.round(interval);
}

function getImageDelayMs() {
    const {imageDelay, imageDelayUnit} = state.settings;
    return imageDelayUnit === 's' ? Math.round(imageDelay * 1000) : Math.round(imageDelay);
}

function saveSettings() {
    if (scheduleState.editing) return;
    // 「收藏到」（收藏夹选择）不持久化：每次加载都默认为「不加入收藏夹」，仅在当前会话内生效。
    const {collectionId, ...persisted} = state.settings;
    storeSet('pixiv_batch_settings', JSON.stringify(persisted));
}

function loadSettings() {
    try {
        const raw = storeGet('pixiv_batch_settings');
        if (raw) {
            const parsed = JSON.parse(raw);
            delete parsed.collectionId;
            Object.assign(state.settings, parsed);
        }
    } catch {
    }
    state.settings.fileNameTemplate = normalizeFileNameTemplate(state.settings.fileNameTemplate);
    if (!['ASK', 'TRUNCATE', 'DEFAULT_NAME', 'CANCEL'].includes(state.settings.pathOverflowAction)) {
        state.settings.pathOverflowAction = 'ASK';
    }
}

/* ============================================================
   文件名模板（逐字移植 batch-download.js）
   ============================================================ */
const WINDOWS_RESERVED_FILE_NAMES = new Set([
    'CON', 'PRN', 'AUX', 'NUL',
    'COM1', 'COM2', 'COM3', 'COM4', 'COM5', 'COM6', 'COM7', 'COM8', 'COM9',
    'LPT1', 'LPT2', 'LPT3', 'LPT4', 'LPT5', 'LPT6', 'LPT7', 'LPT8', 'LPT9'
]);

function normalizeAuthorId(value) {
    if (value === null || value === undefined || value === '') return null;
    const parsed = Number.parseInt(String(value), 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

function normalizeFileNameTemplate(value) {
    const raw = value === null || value === undefined ? '' : String(value);
    return raw.trim() ? raw : DEFAULT_FILE_NAME_TEMPLATE;
}

function sanitizeFileNamePart(value) {
    let cleaned = value === null || value === undefined ? '' : String(value);
    cleaned = cleaned.replace(/[\\/:*?"<>|\x00-\x1F\x7F-\x9F]/g, '_').trim().replace(/[. ]+$/g, '');
    if (WINDOWS_RESERVED_FILE_NAMES.has(cleaned.toUpperCase())) cleaned = '_' + cleaned;
    return cleaned;
}

function normalizeBaseName(value, fallback) {
    let cleaned = sanitizeFileNamePart(value);
    if (!cleaned) cleaned = sanitizeFileNamePart(fallback);
    if (!cleaned) cleaned = 'untitled';
    return cleaned.length > 180 ? cleaned.slice(0, 180) : cleaned;
}

function appendFileNameSuffix(base, suffix) {
    const maxBase = Math.max(1, 180 - suffix.length);
    const trimmed = base.length > maxBase ? base.slice(0, maxBase) : base;
    return trimmed + suffix;
}

function ensureUniqueBaseNames(names) {
    const used = new Set();
    const baseCounts = new Map();
    return names.map((base, page) => {
        const baseKey = base.toLowerCase();
        const duplicate = baseCounts.get(baseKey) || 0;
        let candidate = base;
        if (duplicate > 0 || used.has(baseKey)) {
            let suffixIndex = 1;
            do {
                const suffix = `_p${page}${suffixIndex > 1 ? '_' + suffixIndex : ''}`;
                candidate = appendFileNameSuffix(base, suffix);
                suffixIndex++;
            } while (used.has(candidate.toLowerCase()));
        }
        baseCounts.set(baseKey, duplicate + 1);
        used.add(candidate.toLowerCase());
        return candidate;
    });
}

function formatFileNameBase(template, vars, page, count) {
    const normalizedTemplate = normalizeFileNameTemplate(template);
    const xRestrict = Number(vars.xRestrict) || 0;
    const isAi = !!vars.isAi;
    const replacements = {
        artwork_id: String(vars.artworkId || ''),
        artwork_title: sanitizeFileNamePart(vars.title || ''),
        author_id: vars.authorId ? String(vars.authorId) : '',
        author_name: sanitizeFileNamePart(vars.authorName || ''),
        timestamp: String(vars.timestamp || ''),
        page: String(page),
        count: String(count),
        ai: isAi ? 'AI' : '',
        'ai+': isAi ? 'AI' : 'Human',
        R18: xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : ''),
        'R18+': xRestrict === 2 ? 'R18G' : (xRestrict === 1 ? 'R18' : 'SFW')
    };
    const rendered = normalizedTemplate.replace(
        /\{(artwork_id|artwork_title|author_id|author_name|timestamp|page|count|ai\+?|R18\+?)\}/g,
        (_, key) => replacements[key] ?? ''
    );
    return normalizeBaseName(rendered, `${vars.artworkId}_p${page}`);
}

function buildDownloadFileNames(template, vars, count) {
    const safeCount = Math.max(1, Number(count) || 1);
    const names = [];
    for (let page = 0; page < safeCount; page++) {
        names.push(formatFileNameBase(template, vars, page, safeCount));
    }
    return ensureUniqueBaseNames(names);
}

/* ============================================================
   收藏夹（「收藏到」选项数据源）
   ============================================================ */
let batchCollections = [];

async function refreshBatchCollections(invocation) {
    if (invocation) invocation.assertActive();
    const canUseCollections = appMode === 'solo' || isAdmin;
    if (!canUseCollections) {
        batchCollections = [];
        state.settings.collectionId = null;
        return {collectionId: null, collections: []};
    }
    try {
        const res = await fetch(BASE + '/api/collections', {
            credentials: 'same-origin', signal: invocation ? invocation.signal : undefined
        });
        if (invocation) invocation.assertActive();
        if (!res.ok) {
            batchCollections = [];
            state.settings.collectionId = null;
            return {collectionId: null, collections: []};
        }
        const data = await res.json();
        if (invocation) invocation.assertActive();
        batchCollections = Array.isArray(data.collections) ? data.collections : [];
        const validIds = new Set(batchCollections.map(c => normalizeBatchCollectionId(c.id)).filter(id => id !== null));
        const current = normalizeBatchCollectionId(state.settings.collectionId);
        state.settings.collectionId = current !== null && validIds.has(current) ? current : null;
        return {collectionId: state.settings.collectionId, collections: batchCollections};
    } catch {
        if (invocation) invocation.assertActive();
        batchCollections = [];
        state.settings.collectionId = null;
        return {collectionId: null, collections: []};
    }
}

function normalizeBatchCollectionId(value) {
    if (value === null || value === undefined || value === '') return null;
    const parsed = Number.parseInt(String(value), 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
}

/* ============================================================
   设置抽屉 UI
   ============================================================ */
let settingsLabelSequence = 0;
function settingsRow(labelText, control, helpText) {
    const row = el('div', 'ab-setting-row');
    const head = el('div', 'ab-setting-head');
    const label = el('label', 'ab-setting-label', labelText);
    label.id = 'ab-setting-label-' + (++settingsLabelSequence);
    head.appendChild(label);
    row.appendChild(head);
    if (control) {
        const selector = 'input, select, textarea, button';
        const controls = control.matches(selector) ? [control] : Array.from(control.querySelectorAll(selector));
        if (controls.length === 1) {
            const input = controls[0];
            if (!input.id) input.id = label.id + '-control';
            label.htmlFor = input.id;
        }
        controls.forEach(input => {
            if (!input.hasAttribute('aria-label') && !input.hasAttribute('aria-labelledby')) {
                if (input.classList.contains('ab-unit-toggle')) {
                    input.setAttribute('aria-label', bt('settings.unit-switch', '切换时间单位'));
                } else input.setAttribute('aria-labelledby', label.id);
            }
            if (helpText) input.setAttribute('aria-describedby', label.id + '-help');
        });
        row.appendChild(control);
    }
    if (helpText) {
        const note = el('p', 'ab-field-note', helpText);
        note.id = label.id + '-help';
        row.appendChild(note);
    }
    return row;
}

function switchControl(checked, onChange, disabled) {
    const label = el('label', 'toggle ab-switch');
    const input = el('input');
    input.type = 'checkbox';
    input.setAttribute('role', 'switch');
    input.checked = !!checked;
    input.disabled = !!disabled;
    input.addEventListener('change', () => onChange(input.checked));
    label.append(input, el('span', 'toggle-icon'));
    return label;
}

function numberWithUnit(value, unit, onValue, onUnit) {
    const wrap = el('div', 'ab-number-unit');
    const input = el('input', 'ab-input ab-input--num');
    input.type = 'number';
    input.min = '0';
    input.step = unit === 's' ? '0.5' : '50';
    input.value = value;
    input.addEventListener('change', () => onValue(Math.max(0, parseFloat(input.value) || 0)));
    const unitBtn = el('button', 'ab-unit-toggle', unit);
    unitBtn.type = 'button';
    unitBtn.addEventListener('click', () => {
        const cur = parseFloat(input.value) || 0;
        let nextUnit;
        if (unitBtn.textContent === 's') {
            nextUnit = 'ms';
            input.value = Math.round(cur * 1000);
            input.step = '50';
        } else {
            nextUnit = 's';
            input.value = +(cur / 1000).toFixed(3);
            input.step = '0.5';
        }
        unitBtn.textContent = nextUnit;
        onUnit(nextUnit, parseFloat(input.value) || 0);
    });
    wrap.appendChild(input);
    wrap.appendChild(unitBtn);
    return wrap;
}

function buildSettingsDrawerBody() {
    const s = scheduleState.editing?.settings || state.settings;
    const body = el('div', 'ab-settings');
    if (scheduleState.editing) body.dataset.scheduleSettings = '1';
    body.appendChild(el('p', 'ab-field-note', scheduleState.editing
        ? bt('batch:schedule.save.editing', '', {name: scheduleState.editing.task.name})
        : bt('settings.scope', '更改供后续下载使用。单人模式保存在服务端工作台状态，多人模式保存在此浏览器。')));

    // —— 节奏 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('settings.group.pace', '下载节奏')));
    if (isAdmin && !scheduleState.editing) {
        const control = switchControl(s.autoStartOnEnqueue, value => {
            s.autoStartOnEnqueue = value;
            saveSettings();
        });
        const input = control.querySelector('input');
        input.id = 's-auto-start';
        const row = settingsRow(
            bt('batch:label.settings.auto-start', null),
            control,
            bt('batch:label.settings.auto-start.help', null)
        );
        body.appendChild(row);
    }
    body.appendChild(settingsRow(
        bt('settings.work-interval', '作品间隔'),
        numberWithUnit(s.interval, s.intervalUnit,
            v => { s.interval = v; saveSettings(); },
            (unit, v) => { s.intervalUnit = unit; s.interval = v; saveSettings(); })
    ));
    body.appendChild(settingsRow(
        bt('settings.image-interval', '图片间隔'),
        numberWithUnit(s.imageDelay, s.imageDelayUnit,
            v => { s.imageDelay = v; saveSettings(); },
            (unit, v) => { s.imageDelayUnit = unit; s.imageDelay = v; saveSettings(); })
    ));
    const concurrentInput = el('input', 'ab-input ab-input--num');
    concurrentInput.type = 'number';
    concurrentInput.min = '1';
    concurrentInput.max = '8';
    concurrentInput.value = s.concurrent;
    concurrentInput.addEventListener('change', () => {
        s.concurrent = Math.max(1, parseInt(concurrentInput.value, 10) || 1);
        saveSettings();
        if (state.isRunning) ensureWorkers();
    });
    body.appendChild(settingsRow(bt('settings.concurrent', '最大并发数'), concurrentInput));

    // —— 历史与重下 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('settings.group.history', '历史与重下')));
    const skipSwitch = switchControl(s.skipHistory, v => {
        s.skipHistory = v;
        saveSettings();
        verifyRow.style.display = v ? '' : 'none';
        deletedRow.style.display = v ? '' : 'none';
    });
    body.appendChild(settingsRow(bt('settings.skip-downloaded', '跳过已下载作品'), skipSwitch));
    const verifyRow = settingsRow(
        bt('settings.dir-check', '实际目录检测'),
        switchControl(s.verifyHistoryFiles, v => { s.verifyHistoryFiles = v; saveSettings(); }),
        bt('settings.dir-check.help', '检查记录目录是否存在、是否为空、是否含图片，无效则重新下载。'));
    verifyRow.style.display = s.skipHistory ? '' : 'none';
    body.appendChild(verifyRow);
    const deletedRow = settingsRow(
        bt('settings.allow-deleted', '允许已删除的作品重新下载'),
        switchControl(s.redownloadDeleted, v => { s.redownloadDeleted = v; saveSettings(); }),
        bt('settings.allow-deleted.help', '画廊删除的作品保留删除标记；不勾选视为已下载跳过，勾选后重新下载并清除标记。'));
    deletedRow.style.display = s.skipHistory ? '' : 'none';
    body.appendChild(deletedRow);

    // —— 收藏 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('settings.group.bookmark', '收藏')));
    const cookieOk = cookieHasPhpsessid();
    const bookmarkSwitch = switchControl(s.bookmark, v => { s.bookmark = v; saveSettings(); }, !cookieOk);
    const bookmarkRow = settingsRow(
        bt('settings.auto-bookmark', '下载后自动收藏'),
        bookmarkSwitch,
        cookieOk ? '' : bt('cookie.requires-phpsessid', '无有效cookie(PHPSESSID)此功能不可用'));
    body.appendChild(bookmarkRow);
    const collectionSel = el('select', 'ab-input');
    const noneOpt = el('option', '', bt('settings.collection.none', '（不加入收藏夹）'));
    noneOpt.value = '';
    collectionSel.appendChild(noneOpt);
    batchCollections.forEach(c => {
        const opt = el('option', '', c.name);
        opt.value = String(c.id);
        collectionSel.appendChild(opt);
    });
    collectionSel.value = s.collectionId === null ? '' : String(s.collectionId);
    collectionSel.addEventListener('change', () => {
        s.collectionId = normalizeBatchCollectionId(collectionSel.value);
    });
    body.appendChild(settingsRow(
        bt('settings.collection', '收藏到（本地收藏夹）'),
        collectionSel,
        batchCollections.length ? '' : bt('settings.collection.empty', '暂无本地收藏夹')));

    // —— 文件名 ——
    body.appendChild(el('h4', 'ab-settings-group', bt('settings.group.filename', '文件名格式')));
    const tplInput = el('input', 'ab-input ab-input--mono');
    tplInput.type = 'text';
    tplInput.value = s.fileNameTemplate;
    tplInput.spellcheck = false;
    tplInput.addEventListener('change', () => {
        s.fileNameTemplate = normalizeFileNameTemplate(tplInput.value);
        tplInput.value = s.fileNameTemplate;
        saveSettings();
        previewName();
    });
    const varChips = el('div', 'ab-var-chips');
    [
        ['{artwork_id}', 'settings.var.artwork-id', '作品 ID'],
        ['{artwork_title}', 'settings.var.artwork-title', '作品标题'],
        ['{author_id}', 'settings.var.author-id', '作者 ID'],
        ['{author_name}', 'settings.var.author-name', '作者名'],
        ['{timestamp}', 'settings.var.timestamp', '毫秒时间戳'],
        ['{page}', 'settings.var.page', '当前页码'],
        ['{count}', 'settings.var.count', '总页数'],
        ['{ai}', 'settings.var.ai', 'AI / 空'],
        ['{ai+}', 'settings.var.ai-plus', 'AI 或 Human'],
        ['{R18}', 'settings.var.r18', 'R18 / R18G / 空'],
        ['{R18+}', 'settings.var.r18-plus', 'SFW / R18 / R18G']
    ].forEach(([token, key, fallback]) => {
        const chip = el('button', 'ab-var-chip', token);
        chip.type = 'button';
        chip.title = bt(key, fallback);
        chip.addEventListener('click', () => {
            tplInput.value = tplInput.value + token;
            tplInput.dispatchEvent(new Event('change'));
            tplInput.focus();
        });
        varChips.appendChild(chip);
    });
    const namePreview = el('p', 'ab-name-preview');
    function previewName() {
        const preview = buildDownloadFileNames(tplInput.value, {
            artworkId: 12345678,
            title: bt('settings.filename.sample-title', '示例作品'),
            authorId: 98765,
            authorName: bt('settings.filename.sample-author', '示例画师'),
            xRestrict: 0,
            isAi: false,
            timestamp: Date.now()
        }, 2);
        namePreview.textContent = bt('settings.filename.preview', '预览：{name}.png', {name: preview[0]});
    }
    previewName();
    const templateControls = el('div', 'filename-template-controls');
    const templateSelect = el('select');
    templateControls.append(tplInput, templateSelect);
    window.PixivFilenameTemplatePresets.bind(
        tplInput,
        templateSelect,
        s,
        saveSettings,
        key => bt('batch:' + key),
        isAdmin
    );
    body.appendChild(settingsRow(bt('settings.filename', '文件名模板'), templateControls,
        bt('settings.filename.help', '生成不含扩展名的文件名主干；重复名称自动追加页码。点击变量插入。')));
    body.appendChild(varChips);
    body.appendChild(namePreview);
    const typeSettings = el('div');
    typeSettings.id = 'type-output-settings';
    body.appendChild(typeSettings);
    const runtime = altQueueTypes();
    if (runtime) runtime.contributionsOf('settings').forEach(setting => {
        if (typeof setting.mount !== 'function') return;
        if (scheduleState.editing) withScheduleEditSettings(() => setting.mount(typeSettings));
        else setting.mount(typeSettings);
    });

    if (isAdmin) {
        const pathAction = el('select', 'ab-input');
        pathAction.id = 's-path-overflow-action';
        pathAction.setAttribute('aria-label', bt('batch:path.overflow.label', null));
        pathAction.setAttribute('aria-describedby', 'ab-path-overflow-hint');
        [['ASK', 'ask'], ['TRUNCATE', 'truncate'], ['DEFAULT_NAME', 'default'], ['CANCEL', 'cancel']]
            .forEach(([value, key]) => {
                const option = el('option', '', bt('batch:path.overflow.' + key, null));
                option.value = value;
                pathAction.appendChild(option);
            });
        pathAction.value = s.pathOverflowAction || 'ASK';
        pathAction.addEventListener('change', () => {
            s.pathOverflowAction = pathAction.value;
            saveSettings();
        });
        const row = settingsRow(bt('batch:path.overflow.label', null), pathAction,
            bt('batch:path.overflow.hint', null));
        row.querySelector('.ab-field-note').id = 'ab-path-overflow-hint';
        body.appendChild(row);
    }

    // 作品类型与增强功能由其活动插件贡献真实设置片段。
    const settingsCardSlot = document.createElement('template');
    settingsCardSlot.setAttribute('data-qt-slot', 'settings-card');
    body.appendChild(settingsCardSlot);

    return body;
}

function openSettingsDrawer() {
    openDownloadOptionsDrawer('settings');
}

function openDownloadOptionsDrawer(section) {
    const body = el('div', 'ab-download-options');
    const filters = buildFiltersDrawerBody();
    const settings = buildSettingsDrawerBody();
    const footer = buildFiltersDrawerFooter(filters);
    const sections = [
        ['filters', bt('filters.title', '附加筛选')],
        ['settings', bt('settings.title', '下载设置')]
    ];
    function selectSection(value) {
        filters.hidden = value !== 'filters';
        settings.hidden = value !== 'settings';
        footer.parentElement.hidden = value !== 'filters';
    }
    const selector = smallSeg(sections, section, value => {
        selectSection(value);
        drawer.body.scrollTop = 0;
    });
    body.append(selector, filters, settings);
    const drawer = openDrawer({
        id: 'download-options',
        icon: 'sliders',
        title: sections.map(([, label]) => label).join(' · '),
        body,
        footer
    });
    selectSection(section);
    refreshAltSlots();
}

// 字段语义与可见性由贡献方提供；宿主只负责共享草稿和持久化。
function bindContributedSettings(root, bindings, isActive, refresh) {
    const cleanups = [];
    const settings = scheduleState.editing?.settings || state.settings;
    Object.entries(bindings).forEach(([id, binding]) => {
        const input = root.querySelector('#' + id);
        if (!input) return;
        binding.write(input, settings[binding.key]);
        const changed = () => {
            if (!root.isConnected || !isActive()) return;
            settings[binding.key] = binding.read(input);
            saveSettings();
            refresh();
        };
        ['input', 'change'].forEach(event => {
            input.addEventListener(event, changed);
            cleanups.push(() => input.removeEventListener(event, changed));
        });
    });
    refresh();
    return () => cleanups.splice(0).forEach(cleanup => cleanup());
}

function refreshContributedSettingsVisibility() {
    const runtime = altQueueTypes();
    if (!runtime) return;
    const mode = state.mode;
    let kind = null;
    if (mode === 'user') kind = userState.kind;
    else if (mode === 'search') kind = searchState.kind;
    else if (mode === QUICK_FETCH_MODE) {
        kind = quickState.drill && quickState.drill.type === 'collection' ? 'mixed'
            : quickState.drill && quickState.drill.kind || quickState.kind;
    }
    const type = kind && runtime.resolveSelectionForMode(kind, mode, null);
    runtime.contributionsOf('settings').forEach(contribution => {
        const card = contribution.cardId && document.getElementById(contribution.cardId);
        if (card) card.style.display = dockState.open || mode === SINGLE_IMPORT_MODE || mode === 'series'
            || kind === 'mixed' || type === contribution.type ? '' : 'none';
    });
}

if (typeof window.addEventListener === 'function') {
    window.addEventListener('pixivbatch:slotsrendered', refreshContributedSettingsVisibility);
}
window.PixivBatch = window.PixivBatch || {};
window.PixivBatch.settings = Object.assign(window.PixivBatch.settings || {}, {bindContributedSettings});

window.PixivBatchAlt.settings = Object.assign(window.PixivBatchAlt.settings, {
    getIntervalMs, getImageDelayMs, saveSettings, loadSettings,
    normalizeFileNameTemplate, sanitizeFileNamePart, buildDownloadFileNames,
    normalizeAuthorId, refreshBatchCollections, normalizeBatchCollectionId,
    openSettingsDrawer
});
