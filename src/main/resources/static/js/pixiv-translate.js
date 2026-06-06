/*
 * 小说 AI 翻译共享模块：翻译弹窗 + 名词映射表（glossary）选择/编辑 + 翻译/合订接口 + 内容语言切换器。
 * 由 pixiv-novel.html 与 pixiv-series.html 共用，保证两端弹窗与交互一致。
 *
 * 依赖：调用方提供已加载 'translate'（+'common'）命名空间的 PixivI18n 实例。
 * 所有面向用户的字符串走 i18n（translate: 命名空间），不写死单一语言。
 * 名词映射表接口在 /api/admin/glossary（admin-only，受 AuthFilter monitor 语义保护）。
 */
(function (global) {
    'use strict';

    var CONTENT_LANG_STORAGE_KEY = 'pixiv.contentLang';

    function tt(i18n, key, fallback, vars) {
        if (i18n && typeof i18n.t === 'function') {
            return i18n.t('translate:' + key, fallback, vars);
        }
        return interpolate(fallback != null ? fallback : key, vars);
    }

    function interpolate(template, vars) {
        if (!vars) return String(template);
        return String(template).replace(/\{([a-zA-Z0-9_.-]+)\}/g, function (m, name) {
            return Object.prototype.hasOwnProperty.call(vars, name) ? String(vars[name]) : m;
        });
    }

    // ── 名词映射表后端接口（admin-only）────────────────────────────────────────────
    async function apiJson(url, options) {
        var res = await fetch(url, Object.assign({ credentials: 'same-origin' }, options || {}));
        if (!res.ok) {
            var msg = 'HTTP ' + res.status;
            try { var j = await res.json(); if (j && (j.error || j.message)) msg = j.error || j.message; } catch (_) {}
            throw new Error(msg);
        }
        if (res.status === 204) return null;
        return res.json();
    }

    function glossaryListAll() {
        return apiJson('/api/admin/glossary').then(function (d) { return (d && d.glossaries) || []; });
    }
    function glossaryGet(id) { return apiJson('/api/admin/glossary/' + encodeURIComponent(id)); }
    function glossaryNovelDefault(novelId) {
        return apiJson('/api/admin/glossary/novel/' + encodeURIComponent(novelId) + '/default');
    }
    function glossarySeriesDefault(seriesId) {
        return apiJson('/api/admin/glossary/series/' + encodeURIComponent(seriesId) + '/default');
    }
    function glossaryCreate(body) {
        return apiJson('/api/admin/glossary', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    }
    function glossaryRename(id, name) {
        return apiJson('/api/admin/glossary/' + encodeURIComponent(id), {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ name: name })
        });
    }
    function glossarySaveEntries(id, entries) {
        return apiJson('/api/admin/glossary/' + encodeURIComponent(id) + '/entries', {
            method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ entries: entries })
        });
    }
    function glossaryDelete(id) {
        return apiJson('/api/admin/glossary/' + encodeURIComponent(id), { method: 'DELETE' });
    }

    // ── 名词映射表编辑器（三层弹窗：表名+语言列表 → 选/加语言 → 一对一映射）──────────────
    // 第二层（glossary editor）：编辑表名 + 该表的语言列表（每种目标语言一组映射）。
    // 第三层（lang mapping editor）：对某一语言用「原文 ↔ 译名」成对输入框逐条编辑。
    var glossaryEditorEl = null;
    var glossaryEditorRefs = null;
    var langEditorEl = null;
    var langEditorRefs = null;

    function buildGlossaryEditor() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-glossary-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-g-title"></span>' +
            '    <button class="pt-close pt-g-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-g-name-label"></span>' +
            '      <input class="pt-input pt-g-name" type="text" maxlength="120">' +
            '    </label>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-g-langs-label"></span>' +
            '      <div class="pt-glang-list"></div>' +
            '      <div class="pt-glang-add">' +
            '        <input class="pt-input pt-glang-add-input" type="text" maxlength="35">' +
            '        <button class="pt-btn pt-glang-add-btn" type="button"></button>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-g-langs-hint"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-btn-danger pt-g-delete" type="button"></button>' +
            '    <span style="flex:1"></span>' +
            '    <button class="pt-btn pt-g-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-g-save" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        glossaryEditorRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-g-title'),
            close: backdrop.querySelector('.pt-g-close'),
            nameLabel: backdrop.querySelector('.pt-g-name-label'),
            nameInput: backdrop.querySelector('.pt-g-name'),
            langsLabel: backdrop.querySelector('.pt-g-langs-label'),
            langList: backdrop.querySelector('.pt-glang-list'),
            addInput: backdrop.querySelector('.pt-glang-add-input'),
            addBtn: backdrop.querySelector('.pt-glang-add-btn'),
            langsHint: backdrop.querySelector('.pt-g-langs-hint'),
            del: backdrop.querySelector('.pt-g-delete'),
            cancel: backdrop.querySelector('.pt-g-cancel'),
            save: backdrop.querySelector('.pt-g-save')
        };
        return backdrop;
    }

    function buildLangEditor() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-lang-mapping-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-lm-title"></span>' +
            '    <button class="pt-close pt-lm-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <div class="pt-hint pt-lm-hint"></div>' +
            '    <div class="pt-lm-rows"></div>' +
            '    <button class="pt-btn pt-lm-add" type="button"></button>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-lm-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-lm-confirm" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        langEditorRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-lm-title'),
            close: backdrop.querySelector('.pt-lm-close'),
            hint: backdrop.querySelector('.pt-lm-hint'),
            rows: backdrop.querySelector('.pt-lm-rows'),
            add: backdrop.querySelector('.pt-lm-add'),
            cancel: backdrop.querySelector('.pt-lm-cancel'),
            confirm: backdrop.querySelector('.pt-lm-confirm')
        };
        return backdrop;
    }

    // 在第三层追加一行「原文 ↔ 译名」成对输入框
    function addMappingRow(refs, i18n, source, target) {
        var row = document.createElement('div');
        row.className = 'pt-lm-row';
        var sIn = document.createElement('input');
        sIn.className = 'pt-input pt-lm-source';
        sIn.type = 'text';
        sIn.placeholder = tt(i18n, 'glossary.source-placeholder', 'Original term');
        sIn.value = source || '';
        var arrow = document.createElement('span');
        arrow.className = 'pt-lm-arrow';
        arrow.textContent = '→';
        var tIn = document.createElement('input');
        tIn.className = 'pt-input pt-lm-target';
        tIn.type = 'text';
        tIn.placeholder = tt(i18n, 'glossary.target-placeholder', 'Translation');
        tIn.value = target || '';
        var del = document.createElement('button');
        del.className = 'pt-btn pt-lm-del';
        del.type = 'button';
        del.textContent = '×';
        del.title = tt(i18n, 'glossary.remove-row', 'Remove');
        del.onclick = function () { row.remove(); };
        row.appendChild(sIn);
        row.appendChild(arrow);
        row.appendChild(tIn);
        row.appendChild(del);
        refs.rows.appendChild(row);
        return sIn;
    }

    /**
     * 第三层：编辑某一语言的「原文 → 译名」映射。返回 Promise：
     *  - 确定 → resolve 该语言的 [{source, target}] 列表（仅含两侧都非空的行）
     *  - 取消 → resolve null
     * @param opts { i18n, lang, pairs:[{source,target}], onToast }
     */
    function openLangMappingEditor(opts) {
        opts = opts || {};
        var i18n = opts.i18n;
        var lang = opts.lang || '';
        if (!langEditorEl) langEditorEl = buildLangEditor();
        var r = langEditorRefs;
        r.title.textContent = tt(i18n, 'glossary.lang-mapping-title', 'Mappings · {lang}', { lang: lang });
        r.hint.textContent = tt(i18n, 'glossary.lang-mapping-hint', '');
        r.add.textContent = tt(i18n, 'glossary.add-row', '+ Add mapping');
        r.cancel.textContent = tt(i18n, 'glossary.cancel', 'Cancel');
        r.confirm.textContent = tt(i18n, 'glossary.done', 'Done');
        r.rows.innerHTML = '';
        var pairs = (opts.pairs && opts.pairs.length) ? opts.pairs : [{ source: '', target: '' }];
        var firstInput = null;
        pairs.forEach(function (p) {
            var inp = addMappingRow(r, i18n, p.source, p.target);
            if (!firstInput) firstInput = inp;
        });

        langEditorEl.classList.add('open');
        setTimeout(function () { if (firstInput) firstInput.focus(); }, 30);

        return new Promise(function (resolve) {
            function cleanup(result) {
                langEditorEl.classList.remove('open');
                r.close.onclick = null;
                r.cancel.onclick = null;
                r.confirm.onclick = null;
                r.add.onclick = null;
                r.backdrop.onclick = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            function collect() {
                var out = [];
                r.rows.querySelectorAll('.pt-lm-row').forEach(function (row) {
                    var s = row.querySelector('.pt-lm-source').value.trim();
                    var t = row.querySelector('.pt-lm-target').value.trim();
                    if (s && t) out.push({ source: s, target: t });
                });
                return out;
            }
            function onKey(e) { if (e.key === 'Escape') cleanup(null); }
            r.close.onclick = function () { cleanup(null); };
            r.cancel.onclick = function () { cleanup(null); };
            r.confirm.onclick = function () { cleanup(collect()); };
            r.add.onclick = function () { var inp = addMappingRow(r, i18n, '', ''); inp.focus(); };
            r.backdrop.onclick = function (e) { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    /**
     * 第二层：编辑映射表的表名 + 语言列表（每种语言一组映射，进第三层逐条编辑）。返回 Promise：
     *  - 保存 → resolve { id, name }（创建或编辑后的表）
     *  - 删除 → resolve { deleted:true, id }
     *  - 取消 → resolve null
     * @param opts { i18n, mode:'create'|'edit', id, name, seriesId, novelId, onToast }
     */
    function openGlossaryEditor(opts) {
        opts = opts || {};
        var i18n = opts.i18n;
        var create = opts.mode === 'create';
        if (!glossaryEditorEl) glossaryEditorEl = buildGlossaryEditor();
        var r = glossaryEditorRefs;
        r.title.textContent = create
            ? tt(i18n, 'glossary.title-new', 'New term glossary')
            : tt(i18n, 'glossary.title-edit', 'Edit term glossary');
        r.nameLabel.textContent = tt(i18n, 'glossary.name-label', 'Glossary name');
        r.nameInput.placeholder = tt(i18n, 'glossary.name-placeholder', 'Glossary name');
        r.langsLabel.textContent = tt(i18n, 'glossary.langs-label', 'Languages');
        r.langsHint.textContent = tt(i18n, 'glossary.langs-hint', '');
        r.addInput.placeholder = tt(i18n, 'glossary.add-lang-placeholder', 'e.g. zh-CN');
        r.addBtn.textContent = tt(i18n, 'glossary.add-lang', '+ Add language');
        r.cancel.textContent = tt(i18n, 'glossary.cancel', 'Cancel');
        r.save.textContent = tt(i18n, 'glossary.save', 'Save');
        r.del.textContent = tt(i18n, 'glossary.delete', 'Delete');
        r.del.style.display = create ? 'none' : '';
        r.nameInput.value = opts.name || '';
        r.addInput.value = '';

        // 工作态：langCode -> [{source, target}]，第三层编辑完回写、保存时整表落库
        var langMap = {};

        function renderLangList() {
            r.langList.innerHTML = '';
            var codes = Object.keys(langMap);
            if (!codes.length) {
                var empty = document.createElement('div');
                empty.className = 'pt-hint pt-glang-empty';
                empty.textContent = tt(i18n, 'glossary.langs-empty',
                    'No mappings yet. Add a target language to start.');
                r.langList.appendChild(empty);
                return;
            }
            codes.forEach(function (code) {
                var item = document.createElement('div');
                item.className = 'pt-glang-item';
                var label = document.createElement('span');
                label.className = 'pt-glang-name';
                label.textContent = code + ' · '
                    + tt(i18n, 'glossary.term-count', '{n} terms', { n: langMap[code].length });
                var editBtn = document.createElement('button');
                editBtn.className = 'pt-btn pt-glang-edit';
                editBtn.type = 'button';
                editBtn.textContent = tt(i18n, 'glossary.edit-lang', 'Edit');
                editBtn.onclick = function () { editLang(code); };
                var rmBtn = document.createElement('button');
                rmBtn.className = 'pt-btn pt-btn-danger pt-glang-remove';
                rmBtn.type = 'button';
                rmBtn.textContent = tt(i18n, 'glossary.remove-lang', 'Remove');
                rmBtn.onclick = function () { delete langMap[code]; renderLangList(); };
                item.appendChild(label);
                item.appendChild(editBtn);
                item.appendChild(rmBtn);
                r.langList.appendChild(item);
            });
        }

        function editLang(code) {
            openLangMappingEditor({
                i18n: i18n, lang: code, pairs: langMap[code] || [], onToast: opts.onToast
            }).then(function (result) {
                if (result == null) return; // 取消：保持原样
                if (!result.length) { delete langMap[code]; }
                else { langMap[code] = result; }
                renderLangList();
            });
        }

        function addLang() {
            var code = r.addInput.value.trim();
            if (!code) { r.addInput.focus(); return; }
            if (!langMap[code]) langMap[code] = [];
            r.addInput.value = '';
            renderLangList();
            editLang(code);
        }

        renderLangList();
        // 编辑模式先拉取既有条目并按语言分组
        if (!create && opts.id != null) {
            glossaryGet(opts.id).then(function (detail) {
                if (detail) {
                    r.nameInput.value = detail.name || r.nameInput.value;
                    (detail.entries || []).forEach(function (e) {
                        var code = (e.lang || '').trim();
                        if (!code) return;
                        if (!langMap[code]) langMap[code] = [];
                        langMap[code].push({ source: e.source, target: e.target });
                    });
                    renderLangList();
                }
            }).catch(function () {
                if (opts.onToast) opts.onToast(tt(i18n, 'glossary.load-failed', 'Failed to load glossary'), 'error');
            });
        }

        glossaryEditorEl.classList.add('open');
        setTimeout(function () { r.nameInput.focus(); }, 30);

        return new Promise(function (resolve) {
            function cleanup(result) {
                glossaryEditorEl.classList.remove('open');
                r.close.onclick = null;
                r.cancel.onclick = null;
                r.save.onclick = null;
                r.del.onclick = null;
                r.addBtn.onclick = null;
                r.backdrop.onclick = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            function flattenEntries() {
                var entries = [];
                Object.keys(langMap).forEach(function (code) {
                    (langMap[code] || []).forEach(function (p) {
                        var s = (p.source || '').trim();
                        var t = (p.target || '').trim();
                        if (s && t) entries.push({ source: s, lang: code, target: t });
                    });
                });
                return entries;
            }
            async function doSave() {
                var name = r.nameInput.value.trim();
                if (!name) { r.nameInput.focus(); return; }
                var entries = flattenEntries();
                r.save.disabled = true;
                try {
                    var id = opts.id;
                    if (create) {
                        var created = await glossaryCreate({
                            name: name,
                            seriesId: opts.seriesId != null ? opts.seriesId : null,
                            novelId: opts.novelId != null ? opts.novelId : null
                        });
                        id = created.id;
                    } else {
                        await glossaryRename(id, name);
                    }
                    await glossarySaveEntries(id, entries);
                    if (opts.onToast) opts.onToast(tt(i18n, 'glossary.saved', 'Glossary saved'), 'success');
                    cleanup({ id: id, name: name });
                } catch (e) {
                    if (opts.onToast) {
                        opts.onToast(tt(i18n, 'glossary.save-failed', 'Save failed: {message}',
                            { message: String(e && e.message ? e.message : e) }), 'error');
                    }
                } finally {
                    r.save.disabled = false;
                }
            }
            async function doDelete() {
                if (create || opts.id == null) { cleanup(null); return; }
                if (!window.confirm(tt(i18n, 'glossary.confirm-delete',
                    'Delete this glossary and all its term mappings? This cannot be undone.'))) {
                    return;
                }
                r.del.disabled = true;
                try {
                    await glossaryDelete(opts.id);
                    if (opts.onToast) opts.onToast(tt(i18n, 'glossary.deleted', 'Glossary deleted'), 'success');
                    cleanup({ deleted: true, id: opts.id });
                } catch (e) {
                    if (opts.onToast) {
                        opts.onToast(tt(i18n, 'glossary.save-failed', 'Save failed: {message}',
                            { message: String(e && e.message ? e.message : e) }), 'error');
                    }
                } finally {
                    r.del.disabled = false;
                }
            }
            // 第三层打开时由它自己处理 Escape；这里让出，避免一次 Escape 连关两层
            function onKey(e) {
                if (langEditorEl && langEditorEl.classList.contains('open')) return;
                if (e.key === 'Escape') cleanup(null);
                else if (e.key === 'Enter' && document.activeElement === r.addInput) addLang();
            }
            r.close.onclick = function () { cleanup(null); };
            r.cancel.onclick = function () { cleanup(null); };
            r.save.onclick = doSave;
            r.del.onclick = doDelete;
            r.addBtn.onclick = addLang;
            r.backdrop.onclick = function (e) { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    // ── 翻译弹窗（单例，懒创建）─────────────────────────────────────────────────
    var dialogEl = null;
    var dialogRefs = null;
    // 当前弹窗的映射表上下文：绑定作品 + 默认表信息 + 已有表列表
    var glossaryCtx = { novelId: null, seriesId: null, def: null, list: [] };

    var OPT_NONE = '';
    var OPT_DEFAULT = '__default__';
    var OPT_NEW = '__new__';

    function buildDialog() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title"></span>' +
            '    <button class="pt-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-lang-label"></span>' +
            '      <input class="pt-input pt-lang-input" type="text" maxlength="60">' +
            '    </label>' +
            '    <div class="pt-hint pt-lang-hint"></div>' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-seg-label"></span>' +
            '      <input class="pt-input pt-seg-input" type="number" min="0" step="500" value="0">' +
            '    </label>' +
            '    <div class="pt-hint pt-seg-hint"></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-existing-label"></span>' +
            '      <div class="pt-radio-row">' +
            '        <label class="pt-radio"><input type="radio" name="ptOverwrite" value="overwrite"><span class="pt-ow-label"></span></label>' +
            '        <label class="pt-radio"><input type="radio" name="ptOverwrite" value="skip"><span class="pt-sk-label"></span></label>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-scope-label"></span>' +
            '      <div class="pt-radio-row">' +
            '        <label class="pt-radio"><input type="checkbox" class="pt-scope-body" checked><span class="pt-scope-body-label"></span></label>' +
            '        <label class="pt-radio"><input type="checkbox" class="pt-scope-title" checked><span class="pt-scope-title-label"></span></label>' +
            '        <label class="pt-radio"><input type="checkbox" class="pt-scope-description" checked><span class="pt-scope-description-label"></span></label>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-scope-hint"></div>' +
            '    <div class="pt-hint pt-error pt-scope-error" hidden></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-glossary-label"></span>' +
            '      <div class="pt-glossary-row">' +
            '        <select class="pt-input pt-glossary-select"></select>' +
            '        <button class="pt-btn pt-glossary-edit" type="button"></button>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-glossary-hint"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-confirm" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        dialogRefs = {
            backdrop: backdrop,
            modal: backdrop.querySelector('.pt-modal'),
            title: backdrop.querySelector('.pt-title'),
            close: backdrop.querySelector('.pt-close'),
            langLabel: backdrop.querySelector('.pt-lang-label'),
            langInput: backdrop.querySelector('.pt-lang-input'),
            langHint: backdrop.querySelector('.pt-lang-hint'),
            segLabel: backdrop.querySelector('.pt-seg-label'),
            segInput: backdrop.querySelector('.pt-seg-input'),
            segHint: backdrop.querySelector('.pt-seg-hint'),
            existingLabel: backdrop.querySelector('.pt-existing-label'),
            owLabel: backdrop.querySelector('.pt-ow-label'),
            skLabel: backdrop.querySelector('.pt-sk-label'),
            scopeLabel: backdrop.querySelector('.pt-scope-label'),
            scopeBody: backdrop.querySelector('.pt-scope-body'),
            scopeTitle: backdrop.querySelector('.pt-scope-title'),
            scopeDescription: backdrop.querySelector('.pt-scope-description'),
            scopeBodyLabel: backdrop.querySelector('.pt-scope-body-label'),
            scopeTitleLabel: backdrop.querySelector('.pt-scope-title-label'),
            scopeDescriptionLabel: backdrop.querySelector('.pt-scope-description-label'),
            scopeHint: backdrop.querySelector('.pt-scope-hint'),
            scopeError: backdrop.querySelector('.pt-scope-error'),
            glossaryLabel: backdrop.querySelector('.pt-glossary-label'),
            glossarySelect: backdrop.querySelector('.pt-glossary-select'),
            glossaryEdit: backdrop.querySelector('.pt-glossary-edit'),
            glossaryHint: backdrop.querySelector('.pt-glossary-hint'),
            cancel: backdrop.querySelector('.pt-cancel'),
            confirm: backdrop.querySelector('.pt-confirm')
        };
        return backdrop;
    }

    // 按当前 glossaryCtx 重建映射表下拉
    function rebuildGlossarySelect(i18n, selectValue) {
        var sel = dialogRefs.glossarySelect;
        var prev = selectValue != null ? selectValue : sel.value;
        sel.innerHTML = '';
        var none = document.createElement('option');
        none.value = OPT_NONE;
        none.textContent = tt(i18n, 'dialog.glossary-none', "Don't use");
        sel.appendChild(none);

        var def = glossaryCtx.def;
        var defaultId = def && def.id != null ? def.id : null;
        if (def) {
            var defOpt = document.createElement('option');
            defOpt.value = OPT_DEFAULT;
            var defName = def.name || '';
            defOpt.textContent = defName + ' ' + tt(i18n, 'dialog.glossary-default-suffix', '(default)');
            sel.appendChild(defOpt);
        }
        (glossaryCtx.list || []).forEach(function (g) {
            if (defaultId != null && g.id === defaultId) return; // 默认表已单列
            var o = document.createElement('option');
            o.value = String(g.id);
            o.textContent = g.name || ('#' + g.id);
            sel.appendChild(o);
        });
        var newOpt = document.createElement('option');
        newOpt.value = OPT_NEW;
        newOpt.textContent = tt(i18n, 'dialog.glossary-new', '+ New glossary');
        sel.appendChild(newOpt);

        // 选定值：优先沿用 prev（若仍存在），否则默认表，否则不使用
        var values = Array.prototype.map.call(sel.options, function (o) { return o.value; });
        if (prev != null && values.indexOf(prev) !== -1 && prev !== OPT_NEW) {
            sel.value = prev;
        } else {
            sel.value = def ? OPT_DEFAULT : OPT_NONE;
        }
    }

    async function loadGlossaryContext(i18n, opts) {
        glossaryCtx = { novelId: opts.novelId || null, seriesId: opts.seriesId || null, def: null, list: [] };
        try {
            var defPromise = opts.novelId
                ? glossaryNovelDefault(opts.novelId)
                : (opts.seriesId ? glossarySeriesDefault(opts.seriesId) : Promise.resolve(null));
            var results = await Promise.all([defPromise, glossaryListAll()]);
            glossaryCtx.def = results[0] || null;
            glossaryCtx.list = results[1] || [];
        } catch (_) {
            // 加载失败：保持空上下文，下拉仅有「不使用 / 新建」
        }
        // 打开弹窗时默认选中该作品的默认映射表（存在时），否则「不使用」。
        // 不能依赖 sel.value：清空后的下拉 value 恰为 ''，会被误判为用户选了「不使用」。
        rebuildGlossarySelect(i18n, glossaryCtx.def ? OPT_DEFAULT : OPT_NONE);
    }

    /**
     * 打开翻译弹窗。返回 Promise，确认时 resolve { targetLanguage, segmentSize, overwrite, glossaryId }，取消时 resolve null。
     * @param opts { i18n, series:boolean, novelId, seriesId, onToast }
     */
    function openDialog(opts) {
        opts = opts || {};
        var i18n = opts.i18n;
        var series = !!opts.series;
        var onToast = typeof opts.onToast === 'function' ? opts.onToast : function () {};
        if (!dialogEl) {
            dialogEl = buildDialog();
        }
        var r = dialogRefs;
        r.title.textContent = series
            ? tt(i18n, 'dialog.title-series', 'Translate whole series')
            : tt(i18n, 'dialog.title', 'AI Translate');
        r.langLabel.textContent = tt(i18n, 'dialog.language-label', 'Target language');
        r.langHint.textContent = tt(i18n, 'dialog.language-hint', '');
        r.segLabel.textContent = tt(i18n, 'dialog.segment-label', 'Segment size');
        r.segHint.textContent = tt(i18n, 'dialog.segment-hint', '');
        r.existingLabel.textContent = tt(i18n, 'dialog.existing-label', 'When already translated');
        r.owLabel.textContent = tt(i18n, 'dialog.existing-overwrite', 'Overwrite');
        r.skLabel.textContent = tt(i18n, 'dialog.existing-skip', 'Skip');
        r.scopeLabel.textContent = tt(i18n, 'dialog.scope-label', 'Translate');
        r.scopeBodyLabel.textContent = tt(i18n, 'dialog.scope-body', 'Body');
        r.scopeTitleLabel.textContent = tt(i18n, 'dialog.scope-title', 'Title');
        r.scopeDescriptionLabel.textContent = tt(i18n, 'dialog.scope-description', 'Description');
        r.scopeHint.textContent = tt(i18n, 'dialog.scope-hint', '');
        // 每次打开重置三项全选，避免上次会话留痕影响下一次；同时清空验证错误
        r.scopeBody.checked = true;
        r.scopeTitle.checked = true;
        r.scopeDescription.checked = true;
        r.scopeError.hidden = true;
        r.scopeError.textContent = '';
        // 用户勾选任意一项后立即清除验证错误，避免红字与已勾选状态长期共存。
        [r.scopeBody, r.scopeTitle, r.scopeDescription].forEach(function (cb) {
            cb.onchange = function () {
                if (cb.checked) {
                    r.scopeError.hidden = true;
                    r.scopeError.textContent = '';
                }
            };
        });
        r.glossaryLabel.textContent = tt(i18n, 'dialog.glossary-label', 'Term glossary');
        r.glossaryEdit.textContent = tt(i18n, 'dialog.glossary-edit', 'Edit');
        r.glossaryHint.textContent = tt(i18n, 'dialog.glossary-hint', '');
        r.cancel.textContent = tt(i18n, 'dialog.cancel', 'Cancel');
        r.confirm.textContent = series
            ? tt(i18n, 'dialog.confirm-series', 'Translate series')
            : tt(i18n, 'dialog.confirm', 'Start translating');
        r.langInput.value = tt(i18n, 'dialog.language-default', 'english');
        r.segInput.value = '0';
        // 默认：系列批量倾向「跳过已译」，单作品倾向「覆盖」
        var defaultMode = series ? 'skip' : 'overwrite';
        var radios = r.modal.querySelectorAll('input[name="ptOverwrite"]');
        radios.forEach(function (radio) { radio.checked = radio.value === defaultMode; });

        // 映射表下拉：先占位，再异步加载默认表 + 列表
        r.glossarySelect.innerHTML = '';
        loadGlossaryContext(i18n, opts);

        dialogEl.classList.add('open');
        setTimeout(function () { r.langInput.focus(); r.langInput.select(); }, 30);

        return new Promise(function (resolve) {
            var lastGlossaryValue = OPT_DEFAULT;
            function cleanup(result) {
                dialogEl.classList.remove('open');
                r.close.onclick = null;
                r.cancel.onclick = null;
                r.confirm.onclick = null;
                r.backdrop.onclick = null;
                r.glossaryEdit.onclick = null;
                r.glossarySelect.onchange = null;
                r.scopeBody.onchange = null;
                r.scopeTitle.onchange = null;
                r.scopeDescription.onchange = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            // 解析下拉当前选择对应的 glossaryId（默认表未创建时按需创建），返回 number|null
            async function resolveGlossaryId() {
                var v = r.glossarySelect.value;
                if (v === OPT_NONE || v === OPT_NEW) return null;
                if (v === OPT_DEFAULT) {
                    var def = glossaryCtx.def;
                    if (!def) return null;
                    if (def.id != null) return def.id;
                    // 默认表尚未创建：按需创建（让 AI 新名词有处可并入）
                    var created = await glossaryCreate({
                        name: def.name, seriesId: def.seriesId != null ? def.seriesId : null,
                        novelId: def.novelId != null ? def.novelId : null
                    });
                    def.id = created.id;
                    return created.id;
                }
                var n = Number(v);
                return Number.isFinite(n) ? n : null;
            }
            async function confirmChoice() {
                var lang = r.langInput.value.trim();
                if (!lang) { r.langInput.focus(); return; }
                var seg = parseInt(r.segInput.value, 10);
                if (!Number.isFinite(seg) || seg < 0) seg = 0;
                var modeEl = r.modal.querySelector('input[name="ptOverwrite"]:checked');
                var overwrite = !modeEl || modeEl.value === 'overwrite';
                var translateBody = !!r.scopeBody.checked;
                var translateTitle = !!r.scopeTitle.checked;
                var translateDescription = !!r.scopeDescription.checked;
                if (!translateBody && !translateTitle && !translateDescription) {
                    // 弹窗内联错误：放在勾选项下方，跨页面（pixiv-novel / pixiv-series）都可见，
                    // 不依赖宿主页有没有 toast 容器。
                    r.scopeError.textContent = tt(i18n, 'dialog.scope-required',
                        'Select at least one of body, title or description to translate');
                    r.scopeError.hidden = false;
                    return;
                }
                r.confirm.disabled = true;
                var glossaryId;
                try {
                    glossaryId = await resolveGlossaryId();
                } catch (e) {
                    onToast(tt(i18n, 'glossary.save-failed', 'Save failed: {message}',
                        { message: String(e && e.message ? e.message : e) }), 'error');
                    r.confirm.disabled = false;
                    return;
                }
                r.confirm.disabled = false;
                cleanup({
                    targetLanguage: lang, segmentSize: seg, overwrite: overwrite,
                    glossaryId: glossaryId,
                    translateBody: translateBody, translateTitle: translateTitle,
                    translateDescription: translateDescription
                });
            }
            function onKey(e) {
                // 映射表编辑层（第二/三层）打开时让出键盘，避免一次 Escape 连关多层
                if (glossaryEditorEl && glossaryEditorEl.classList.contains('open')) return;
                if (langEditorEl && langEditorEl.classList.contains('open')) return;
                if (e.key === 'Escape') cleanup(null);
                else if (e.key === 'Enter' && document.activeElement === r.langInput) confirmChoice();
            }
            // 编辑当前选中的映射表（默认表未建则进入新建模式并带绑定）
            async function editCurrent() {
                var v = r.glossarySelect.value;
                var editorOpts = { i18n: i18n, onToast: onToast };
                if (v === OPT_DEFAULT) {
                    var def = glossaryCtx.def;
                    if (def && def.id != null) {
                        editorOpts.mode = 'edit'; editorOpts.id = def.id;
                    } else if (def) {
                        editorOpts.mode = 'create'; editorOpts.name = def.name;
                        editorOpts.seriesId = def.seriesId; editorOpts.novelId = def.novelId;
                    } else { return; }
                } else if (v === OPT_NONE) {
                    return;
                } else if (v === OPT_NEW) {
                    editorOpts.mode = 'create';
                } else {
                    editorOpts.mode = 'edit'; editorOpts.id = Number(v);
                }
                var result = await openGlossaryEditor(editorOpts);
                await refreshAfterEditor(result, v);
            }
            // 编辑器关闭后刷新上下文与下拉，并把选择落到合理项。
            // fallback 是编辑器被取消（result 为 null）时应回退到的下拉值。
            async function refreshAfterEditor(result, prevValue, fallback) {
                try {
                    var listed = await glossaryListAll();
                    glossaryCtx.list = listed || [];
                } catch (_) {}
                var want;
                if (result && result.deleted) {
                    if (glossaryCtx.def && glossaryCtx.def.id === result.id) glossaryCtx.def.id = null;
                    want = glossaryCtx.def ? OPT_DEFAULT : OPT_NONE;
                } else if (result && result.id != null) {
                    // 默认表的「按需创建」：回填 def.id 并保持选中默认；其余情况选中该表
                    if (prevValue === OPT_DEFAULT && glossaryCtx.def && glossaryCtx.def.id == null) {
                        glossaryCtx.def.id = result.id;
                        want = OPT_DEFAULT;
                    } else if (prevValue === OPT_DEFAULT) {
                        want = OPT_DEFAULT;
                    } else {
                        want = String(result.id);
                    }
                } else {
                    // 取消：回退到指定值（或维持原选择）
                    want = fallback != null ? fallback : prevValue;
                }
                rebuildGlossarySelect(i18n, want);
                lastGlossaryValue = r.glossarySelect.value;
            }
            function onGlossaryChange() {
                var v = r.glossarySelect.value;
                if (v === OPT_NEW) {
                    // 选「新建」立即进入新建编辑器；取消则回退到上次选择
                    openGlossaryEditor({ i18n: i18n, mode: 'create', onToast: onToast })
                        .then(function (result) { return refreshAfterEditor(result, OPT_NEW, lastGlossaryValue); });
                } else {
                    lastGlossaryValue = v;
                }
            }
            r.close.onclick = function () { cleanup(null); };
            r.cancel.onclick = function () { cleanup(null); };
            r.confirm.onclick = confirmChoice;
            r.glossaryEdit.onclick = editCurrent;
            r.glossarySelect.onchange = onGlossaryChange;
            r.backdrop.onclick = function (e) { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    // ── 后端接口 ─────────────────────────────────────────────────────────────────

    // 文本模型（LLM）是否已配置：admin-only 的 /api/admin/ai/status（纯配置检查、不触网）。结果按进程缓存，
    // 供小说 / 系列详情页决定是否展示「AI 翻译」入口——未配置时不展示。非管理员 / 探测失败一律按未配置处理。
    var aiConfiguredPromise = null;
    function isAiConfigured() {
        if (!aiConfiguredPromise) {
            aiConfiguredPromise = fetch('/api/admin/ai/status', { credentials: 'same-origin' })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (d) { return !!(d && d.configured); })
                .catch(function () { return false; });
        }
        return aiConfiguredPromise;
    }

    async function translateNovel(novelId, opts) {
        opts = opts || {};
        // 翻译范围：未显式给出时默认全部 true（兼容旧调用方与外部脚本）
        var translateBody = opts.translateBody == null ? true : !!opts.translateBody;
        var translateTitle = opts.translateTitle == null ? true : !!opts.translateTitle;
        var translateDescription = opts.translateDescription == null ? true : !!opts.translateDescription;
        var fetchInit = {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({
                targetLanguage: opts.targetLanguage,
                segmentSize: opts.segmentSize == null ? 0 : opts.segmentSize,
                overwrite: !!opts.overwrite,
                langHint: opts.langHint || null,
                glossaryId: opts.glossaryId == null ? null : opts.glossaryId,
                translateBody: translateBody,
                translateTitle: translateTitle,
                translateDescription: translateDescription
            })
        };
        if (opts.signal) fetchInit.signal = opts.signal;
        var res = await fetch('/api/gallery/novel/' + encodeURIComponent(novelId) + '/translate', fetchInit);
        if (!res.ok) {
            var msg = 'HTTP ' + res.status;
            try {
                var j = await res.json();
                if (j && (j.error || j.message)) msg = j.error || j.message;
            } catch (_) {}
            throw new Error(msg);
        }
        return res.json();
    }

    async function mergeSeriesLang(seriesId, langCode, format) {
        var params = new URLSearchParams();
        params.set('format', format || 'epub');
        if (langCode) params.set('lang', langCode);
        var res = await fetch('/api/gallery/novel/series/' + encodeURIComponent(seriesId)
            + '/merge?' + params.toString(), { method: 'POST', credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        return res.json();
    }

    // 翻译某系列的系列名 / 系列简介为目标语言（admin only）。best-effort：失败仅返回 null，不抛错。
    // 传 glossaryId 时与正文翻译共用同一张映射表，保证系列名与已译章节标题的术语一致。
    // translateTitle / translateDescription 默认 true；两者全 false 时跳过本次调用直接返回 null。
    async function translateSeriesTitle(seriesId, targetLanguage, langHint, glossaryId,
                                        translateTitle, translateDescription) {
        var doTitle = translateTitle == null ? true : !!translateTitle;
        var doDescription = translateDescription == null ? true : !!translateDescription;
        if (!doTitle && !doDescription) return null;
        try {
            var res = await fetch('/api/gallery/novel/series/' + encodeURIComponent(seriesId)
                + '/translate-title', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    targetLanguage: targetLanguage,
                    langHint: langHint || null,
                    glossaryId: (glossaryId == null ? null : glossaryId),
                    translateTitle: doTitle,
                    translateDescription: doDescription
                })
            });
            if (!res.ok) return null;
            var data = await res.json();
            return (data && data.langCode) ? data : null;
        } catch (_) {
            return null;
        }
    }

    async function fetchSeriesNovelIds(seriesId) {
        var res = await fetch('/api/gallery/novel/series/' + encodeURIComponent(seriesId) + '/novel-ids',
            { credentials: 'same-origin' });
        if (!res.ok) throw new Error('HTTP ' + res.status);
        var data = await res.json();
        return (data && data.novelIds) || [];
    }

    // 把用户自由文本（「简体中文」/「english」）解析为规范 BCP-47 代码（zh-CN / en-US）。
    // 用于系列批量翻译前预解析，使首章也能凭 langHint 走 DB 跳过。失败返回空字符串。
    async function probeLangCode(targetLanguage, opts) {
        opts = opts || {};
        var fetchInit = {
            method: 'POST',
            credentials: 'same-origin',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ targetLanguage: targetLanguage || '' })
        };
        if (opts.signal) fetchInit.signal = opts.signal;
        var res = await fetch('/api/gallery/novel/translate-lang-probe', fetchInit);
        if (!res.ok) return '';
        try {
            var data = await res.json();
            return (data && data.valid && data.code) ? String(data.code).trim() : '';
        } catch (_) {
            return '';
        }
    }

    // ── 翻译进度弹窗（单作品 / 系列共用） ────────────────────────────────────────────
    // 设计：同一时刻只允许一个 activeJob；翻译按钮在 job 进行中再次点击 = 重新弹出当前进度。
    // 「隐藏」只关闭弹窗、保留任务；「取消」abort 当前 fetch（后端可能仍写入 DB，无法拦截）。
    // 系列模式下取消后已完成章节仍触发该语言合订。

    var progressEl = null;
    var progressRefs = null;
    var progressTimerId = null;
    var activeJob = null;

    function buildProgress() {
        var backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-progress-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-progress-title"></span>' +
            '  </div>' +
            '  <div class="pt-body pt-progress-body">' +
            '    <div class="pt-progress-status">' +
            '      <span class="pt-progress-spinner"></span>' +
            '      <span class="pt-progress-status-text"></span>' +
            '    </div>' +
            '    <div class="pt-progress-sub pt-progress-sub-text"></div>' +
            '    <div class="pt-progress-stats pt-progress-stats-text"></div>' +
            '    <div class="pt-progress-warnings"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-progress-hide" type="button"></button>' +
            '    <button class="pt-btn pt-btn-danger pt-progress-cancel" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        progressRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-progress-title'),
            statusText: backdrop.querySelector('.pt-progress-status-text'),
            subText: backdrop.querySelector('.pt-progress-sub-text'),
            statsText: backdrop.querySelector('.pt-progress-stats-text'),
            warnings: backdrop.querySelector('.pt-progress-warnings'),
            hideBtn: backdrop.querySelector('.pt-progress-hide'),
            cancelBtn: backdrop.querySelector('.pt-progress-cancel')
        };
        return backdrop;
    }

    function elapsedSec(startedAt) {
        return Math.max(0, Math.floor((Date.now() - startedAt) / 1000));
    }

    function renderProgressStatus() {
        if (!progressRefs || !activeJob) return;
        var st = activeJob.state;
        var i18n = st.i18n;
        var elapsed = elapsedSec(st.phaseStartedAt);
        if (st.phase === 'merging') {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-merging',
                'Generating the merged volume… ({elapsed}s)', { elapsed: elapsed });
        } else if (st.phase === 'resolving') {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-resolving',
                'Resolving language code… ({elapsed}s)', { elapsed: elapsed });
        } else {
            progressRefs.statusText.textContent = tt(i18n, 'progress.status-running',
                'AI model translating… ({elapsed}s)', { elapsed: elapsed });
        }
    }

    function renderProgressAll() {
        if (!progressRefs || !activeJob) return;
        var st = activeJob.state;
        var i18n = st.i18n;
        progressRefs.title.textContent = st.title;
        progressRefs.hideBtn.textContent = tt(i18n, 'progress.btn-hide', 'Hide');
        progressRefs.cancelBtn.textContent = tt(i18n, 'progress.btn-cancel', 'Cancel');
        renderProgressStatus();
        if (st.type === 'series' && st.phase === 'translating') {
            progressRefs.subText.textContent = tt(i18n, 'progress.series-progress',
                'Translating chapter {done} / {total}',
                { done: st.currentIndex + 1, total: st.total });
            progressRefs.subText.style.display = '';
        } else {
            progressRefs.subText.style.display = 'none';
        }
        if (st.type === 'series') {
            progressRefs.statsText.textContent = tt(i18n, 'progress.series-stats',
                '{ok} done {skipped} skipped {failed} failed',
                { ok: st.ok, skipped: st.skipped, failed: st.failed });
            progressRefs.statsText.style.display = '';
        } else {
            progressRefs.statsText.style.display = 'none';
        }
        progressRefs.warnings.innerHTML = '';
        if (st.warnings && st.warnings.length) {
            st.warnings.forEach(function (text) {
                var div = document.createElement('div');
                div.className = 'pt-progress-warn';
                div.textContent = text;
                progressRefs.warnings.appendChild(div);
            });
            progressRefs.warnings.style.display = '';
        } else {
            progressRefs.warnings.style.display = 'none';
        }
        progressRefs.cancelBtn.disabled = !!st.cancelDisabled;
    }

    function showProgressDialog() {
        if (!activeJob) return;
        if (!progressEl) progressEl = buildProgress();
        progressEl.classList.add('open');
        renderProgressAll();
        if (progressTimerId == null) {
            progressTimerId = setInterval(function () {
                if (!activeJob) return;
                if (progressEl && progressEl.classList.contains('open')) {
                    renderProgressStatus();
                }
            }, 500);
        }
    }

    function hideProgressDialog() {
        if (progressEl) progressEl.classList.remove('open');
    }

    function endProgressJob() {
        hideProgressDialog();
        if (progressTimerId != null) {
            clearInterval(progressTimerId);
            progressTimerId = null;
        }
        activeJob = null;
    }

    function bindProgressButtons(onHide, onCancel) {
        if (!progressRefs) return;
        progressRefs.hideBtn.onclick = onHide || null;
        progressRefs.cancelBtn.onclick = onCancel || null;
    }

    async function runSingleNovel(opts) {
        opts = opts || {};
        if (activeJob) { showProgressDialog(); return null; }
        var i18n = opts.i18n;
        var controller = new AbortController();
        var state = {
            type: 'single',
            i18n: i18n,
            title: tt(i18n, 'progress.title-single', 'AI translation in progress'),
            phase: 'translating',
            phaseStartedAt: Date.now(),
            warnings: []
        };
        var cancelled = false;
        activeJob = { type: 'single', state: state, show: showProgressDialog };
        // 必须先 showProgressDialog 触发 buildProgress（progressRefs 才存在），再绑定按钮
        showProgressDialog();
        bindProgressButtons(hideProgressDialog, function () {
            cancelled = true;
            state.cancelDisabled = true;
            renderProgressAll();
            controller.abort();
        });
        var result = null, error = null;
        try {
            result = await translateNovel(opts.novelId, Object.assign({}, opts.choice,
                { signal: controller.signal }));
        } catch (e) {
            error = e;
        }

        // 翻译成功且该小说属于某个系列：重生该语言变体合订本（best-effort）。
        // 仅在新译成功（OK）时触发，SKIPPED 表示该语言译文早已落库、合订本应已是最新。
        var mergeFailed = null;
        if (!cancelled && !error && result && opts.seriesId
                && result.status === 'OK' && result.langCode) {
            // 合订前顺手补齐该系列在此语言下的系列名 / 系列简介翻译（best-effort），共用本次选定的映射表与勾选范围
            try { await translateSeriesTitle(opts.seriesId, opts.choice.targetLanguage,
                    result.langCode, opts.choice.glossaryId,
                    opts.choice.translateTitle, opts.choice.translateDescription); }
            catch (_) {}
            state.phase = 'merging';
            state.phaseStartedAt = Date.now();
            state.cancelDisabled = true;
            renderProgressAll();
            try {
                await mergeSeriesLang(opts.seriesId, result.langCode, 'epub');
            } catch (e) {
                mergeFailed = e;
            }
        }

        endProgressJob();
        if (cancelled) return { cancelled: true };
        if (error) return { error: error };
        return { result: result, mergeFailed: mergeFailed };
    }

    async function runSeries(opts) {
        opts = opts || {};
        if (activeJob) { showProgressDialog(); return null; }
        var i18n = opts.i18n;
        var controller = new AbortController();
        var state = {
            type: 'series',
            i18n: i18n,
            title: tt(i18n, 'progress.title-series', 'Series translation in progress'),
            phase: 'translating',
            phaseStartedAt: Date.now(),
            currentIndex: 0,
            total: 0,
            ok: 0, skipped: 0, failed: 0,
            warnings: [
                tt(i18n, 'progress.warn-do-not-close',
                    'Do not close or refresh this tab; the progress will be interrupted.'),
                tt(i18n, 'progress.warn-long',
                    'Serial translation keeps glossary consistency; the whole series may take minutes.'),
                tt(i18n, 'progress.warn-cancel',
                    'Cancel keeps already-translated chapters and still triggers the merged volume.')
            ]
        };
        var cancelled = false;
        activeJob = { type: 'series', state: state, show: showProgressDialog };
        // 必须先 showProgressDialog 触发 buildProgress（progressRefs 才存在），再绑定按钮
        showProgressDialog();
        bindProgressButtons(hideProgressDialog, function () {
            cancelled = true;
            state.cancelDisabled = true;
            renderProgressAll();
            controller.abort();
        });

        var ids;
        try {
            ids = await fetchSeriesNovelIds(opts.seriesId);
        } catch (e) {
            endProgressJob();
            return { error: e };
        }
        if (!ids.length) {
            endProgressJob();
            return { empty: true };
        }
        state.total = ids.length;
        renderProgressAll();

        // 跳过模式下：先用一次小型 AI 探测把用户自由文本目标语言转为规范 BCP-47 代码，
        // 使首章也能凭 langHint 走 DB 跳过、不必为识别语言再发一次完整翻译请求。
        // 覆盖模式下每章都必发 AI 调用，无需预探测。
        var langCode = null;
        var invalid = false;
        if (!opts.choice.overwrite && !cancelled) {
            state.phase = 'resolving';
            state.phaseStartedAt = Date.now();
            renderProgressAll();
            try {
                var probed = await probeLangCode(opts.choice.targetLanguage,
                    { signal: controller.signal });
                if (probed) langCode = probed;
            } catch (e) {
                // 探测失败 / 取消：忽略，回退到无 langHint 的行为
                if (cancelled || (e && e.name === 'AbortError')) {
                    // fall through to loop; cancelled flag handles break
                }
            }
            state.phase = 'translating';
            renderProgressAll();
        }

        for (var i = 0; i < ids.length; i++) {
            if (cancelled) break;
            state.currentIndex = i;
            state.phaseStartedAt = Date.now();
            renderProgressAll();
            try {
                var resp = await translateNovel(ids[i], Object.assign({}, opts.choice,
                    { langHint: langCode, signal: controller.signal }));
                if (resp.status === 'INVALID_LANGUAGE') { invalid = true; break; }
                if (resp.langCode && !langCode) langCode = resp.langCode;
                if (resp.status === 'OK') state.ok++;
                else if (resp.status === 'SKIPPED') state.skipped++;
                else state.failed++;
            } catch (e) {
                if (cancelled || (e && e.name === 'AbortError')) break;
                state.failed++;
            }
            renderProgressAll();
        }

        var mergeFailed = null;
        if (!invalid && langCode && (state.ok > 0 || state.skipped > 0)) {
            // 合订前先把系列名 / 系列简介翻译好（best-effort），共用本次选定的映射表与勾选范围
            try { await translateSeriesTitle(opts.seriesId, opts.choice.targetLanguage,
                    langCode, opts.choice.glossaryId,
                    opts.choice.translateTitle, opts.choice.translateDescription); }
            catch (_) {}
            state.phase = 'merging';
            state.phaseStartedAt = Date.now();
            state.cancelDisabled = true;
            renderProgressAll();
            try {
                await mergeSeriesLang(opts.seriesId, langCode, 'epub');
            } catch (e) {
                mergeFailed = e;
            }
        }
        endProgressJob();
        return {
            cancelled: cancelled,
            invalid: invalid,
            ok: state.ok, skipped: state.skipped, failed: state.failed,
            langCode: langCode,
            mergeFailed: mergeFailed
        };
    }

    function hasActiveJob() { return !!activeJob; }
    function showActiveJob() { if (activeJob) showProgressDialog(); }

    global.PixivTranslate = {
        openDialog: openDialog,
        isAiConfigured: isAiConfigured,
        translateNovel: translateNovel,
        translateSeriesTitle: translateSeriesTitle,
        mergeSeriesLang: mergeSeriesLang,
        fetchSeriesNovelIds: fetchSeriesNovelIds,
        runSingleNovel: runSingleNovel,
        runSeries: runSeries,
        hasActiveJob: hasActiveJob,
        showActiveJob: showActiveJob,
        STATUS_OK: 'OK',
        STATUS_SKIPPED: 'SKIPPED',
        STATUS_INVALID_LANGUAGE: 'INVALID_LANGUAGE'
    };

    // ── 内容语言切换器（独立于界面语言，localStorage 全局记忆）──────────────────────
    function readStoredContentLang() {
        try { return localStorage.getItem(CONTENT_LANG_STORAGE_KEY) || ''; } catch (_) { return ''; }
    }
    function writeStoredContentLang(lang) {
        try {
            if (lang) localStorage.setItem(CONTENT_LANG_STORAGE_KEY, lang);
            else localStorage.removeItem(CONTENT_LANG_STORAGE_KEY);
        } catch (_) {}
    }

    /**
     * 挂载内容语言切换器。返回控制器 { setLanguages, getValue, element }。
     * @param opts { mountPoint, i18n, languages:[code], current, onChange(langOrNull) }
     */
    function mountContentLang(opts) {
        opts = opts || {};
        var mountPoint = opts.mountPoint;
        if (!mountPoint) return null;
        var i18n = opts.i18n;
        var wrap = document.createElement('span');
        wrap.className = 'pt-lang-switch';
        wrap.title = tt(i18n, 'switcher.title', '');
        var select = document.createElement('select');
        select.className = 'pt-lang-select';
        wrap.appendChild(select);
        mountPoint.appendChild(wrap);

        var current = opts.current || '';
        var onChange = typeof opts.onChange === 'function' ? opts.onChange : function () {};

        function rebuild(languages, cur) {
            var langs = Array.isArray(languages) ? languages.slice() : [];
            select.innerHTML = '';
            var optOriginal = document.createElement('option');
            optOriginal.value = '';
            optOriginal.textContent = tt(i18n, 'switcher.original', 'Original');
            select.appendChild(optOriginal);
            langs.forEach(function (code) {
                if (!code) return;
                var o = document.createElement('option');
                o.value = code;
                o.textContent = code;
                select.appendChild(o);
            });
            // 选定值：优先入参 cur，否则当前 current（若仍可用），否则原文
            var want = (cur != null) ? cur : current;
            if (want && langs.indexOf(want) === -1) want = '';
            current = want || '';
            select.value = current;
            wrap.style.display = langs.length ? '' : 'none';
        }

        select.addEventListener('change', function () {
            current = select.value || '';
            writeStoredContentLang(current);
            onChange(current || null);
        });

        rebuild(opts.languages, opts.current);

        return {
            element: wrap,
            getValue: function () { return current || ''; },
            setLanguages: function (languages, cur) { rebuild(languages, cur); },
            relabel: function (nextI18n) {
                i18n = nextI18n || i18n;
                wrap.title = tt(i18n, 'switcher.title', '');
                var sel = select.value;
                rebuild(Array.prototype.slice.call(select.options).slice(1).map(function (o) { return o.value; }), sel);
            }
        };
    }

    global.PixivContentLang = {
        mount: mountContentLang,
        getStored: readStoredContentLang,
        setStored: writeStoredContentLang,
        STORAGE_KEY: CONTENT_LANG_STORAGE_KEY
    };
})(window);
