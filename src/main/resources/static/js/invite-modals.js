/* global window, document */
/**
 * 邀请码相关的共享 modal：创建/编辑邀请、配置可见标签或作者、展示邀请链接结果、Toast。
 * 由 pixiv-gallery.html、pixiv-invite-manage.html、pixiv-invite-detail.html 复用。
 *
 * 可见范围按媒体类型拆分为漫画/插画侧（tag/author）与小说侧（novelTag/novelAuthor），
 * 分别独立配置；语义和取数源相互独立。
 *
 * 使用 CSS 变量保证暗色模式自动适配；调用方需要在页面上提供主题变量
 * （--bg / --surface / --surface-muted / --text / --text-muted / --border / --brand）。
 */
(function () {
    'use strict';

    // ---------- 默认翻译（无 i18n 客户端时使用） ----------
    const FALLBACK = {
        'modal.cancel': '取消',
        'picker.cancel': '取消',
        'picker.save': '保存',
        'picker.search.placeholder': '搜索',
        'picker.filter.label': '显示',
        'picker.filter.all': '全部',
        'picker.filter.visible': '可见',
        'picker.filter.hidden': '不可见',
        'picker.unrestricted': '该维度全部可见',
        'picker.hint': '点击行可切换可见状态。',
        'picker.empty': '暂无匹配项',
        'picker.toggle.visible': '可见',
        'picker.toggle.hidden': '不可见',
        'picker.tag.title': '配置可见标签',
        'picker.author.title': '配置可见作者',
        'picker.novel-tag.title': '配置小说可见标签',
        'picker.novel-author.title': '配置小说可见作者',
        'picker.meta': '共 {total} 项',
        'modal.create.title': '邀请访客',
        'modal.create.submit': '创建邀请链接',
        'modal.field.name': '访客名称',
        'modal.field.name.placeholder': '访客名称（用于记忆）',
        'modal.field.name.hint': '仅用于你自己记忆，访客看不到。',
        'modal.field.expire': '有效期',
        'modal.field.expire.unit': '天',
        'modal.preset.7': '7天',
        'modal.preset.30': '30天',
        'modal.preset.365': '365天',
        'modal.preset.permanent': '永久',
        'modal.field.age-rating': '可见年龄分级',
        'modal.field.range.illust': '漫画/插画可见范围',
        'modal.field.range.novel': '小说可见范围',
        'modal.btn.config-tags': '配置可见标签',
        'modal.btn.config-authors': '配置可见作者',
        'modal.summary.tags': '标签：{value}',
        'modal.summary.authors': '作者：{value}',
        'modal.summary.all': '全部',
        'modal.summary.count': '{count} 个',
        'modal.summary.hint': '（OR 语义：标签命中或作者命中即可见）',
        'modal.error.name-required': '请填写访客名称',
        'modal.error.expire-required': '请填写有效期或选择"永久"',
        'modal.error.expire-invalid': '有效期必须为正整数',
        'modal.error.age-empty': '请至少选择一个年龄分级',
        'modal.error.whitelist-empty': '请至少配置一项可见标签或可见作者，或将其中一项设为"全部可见"',
        'result.title': '邀请链接已生成',
        'result.link': '邀请链接',
        'result.code': '邀请码',
        'result.hint': '通过链接访问可自动登录；也可在登录页粘贴邀请码进入。',
        'result.done': '确定',
        'copy': '复制',
        'copy.copied': '已复制',
        'copy.failed': '复制失败',
        'age.sfw': 'SFW',
        'age.r18': 'R18',
        'age.r18g': 'R18G',
    };

    function t(key, vars) {
        const i18n = window.PixivInvitesI18n;
        let template = (i18n && i18n.t) ? i18n.t('invite:' + key, FALLBACK[key] || key, vars) : (FALLBACK[key] || key);
        return interpolate(template, vars);
    }
    function interpolate(template, vars) {
        if (!vars) return template;
        return String(template).replace(/\{([a-zA-Z0-9_-]+)\}/g, (m, name) =>
            Object.prototype.hasOwnProperty.call(vars, name) ? vars[name] : m);
    }

    // ---------- styles ----------
    const STYLE_ID = 'invite-modals-style';
    if (!document.getElementById(STYLE_ID)) {
        const style = document.createElement('style');
        style.id = STYLE_ID;
        style.textContent = `
        .invite-modal-backdrop {
            position: fixed; inset: 0; background: rgba(0,0,0,0.5);
            display: none; align-items: center; justify-content: center;
            z-index: 9000; padding: 16px;
        }
        .invite-modal-backdrop.open { display: flex; }
        .invite-modal {
            background: var(--surface, #fff); color: var(--text, #222);
            border-radius: 12px; width: 100%; max-width: 560px;
            max-height: calc(100vh - 32px); display: flex; flex-direction: column;
            box-shadow: 0 12px 48px rgba(0,0,0,0.25);
        }
        .invite-modal.large { max-width: 760px; }
        .invite-modal-head {
            display: flex; align-items: center; justify-content: space-between;
            padding: 16px 20px; border-bottom: 1px solid var(--border, #eee);
            font-size: 16px; font-weight: 600;
        }
        .invite-modal-close {
            background: none; border: none; cursor: pointer; font-size: 20px;
            color: var(--text-muted, #888); line-height: 1;
        }
        .invite-modal-body { padding: 20px; overflow: auto; flex: 1; }
        .invite-modal-foot {
            padding: 12px 20px; border-top: 1px solid var(--border, #eee);
            display: flex; justify-content: flex-end; gap: 8px; flex-wrap: wrap;
        }
        .invite-field { margin-bottom: 14px; }
        .invite-field-label { display: block; font-size: 12px; font-weight: 600; margin-bottom: 6px; color: var(--text-muted, #555); }
        .invite-input, .invite-select {
            width: 100%; padding: 8px 10px; border: 1px solid var(--border, #ddd);
            border-radius: 6px; font-size: 13px; background: var(--surface, #fff); color: inherit;
        }
        .invite-input:focus, .invite-select:focus { outline: none; border-color: var(--brand, #28a745); }
        .invite-row { display: flex; gap: 8px; align-items: center; flex-wrap: wrap; }
        .invite-chip {
            display: inline-flex; align-items: center; padding: 4px 10px;
            border-radius: 999px; border: 1px solid var(--border, #ddd);
            background: var(--surface, #fff); color: inherit;
            cursor: pointer; font-size: 12px; user-select: none;
        }
        .invite-chip.active {
            background: var(--brand, #28a745); color: #fff; border-color: var(--brand, #28a745);
        }
        .invite-checkbox-row { display: flex; gap: 14px; flex-wrap: wrap; align-items: center; }
        .invite-btn {
            padding: 8px 14px; border-radius: 6px; border: 1px solid var(--border, #ddd);
            background: var(--surface, #fff); color: inherit; cursor: pointer; font-size: 13px;
        }
        .invite-btn.primary { background: var(--brand, #28a745); color: #fff; border-color: var(--brand, #28a745); }
        .invite-btn.danger { background: #dc3545; color: #fff; border-color: #dc3545; }
        .invite-btn:disabled { opacity: 0.55; cursor: not-allowed; }
        .invite-error { font-size: 12px; color: #dc3545; min-height: 16px; margin-top: 8px; }
        .invite-help { font-size: 12px; color: var(--text-muted, #777); margin-top: 4px; }
        .invite-link-block {
            display: flex; gap: 8px; align-items: center; padding: 8px 10px;
            border: 1px solid var(--border, #ddd); border-radius: 6px;
            background: var(--surface-muted, #f6f6f6); margin-bottom: 8px;
            word-break: break-all; font-family: ui-monospace, Consolas, monospace; font-size: 12px;
        }
        .invite-link-block .invite-link-value { flex: 1; }

        /* picker */
        .invite-picker-toolbar {
            display: flex; gap: 8px; align-items: center; margin-bottom: 10px; flex-wrap: wrap;
        }
        .invite-picker-list {
            border: 1px solid var(--border, #eee); border-radius: 6px;
            max-height: 50vh; min-height: 240px; overflow-y: auto; background: var(--surface, #fff);
        }
        .invite-picker-row {
            display: flex; align-items: center; justify-content: space-between;
            padding: 8px 12px; border-bottom: 1px solid var(--border, #f0f0f0);
            cursor: pointer; gap: 12px;
        }
        .invite-picker-row:last-child { border-bottom: none; }
        .invite-picker-row:hover { background: var(--surface-hover, var(--surface-muted, #fafafa)); }
        .invite-picker-row.disabled { cursor: not-allowed; opacity: 0.55; }
        .invite-picker-name { flex: 1; font-size: 13px; word-break: break-all; }
        .invite-picker-name .secondary { color: var(--text-muted, #888); margin-left: 6px; font-size: 12px; }
        .invite-picker-toggle {
            min-width: 60px; padding: 4px 10px; border-radius: 999px;
            font-size: 11px; font-weight: 600; text-align: center;
            border: 1px solid transparent;
        }
        .invite-picker-toggle.visible { background: rgba(40,167,69,0.18); color: #1a7d33; border-color: rgba(40,167,69,0.45); }
        .invite-picker-toggle.hidden  { background: rgba(220,53,69,0.18); color: #b41a1a; border-color: rgba(220,53,69,0.45); }
        .invite-picker-meta { font-size: 12px; color: var(--text-muted, #777); margin-bottom: 8px; }
        .invite-picker-empty { padding: 24px; text-align: center; color: var(--text-muted, #888); font-size: 13px; }
        .invite-checkbox { display: inline-flex; align-items: center; gap: 6px; cursor: pointer; user-select: none; font-size: 13px; }

        /* toast */
        .invite-toast-host {
            position: fixed; top: 20px; left: 50%; transform: translateX(-50%);
            display: flex; flex-direction: column; gap: 6px; z-index: 9100; pointer-events: none;
        }
        .invite-toast {
            background: var(--surface, #fff); color: var(--text, #222);
            border: 1px solid var(--border, #ddd);
            border-radius: 6px; padding: 8px 16px; font-size: 13px;
            box-shadow: 0 4px 16px rgba(0,0,0,0.15);
            opacity: 0; transition: opacity .2s ease;
            pointer-events: auto; max-width: 80vw;
        }
        .invite-toast.show { opacity: 1; }
        .invite-toast.success { border-left: 3px solid var(--brand, #28a745); }
        .invite-toast.error   { border-left: 3px solid #dc3545; }
        `;
        document.head.appendChild(style);
    }

    // ---------- helpers ----------
    function el(tag, attrs, children) {
        const node = document.createElement(tag);
        if (attrs) {
            for (const [k, v] of Object.entries(attrs)) {
                if (v == null) continue;
                if (k === 'class') node.className = v;
                else if (k === 'style' && typeof v === 'object') Object.assign(node.style, v);
                else if (k === 'text') node.textContent = v;
                else if (k.startsWith('on') && typeof v === 'function') {
                    node.addEventListener(k.slice(2).toLowerCase(), v);
                } else if (k === 'value') node.value = v;
                else if (k === 'checked') node.checked = !!v;
                else if (k === 'disabled') node.disabled = !!v;
                else node.setAttribute(k, v);
            }
        }
        if (children) {
            for (const child of children) {
                if (child == null) continue;
                node.appendChild(typeof child === 'string' ? document.createTextNode(child) : child);
            }
        }
        return node;
    }
    function closeBackdrop(backdrop) { if (backdrop && backdrop.parentNode) backdrop.parentNode.removeChild(backdrop); }

    async function copyText(text) {
        try {
            if (navigator.clipboard && navigator.clipboard.writeText) {
                await navigator.clipboard.writeText(text); return true;
            }
        } catch (_) { /* fallthrough */ }
        try {
            const ta = document.createElement('textarea');
            ta.value = text; ta.style.position = 'fixed'; ta.style.opacity = '0';
            document.body.appendChild(ta); ta.select();
            const ok = document.execCommand('copy'); document.body.removeChild(ta); return ok;
        } catch (_) { return false; }
    }

    // ---------- toast ----------
    let toastHost = null;
    function ensureToastHost() {
        if (toastHost && document.body.contains(toastHost)) return toastHost;
        toastHost = document.createElement('div');
        toastHost.className = 'invite-toast-host';
        document.body.appendChild(toastHost);
        return toastHost;
    }
    function showToast(message, kind) {
        const host = ensureToastHost();
        const node = document.createElement('div');
        node.className = 'invite-toast' + (kind ? ' ' + kind : '');
        node.textContent = message;
        host.appendChild(node);
        // 强制 reflow 触发过渡
        // eslint-disable-next-line no-unused-expressions
        node.offsetWidth;
        node.classList.add('show');
        setTimeout(() => {
            node.classList.remove('show');
            setTimeout(() => { if (node.parentNode) node.parentNode.removeChild(node); }, 220);
        }, 2400);
    }

    // ---------- visibility picker ----------
    /**
     * 行始终可点击。状态机：
     * - "全部可见"勾选 + selected=[] ≡ 所有项可见。
     * - 勾选状态下点击行：该行变为不可见。自动取消勾选并将 selected 设为 (全部 - 该行)。
     * - 未勾选状态下点击行：该行翻转可见状态（可见集合添加/移除）。
     *   若移除后 selected 与 items 等大（全部可见），自动重新勾选并清空 selected。
     *
     * {@code kind} 用于解析标题键：tag / author / novel-tag / novel-author。
     */
    function openVisibilityPicker(opts) {
        const { kind, items, onSubmit } = opts;
        let unrestricted = !!opts.unrestricted;
        let selected = new Set(opts.selectedIds || []);
        let filter = 'all';
        let keyword = '';

        const backdrop = el('div', { class: 'invite-modal-backdrop open' });
        const titleKey = resolvePickerTitleKey(kind);
        const list = el('div', { class: 'invite-picker-list' });

        function isVisible(item) {
            if (unrestricted) return true;
            return selected.has(item.id);
        }
        function rowMatchesFilter(item) {
            if (filter === 'visible' && !isVisible(item)) return false;
            if (filter === 'hidden' && isVisible(item)) return false;
            if (keyword) {
                const k = keyword.toLowerCase();
                const name = (item.name || '').toLowerCase();
                const sec = (item.secondary || '').toLowerCase();
                if (!name.includes(k) && !sec.includes(k)) return false;
            }
            return true;
        }
        function syncCheckbox() {
            unrestrictedCb.checked = unrestricted;
        }
        function toggleItem(item) {
            const wasVisible = isVisible(item);
            if (wasVisible) {
                // 切换为不可见
                if (unrestricted) {
                    // 从"全部可见"翻转：自动取消勾选并把 selected 设为 全部-{item}
                    unrestricted = false;
                    selected = new Set(items.map(it => it.id));
                    selected.delete(item.id);
                    syncCheckbox();
                } else {
                    selected.delete(item.id);
                }
            } else {
                // 切换为可见（仅 unrestricted=false 时可达）
                selected.add(item.id);
                if (selected.size === items.length) {
                    // 全部可见 → 自动勾选并清空
                    unrestricted = true;
                    selected.clear();
                    syncCheckbox();
                }
            }
            renderList();
        }
        function renderList() {
            list.innerHTML = '';
            const filtered = items.filter(rowMatchesFilter);
            if (filtered.length === 0) {
                list.appendChild(el('div', { class: 'invite-picker-empty', text: t('picker.empty') }));
                return;
            }
            for (const item of filtered) {
                const visible = isVisible(item);
                const toggle = el('span', {
                    class: 'invite-picker-toggle ' + (visible ? 'visible' : 'hidden'),
                    text: t(visible ? 'picker.toggle.visible' : 'picker.toggle.hidden')
                });
                const nameNode = el('div', { class: 'invite-picker-name' }, [
                    item.name || '',
                    item.secondary ? el('span', { class: 'secondary', text: item.secondary }) : null
                ]);
                const row = el('div', { class: 'invite-picker-row' }, [nameNode, toggle]);
                row.addEventListener('click', () => toggleItem(item));
                list.appendChild(row);
            }
        }

        const search = el('input', {
            class: 'invite-input',
            placeholder: t('picker.search.placeholder'),
            style: { flex: '1', minWidth: '160px' },
            oninput: (e) => { keyword = e.target.value || ''; renderList(); }
        });
        const filterSelect = el('select', {
            class: 'invite-select',
            style: { width: 'auto', minWidth: '100px' },
            onchange: (e) => { filter = e.target.value; renderList(); }
        }, [
            el('option', { value: 'all', text: t('picker.filter.all') }),
            el('option', { value: 'visible', text: t('picker.filter.visible') }),
            el('option', { value: 'hidden', text: t('picker.filter.hidden') }),
        ]);
        const filterLabel = el('label', { class: 'invite-help', style: { margin: 0 },
            text: t('picker.filter.label') });
        const unrestrictedCb = el('input', { type: 'checkbox', checked: unrestricted });
        unrestrictedCb.addEventListener('change', () => {
            unrestricted = unrestrictedCb.checked;
            // 用户主动切换"全部可见"清空 selected
            selected.clear();
            renderList();
        });
        const unrestrictedLabel = el('label', { class: 'invite-checkbox' }, [
            unrestrictedCb,
            el('span', { text: t('picker.unrestricted') })
        ]);

        const toolbar = el('div', { class: 'invite-picker-toolbar' }, [
            search,
            filterLabel,
            filterSelect,
            unrestrictedLabel
        ]);
        const meta = el('div', { class: 'invite-picker-meta',
            text: t('picker.meta', { total: items.length }) + ' · ' + t('picker.hint') });

        const cancelBtn = el('button', { type: 'button', class: 'invite-btn',
            text: t('picker.cancel'), onclick: () => closeBackdrop(backdrop) });
        const okBtn = el('button', { type: 'button', class: 'invite-btn primary',
            text: t('picker.save'),
            onclick: () => {
                onSubmit({ unrestricted, ids: Array.from(selected) });
                closeBackdrop(backdrop);
            } });

        const modal = el('div', { class: 'invite-modal large' }, [
            el('div', { class: 'invite-modal-head' }, [
                el('span', { text: t(titleKey) }),
                el('button', { class: 'invite-modal-close', text: '×', onclick: () => closeBackdrop(backdrop) })
            ]),
            el('div', { class: 'invite-modal-body' }, [meta, toolbar, list]),
            el('div', { class: 'invite-modal-foot' }, [cancelBtn, okBtn])
        ]);
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
        renderList();
    }

    function resolvePickerTitleKey(kind) {
        switch (kind) {
            case 'novel-tag': return 'picker.novel-tag.title';
            case 'novel-author': return 'picker.novel-author.title';
            case 'author': return 'picker.author.title';
            case 'tag':
            default: return 'picker.tag.title';
        }
    }

    // ---------- create / edit form ----------
    function openInviteFormModal(opts) {
        const prefill = opts.prefill || {};
        // 默认全部年龄分级勾选（O6）
        const isEdit = !!prefill.id || !!opts.editing;
        let allowSfw = isEdit ? prefill.allowSfw !== false : true;
        let allowR18 = isEdit ? !!prefill.allowR18 : true;
        let allowR18g = isEdit ? !!prefill.allowR18g : true;
        // 漫画/插画侧
        let tagUnrestricted = prefill.tagUnrestricted !== false;
        let authorUnrestricted = prefill.authorUnrestricted !== false;
        let tagIds = new Set(prefill.tagIds || []);
        let authorIds = new Set(prefill.authorIds || []);
        const tagsSnapshot = prefill.tagsSnapshot || null;
        const authorsSnapshot = prefill.authorsSnapshot || null;
        // 小说侧
        let novelTagUnrestricted = prefill.novelTagUnrestricted !== false;
        let novelAuthorUnrestricted = prefill.novelAuthorUnrestricted !== false;
        let novelTagIds = new Set(prefill.novelTagIds || []);
        let novelAuthorIds = new Set(prefill.novelAuthorIds || []);
        const novelTagsSnapshot = prefill.novelTagsSnapshot || null;
        const novelAuthorsSnapshot = prefill.novelAuthorsSnapshot || null;

        const nameInput = el('input', {
            class: 'invite-input', value: prefill.name || '',
            placeholder: t('modal.field.name.placeholder')
        });
        const expireInput = el('input', {
            class: 'invite-input', type: 'number', min: '1',
            value: prefill.expireDays != null ? String(prefill.expireDays) : '7',
            placeholder: t('modal.field.expire.unit'), style: { width: '120px' }
        });
        function presetChip(labelKey, days) {
            return el('button', { type: 'button', class: 'invite-chip', text: t(labelKey),
                onclick: () => {
                    if (days == null) {
                        expireInput.value = '';
                        expireInput.disabled = true;
                        expireInput.dataset.permanent = '1';
                    } else {
                        expireInput.disabled = false;
                        delete expireInput.dataset.permanent;
                        expireInput.value = String(days);
                    }
                } });
        }
        const expireRow = el('div', { class: 'invite-row' }, [
            expireInput,
            el('span', { text: t('modal.field.expire.unit') }),
            presetChip('modal.preset.7', 7),
            presetChip('modal.preset.30', 30),
            presetChip('modal.preset.365', 365),
            presetChip('modal.preset.permanent', null)
        ]);
        if (prefill.expireDays == null && prefill.permanent) {
            expireInput.value = '';
            expireInput.disabled = true;
            expireInput.dataset.permanent = '1';
        }

        function makeCheckbox(labelText, checked, onChange) {
            const cb = el('input', { type: 'checkbox', checked });
            cb.addEventListener('change', () => onChange(cb.checked));
            return el('label', { class: 'invite-checkbox' }, [cb, el('span', { text: labelText })]);
        }
        const ageRow = el('div', { class: 'invite-checkbox-row' }, [
            makeCheckbox(t('age.sfw'), allowSfw, v => allowSfw = v),
            makeCheckbox(t('age.r18'), allowR18, v => allowR18 = v),
            makeCheckbox(t('age.r18g'), allowR18g, v => allowR18g = v)
        ]);

        const illustSummary = el('div', { class: 'invite-help' });
        const novelSummary = el('div', { class: 'invite-help' });
        function summaryLine(kind, unrestrictedFlag, selectedSet) {
            const valueKey = unrestrictedFlag ? 'modal.summary.all'
                : null;
            const value = valueKey ? t(valueKey) : t('modal.summary.count', { count: selectedSet.size });
            return t(kind === 'tag' ? 'modal.summary.tags' : 'modal.summary.authors', { value });
        }
        function renderSummary() {
            illustSummary.textContent = summaryLine('tag', tagUnrestricted, tagIds)
                + ' · ' + summaryLine('author', authorUnrestricted, authorIds)
                + ' ' + t('modal.summary.hint');
            novelSummary.textContent = summaryLine('tag', novelTagUnrestricted, novelTagIds)
                + ' · ' + summaryLine('author', novelAuthorUnrestricted, novelAuthorIds)
                + ' ' + t('modal.summary.hint');
        }

        function makePickerBtn(kind, getItemsFn, getState, setState) {
            const btn = el('button', { type: 'button', class: 'invite-btn',
                text: t(kind === 'tag' || kind === 'novel-tag' ? 'modal.btn.config-tags' : 'modal.btn.config-authors'),
                onclick: async () => {
                    btn.disabled = true;
                    try {
                        const items = await getItemsFn();
                        const state = getState();
                        openVisibilityPicker({
                            kind, items,
                            unrestricted: state.unrestricted,
                            selectedIds: state.ids,
                            onSubmit: ({ unrestricted, ids }) => {
                                setState({ unrestricted, ids: new Set(ids) });
                                renderSummary();
                            }
                        });
                    } finally { btn.disabled = false; }
                } });
            return btn;
        }

        const illustTagBtn = makePickerBtn(
            'tag',
            async () => tagsSnapshot ? tagsSnapshot.slice() : await opts.fetchTags(),
            () => ({ unrestricted: tagUnrestricted, ids: tagIds }),
            ({ unrestricted, ids }) => { tagUnrestricted = unrestricted; tagIds = ids; }
        );
        const illustAuthorBtn = makePickerBtn(
            'author',
            async () => authorsSnapshot ? authorsSnapshot.slice() : await opts.fetchAuthors(),
            () => ({ unrestricted: authorUnrestricted, ids: authorIds }),
            ({ unrestricted, ids }) => { authorUnrestricted = unrestricted; authorIds = ids; }
        );
        const novelTagBtn = makePickerBtn(
            'novel-tag',
            async () => novelTagsSnapshot ? novelTagsSnapshot.slice() : await opts.fetchNovelTags(),
            () => ({ unrestricted: novelTagUnrestricted, ids: novelTagIds }),
            ({ unrestricted, ids }) => { novelTagUnrestricted = unrestricted; novelTagIds = ids; }
        );
        const novelAuthorBtn = makePickerBtn(
            'novel-author',
            async () => novelAuthorsSnapshot ? novelAuthorsSnapshot.slice() : await opts.fetchNovelAuthors(),
            () => ({ unrestricted: novelAuthorUnrestricted, ids: novelAuthorIds }),
            ({ unrestricted, ids }) => { novelAuthorUnrestricted = unrestricted; novelAuthorIds = ids; }
        );

        renderSummary();

        const errorBox = el('div', { class: 'invite-error' });
        const submitBtn = el('button', { type: 'button', class: 'invite-btn primary',
            text: opts.submitText || t('modal.create.submit') });

        const backdrop = el('div', { class: 'invite-modal-backdrop open' });
        submitBtn.addEventListener('click', async () => {
            errorBox.textContent = '';
            const name = nameInput.value.trim();
            if (!name) { errorBox.textContent = t('modal.error.name-required'); return; }
            let expireDays = null;
            if (!expireInput.dataset.permanent) {
                const raw = expireInput.value.trim();
                if (!raw) { errorBox.textContent = t('modal.error.expire-required'); return; }
                expireDays = parseInt(raw, 10);
                if (!(expireDays > 0)) { errorBox.textContent = t('modal.error.expire-invalid'); return; }
            }
            if (!allowSfw && !allowR18 && !allowR18g) {
                errorBox.textContent = t('modal.error.age-empty'); return;
            }
            const allDimensionsHidden =
                !tagUnrestricted && tagIds.size === 0
                && !authorUnrestricted && authorIds.size === 0
                && !novelTagUnrestricted && novelTagIds.size === 0
                && !novelAuthorUnrestricted && novelAuthorIds.size === 0;
            if (allDimensionsHidden) {
                errorBox.textContent = t('modal.error.whitelist-empty');
                return;
            }
            const payload = {
                name, expireDays,
                allowSfw, allowR18, allowR18g,
                tagUnrestricted, tagIds: tagUnrestricted ? [] : Array.from(tagIds),
                authorUnrestricted, authorIds: authorUnrestricted ? [] : Array.from(authorIds),
                novelTagUnrestricted, novelTagIds: novelTagUnrestricted ? [] : Array.from(novelTagIds),
                novelAuthorUnrestricted, novelAuthorIds: novelAuthorUnrestricted ? [] : Array.from(novelAuthorIds)
            };
            submitBtn.disabled = true;
            try {
                await opts.onSubmit(payload);
                closeBackdrop(backdrop);
            } catch (e) {
                errorBox.textContent = e && e.message ? e.message : t('modal.error.name-required');
            } finally {
                submitBtn.disabled = false;
            }
        });
        const cancelBtn = el('button', { type: 'button', class: 'invite-btn',
            text: t('modal.cancel'), onclick: () => closeBackdrop(backdrop) });

        const body = el('div', { class: 'invite-modal-body' }, [
            el('div', { class: 'invite-field' }, [
                el('label', { class: 'invite-field-label', text: t('modal.field.name') }),
                nameInput,
                el('div', { class: 'invite-help', text: t('modal.field.name.hint') })
            ]),
            el('div', { class: 'invite-field' }, [
                el('label', { class: 'invite-field-label', text: t('modal.field.expire') }),
                expireRow
            ]),
            el('div', { class: 'invite-field' }, [
                el('label', { class: 'invite-field-label', text: t('modal.field.age-rating') }),
                ageRow
            ]),
            el('div', { class: 'invite-field' }, [
                el('label', { class: 'invite-field-label', text: t('modal.field.range.illust') }),
                el('div', { class: 'invite-row' }, [illustTagBtn, illustAuthorBtn]),
                illustSummary
            ]),
            el('div', { class: 'invite-field' }, [
                el('label', { class: 'invite-field-label', text: t('modal.field.range.novel') }),
                el('div', { class: 'invite-row' }, [novelTagBtn, novelAuthorBtn]),
                novelSummary
            ]),
            errorBox
        ]);

        const modal = el('div', { class: 'invite-modal' }, [
            el('div', { class: 'invite-modal-head' }, [
                el('span', { text: opts.title || t('modal.create.title') }),
                el('button', { class: 'invite-modal-close', text: '×',
                    onclick: () => closeBackdrop(backdrop) })
            ]),
            body,
            el('div', { class: 'invite-modal-foot' }, [cancelBtn, submitBtn])
        ]);
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
    }

    // ---------- result modal ----------
    function openInviteResultModal(result) {
        const backdrop = el('div', { class: 'invite-modal-backdrop open' });
        function copyButton(text) {
            return el('button', { type: 'button', class: 'invite-btn', text: t('copy'),
                onclick: async (e) => {
                    const ok = await copyText(text);
                    e.target.textContent = ok ? t('copy.copied') : t('copy.failed');
                    setTimeout(() => { e.target.textContent = t('copy'); }, 1500);
                } });
        }
        const modal = el('div', { class: 'invite-modal' }, [
            el('div', { class: 'invite-modal-head' }, [
                el('span', { text: t('result.title') }),
                el('button', { class: 'invite-modal-close', text: '×',
                    onclick: () => closeBackdrop(backdrop) })
            ]),
            el('div', { class: 'invite-modal-body' }, [
                el('div', { class: 'invite-field-label', text: t('result.link') }),
                el('div', { class: 'invite-link-block' }, [
                    el('span', { class: 'invite-link-value', text: result.url }),
                    copyButton(result.url)
                ]),
                el('div', { class: 'invite-field-label', text: t('result.code') }),
                el('div', { class: 'invite-link-block' }, [
                    el('span', { class: 'invite-link-value', text: result.code }),
                    copyButton(result.code)
                ]),
                el('div', { class: 'invite-help', text: t('result.hint') })
            ]),
            el('div', { class: 'invite-modal-foot' }, [
                el('button', { type: 'button', class: 'invite-btn primary', text: t('result.done'),
                    onclick: () => closeBackdrop(backdrop) })
            ])
        ]);
        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);
    }

    window.InviteModals = {
        openInviteFormModal,
        openInviteResultModal,
        openVisibilityPicker,
        copyText,
        showToast,
        t,
    };
})();
