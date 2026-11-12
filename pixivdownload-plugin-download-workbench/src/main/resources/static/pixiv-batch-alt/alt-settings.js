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
    window.PixivDownloadProfiles?.refresh();
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

function normalizeAuthorId(value) {
    if (value === null || value === undefined || value === '') return null;
    const parsed = Number.parseInt(String(value), 10);
    return Number.isFinite(parsed) && parsed > 0 ? parsed : null;
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
let activeSettingsHint = null;

function attachSettingsHint(title, ...notes) {
    if (!title || !notes.length || title.closest('.ab-setting-hint-label')) return;
    const wrap = el('div', 'ab-setting-hint-label');
    title.parentNode.insertBefore(wrap, title);
    wrap.appendChild(title);
    const trigger = el('button', 'ab-setting-hint-trigger');
    trigger.type = 'button';
    bindAltText(trigger, () => bt('settings.hint', null, {title: title.textContent.trim()}), 'aria-label');
    const icon = el('span', 'ab-setting-hint-icon', '!');
    icon.setAttribute('aria-hidden', 'true');
    trigger.appendChild(icon);
    const popup = el('div', 'ab-setting-hint');
    popup.id = 'ab-setting-hint-' + (++settingsLabelSequence);
    popup.setAttribute('popover', 'manual');
    popup.setAttribute('role', 'tooltip');
    popup.append(...notes);
    trigger.setAttribute('aria-describedby', popup.id);
    wrap.append(trigger, popup);
    let leaveTimer;
    let lifetime;
    function hide() {
        clearTimeout(leaveTimer);
        if (!lifetime) return;
        lifetime.abort();
        lifetime = null;
        if (popup.matches(':popover-open')) popup.hidePopover();
        if (activeSettingsHint === hide) activeSettingsHint = null;
    }
    function show() {
        clearTimeout(leaveTimer);
        if (lifetime) return;
        activeSettingsHint?.();
        activeSettingsHint = hide;
        lifetime = new AbortController();
        popup.showPopover();
        const anchor = trigger.getBoundingClientRect();
        const width = document.documentElement.clientWidth;
        const height = window.innerHeight;
        popup.style.left = Math.max(8, Math.min(anchor.left, width - popup.offsetWidth - 8)) + 'px';
        popup.style.top = Math.max(8, Math.min(anchor.bottom + popup.offsetHeight + 4 <= height - 8
            ? anchor.bottom + 4 : anchor.top - popup.offsetHeight - 4, height - popup.offsetHeight - 8)) + 'px';
        const options = {signal: lifetime.signal, capture: true};
        document.addEventListener('keydown', event => {
            if (event.key !== 'Escape') return;
            event.preventDefault();
            event.stopPropagation();
            hide();
        }, options);
        window.addEventListener('resize', hide, options);
        window.addEventListener('scroll', event => {
            const current = trigger.getBoundingClientRect();
            if (!popup.contains(event.target) && (current.top !== anchor.top || current.left !== anchor.left)) hide();
        }, options);
        // 抽屉替换或贡献撤回时，释放顶层提示与监听器。
        const observer = new MutationObserver(() => {
            if (!trigger.isConnected || !trigger.checkVisibility()) hide();
        });
        observer.observe(document.body, {childList: true, subtree: true, attributes: true,
            attributeFilter: ['hidden', 'style', 'open']});
        lifetime.signal.addEventListener('abort', () => observer.disconnect(), {once: true});
    }
    function leave() {
        clearTimeout(leaveTimer);
        leaveTimer = setTimeout(() => {
            const focused = document.activeElement === trigger && trigger.matches(':focus-visible');
            if (!focused && !trigger.matches(':hover') && !popup.matches(':hover')) hide();
        }, 100);
    }
    trigger.addEventListener('pointerenter', show);
    trigger.addEventListener('focus', show);
    popup.addEventListener('pointerenter', () => clearTimeout(leaveTimer));
    trigger.addEventListener('pointerleave', leave);
    trigger.addEventListener('blur', leave);
    popup.addEventListener('pointerleave', leave);
}

function settingsRow(labelText, control, helpText) {
    const row = el('div', 'ab-setting-row');
    const head = el('div', 'ab-setting-copy');
    const label = el('label', 'ab-setting-label', labelText);
    label.id = 'ab-setting-label-' + (++settingsLabelSequence);
    head.appendChild(label);
    row.appendChild(head);
    if (control) {
        const selector = 'input, select, textarea, button';
        const controls = control.matches(selector) ? [control] : Array.from(control.querySelectorAll(selector));
        const input = controls.find(node => node.tagName === 'INPUT' || node.tagName === 'TEXTAREA')
            || controls.find(node => node.tagName === 'SELECT');
        if (input) {
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
        const field = el('div', 'ab-setting-control');
        field.appendChild(control);
        row.appendChild(field);
        row.classList.toggle('ab-setting-row--toggle', control.classList.contains('ab-switch'));
    }
    if (helpText) {
        const note = el('p', 'ab-field-note', helpText);
        note.id = label.id + '-help';
        attachSettingsHint(label, note);
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
    function group(key) {
        const section = el('section', 'ab-settings-section');
        const heading = el('h3', 'ab-settings-group', () => bt(key));
        heading.id = 'ab-settings-group-' + (++settingsLabelSequence);
        section.setAttribute('aria-labelledby', heading.id);
        section.appendChild(heading);
        body.appendChild(section);
        return section;
    }

    const pace = group('settings.group.pace');
    if (isAdmin && !scheduleState.editing) {
        const control = switchControl(s.autoStartOnEnqueue, value => {
            s.autoStartOnEnqueue = value;
            saveSettings();
        });
        const input = control.querySelector('input');
        input.id = 's-auto-start';
        const row = settingsRow(
            () => bt('batch:label.settings.auto-start', null),
            control,
            () => bt('batch:label.settings.auto-start.help', null)
        );
        pace.appendChild(row);
    }
    pace.appendChild(settingsRow(
        bt('settings.work-interval', '作品间隔'),
        numberWithUnit(s.interval, s.intervalUnit,
            v => { s.interval = v; saveSettings(); },
            (unit, v) => { s.intervalUnit = unit; s.interval = v; saveSettings(); })
    ));
    pace.appendChild(settingsRow(
        bt('settings.image-interval', '图片间隔'),
        numberWithUnit(s.imageDelay, s.imageDelayUnit,
            v => { s.imageDelay = v; saveSettings(); },
            (unit, v) => { s.imageDelayUnit = unit; s.imageDelay = v; saveSettings(); })
    ));
    const concurrentInput = el('input', 'ab-input ab-input--num');
    concurrentInput.type = 'number';
    concurrentInput.min = '1';
    concurrentInput.value = s.concurrent;
    concurrentInput.addEventListener('change', () => {
        s.concurrent = Math.max(1, parseInt(concurrentInput.value, 10) || 1);
        saveSettings();
        if (!scheduleState.editing && state.isRunning) ensureWorkers();
    });
    pace.appendChild(settingsRow(bt('settings.concurrent', '最大并发数'), concurrentInput));

    const history = group('settings.group.history');
    const historyOptions = el('div', 'ab-settings-dependent');
    historyOptions.hidden = !s.skipHistory;
    const skipSwitch = switchControl(s.skipHistory, v => {
        s.skipHistory = v;
        saveSettings();
        historyOptions.hidden = !v;
    });
    history.appendChild(settingsRow(bt('settings.skip-downloaded', '跳过已下载作品'), skipSwitch));
    const verifyRow = settingsRow(
        () => bt('settings.dir-check', '实际目录检测'),
        switchControl(s.verifyHistoryFiles, v => { s.verifyHistoryFiles = v; saveSettings(); }),
        () => bt('settings.dir-check.help', '检查已登记的插画、漫画和动图是否完整，缺页或空文件会重新下载，检测出错则报错。历史记录会保留，已删除作品按重下设置处理。'));
    historyOptions.appendChild(verifyRow);
    const deletedRow = settingsRow(
        () => bt('settings.allow-deleted', '允许已删除的作品重新下载'),
        switchControl(s.redownloadDeleted, v => { s.redownloadDeleted = v; saveSettings(); }),
        () => bt('settings.allow-deleted.help', '画廊删除的作品保留删除标记；不勾选视为已下载跳过，勾选后重新下载并清除标记。'));
    historyOptions.appendChild(deletedRow);
    history.appendChild(historyOptions);

    const bookmarks = group('settings.group.bookmark');
    const cookieOk = cookieHasPhpsessid();
    const bookmarkSwitch = switchControl(s.bookmark, v => { s.bookmark = v; saveSettings(); }, !cookieOk);
    const bookmarkRow = settingsRow(
        () => bt('settings.auto-bookmark', '下载后自动收藏'),
        bookmarkSwitch,
        cookieOk ? '' : () => bt('cookie.requires-phpsessid', '无有效cookie(PHPSESSID)此功能不可用'));
    bookmarks.appendChild(bookmarkRow);
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
    bookmarks.appendChild(settingsRow(
        () => bt('settings.collection', '收藏到（本地收藏夹）'),
        collectionSel,
        batchCollections.length ? '' : () => bt('settings.collection.empty', '暂无本地收藏夹')));

    const naming = group('settings.group.filename');
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
    tplInput.addEventListener('input', previewName);
    const variables = el('details', 'ab-template-variables');
    const variableSummary = el('summary', '', () => bt('settings.filename.variables'));
    variables.appendChild(variableSummary);
    variables.addEventListener('keydown', event => {
        if (event.key === 'Escape' && variables.open) {
            event.preventDefault();
            event.stopPropagation();
            variables.open = false;
            variableSummary.focus();
        }
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
        const chip = el('button', 'ab-var-chip', () => bt(key, fallback));
        chip.type = 'button';
        chip.title = token;
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
    const templateRow = settingsRow(() => bt('settings.filename'), templateControls, () => bt('settings.filename.help'));
    templateRow.classList.add('ab-setting-row--wide');
    namePreview.id = tplInput.id + '-preview';
    tplInput.setAttribute('aria-describedby', tplInput.getAttribute('aria-describedby') + ' ' + namePreview.id);
    naming.append(templateRow, namePreview);
    variables.appendChild(varChips);
    naming.appendChild(variables);

    if (isAdmin) {
        const pathAction = el('select', 'ab-input');
        pathAction.id = 's-path-overflow-action';
        pathAction.setAttribute('aria-label', bt('batch:path.overflow.label', null));
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
        const row = settingsRow(() => bt('batch:path.overflow.label', null), pathAction,
            () => bt('batch:path.overflow.hint', null));
        naming.appendChild(row);
    }

    const typeSettings = el('div');
    typeSettings.id = 'type-output-settings';
    body.appendChild(typeSettings);
    const runtime = altQueueTypes();
    if (runtime) runtime.contributionsOf('settings').forEach(setting => {
        if (typeof setting.mount !== 'function') return;
        if (scheduleState.editing) withScheduleEditSettings(() => setting.mount(typeSettings));
        else setting.mount(typeSettings);
    });

    // 作品类型与增强功能由其活动插件贡献真实设置片段。
    const settingsCardSlot = document.createElement('template');
    settingsCardSlot.setAttribute('data-qt-slot', 'settings-card');
    body.appendChild(settingsCardSlot);

    return body;
}

function openSettingsDrawer() {
    openDownloadOptionsDrawer('filters');
}

function openDownloadOptionsDrawer(section) {
    const body = el('div', 'ab-download-options');
    const profiles = el('div');
    if (isAdmin) body.appendChild(profiles);
    const scope = el('p', 'ab-field-note', () => scheduleState.editing
        ? bt('settings.draft-scope', null)
        : bt('settings.scope', null));
    const content = el('div', 'ab-download-options-content');
    const footer = el('div', 'ab-drawer-actions');
    const done = el('button', 'ab-btn ab-btn--primary', () => bt('settings.done', null));
    done.type = 'button';
    done.addEventListener('click', closeDrawer);
    footer.appendChild(done);
    const sections = [
        ['filters', () => bt('settings.section.filters', null)],
        ['settings', () => bt('settings.section.download', null)]
    ];
    function selectSection(value) {
        section = value;
        content.querySelector('.ab-filters').hidden = value !== 'filters';
        content.querySelector('.ab-settings').hidden = value !== 'settings';
    }
    function renderContent() {
        content.replaceChildren(buildFiltersDrawerBody(), buildSettingsDrawerBody());
        selectSection(section);
        refreshAltSlots();
    }
    const selector = smallSeg(sections, section, selectSection);
    selector.classList.add('ab-download-sections');
    selector.setAttribute('role', 'group');
    bindAltText(selector, () => bt('settings.title', null), 'aria-label');
    body.append(selector, content);
    const drawer = openDrawer({
        id: 'download-options',
        icon: 'sliders',
        title: () => bt('settings.title', null),
        body,
        footer
    });
    attachSettingsHint(drawer.box.querySelector('.ab-drawer-title'), scope);
    renderContent();
    if (isAdmin) mountAltDownloadProfiles(profiles, renderContent);
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
    root.querySelectorAll('[data-setting-hint-for]').forEach(note => {
        attachSettingsHint(root.querySelector('label[for="' + note.dataset.settingHintFor + '"]'), note);
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
window.PixivBatch.settings = Object.assign(window.PixivBatch.settings || {}, {
    bindContributedSettings, attachHint: attachSettingsHint
});
window.PixivBatch.settings.target = () => scheduleState.editing?.settings || state.settings;

window.PixivBatchAlt.settings = Object.assign(window.PixivBatchAlt.settings, {
    getIntervalMs, getImageDelayMs, saveSettings, loadSettings,
    normalizeFileNameTemplate, sanitizeFileNamePart, buildDownloadFileNames,
    normalizeAuthorId, refreshBatchCollections, normalizeBatchCollectionId,
    openSettingsDrawer
});
