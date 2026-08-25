/* eslint-disable */
/** 朗读分析设置、花名册选择与旁白预设弹窗。 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules || (global.PixivNovelNarrationModules = {});
    modules.dialog = {
        install(ctx) {
            const { LS, lsGet, lsSet, state, els, t, feedbackPrompt } = ctx.core;
            const { buildBlocks } = ctx.marks;
            const { runAnalysis } = ctx.playback;
            const { openCastFor, previewInstruction, sampleLineFor } = ctx.cast;
    // ---------- 朗读分析设置弹窗（分段字数 + 花名册选择）----------
    // 体验对齐翻译弹窗的「名词映射表」下拉：不使用（纯旁白）/ 本作默认（默认）/ 其它已有花名册 / 新建花名册；
    // 「选角与音色」按钮编辑的是「当前所选花名册」（按 castId），借用别人的花名册即编辑那份共享册。
    const CAST_OPT_NONE = '';            // 不使用（纯旁白）→ castId 0（不调 LLM）
    const CAST_OPT_DEFAULT = '__default__';
    const CAST_OPT_NEW = '__new__';
    const NARRATOR_KEEP = '__keep__';    // 旁白音色「保持当前」哨兵 → 不下发 narratorPreset（旁白不变）
    let dialogEl = null;
    let dialogRefs = null;
    let castCtx = { def: null, list: [] }; // def: {castId,name,seriesId,novelId}; list: [{id,name,...}]
    let narratorPresets = null;          // [{id, instruction}]（后端固定英文画像，首次加载后缓存）
    let narratorUserTouched = false;     // 用户是否显式动过旁白音色下拉：动过则切换花名册时不再自动改写其选择

    function narratorPresetById(id) {
        return (narratorPresets || []).find((p) => p.id === id) || null;
    }
    // 默认预设 = 后端清单首项（枚举声明序，首项即 NarratorVoicePreset.DEFAULT，避免在前端硬编码 id）。
    function narratorDefaultId() {
        return (narratorPresets && narratorPresets[0]) ? narratorPresets[0].id : NARRATOR_KEEP;
    }
    async function loadNarratorPresets() {
        if (narratorPresets) return narratorPresets;
        try {
            const d = await castApi('/api/narration/narrator-presets');
            narratorPresets = (d && d.presets) || [];
        } catch { narratorPresets = []; }
        return narratorPresets;
    }
    // 旁白音色下拉：「保持当前音色」+ 各预设（label 走 i18n、value=preset.id）。
    function rebuildNarratorSelect(selectValue) {
        const sel = dialogRefs && dialogRefs.narratorSelect;
        if (!sel) return;
        const prev = selectValue != null ? selectValue : sel.value;
        sel.innerHTML = '';
        const keep = document.createElement('option');
        keep.value = NARRATOR_KEEP;
        keep.textContent = t('narration:dialog.narrator-keep', '保持当前音色');
        sel.appendChild(keep);
        (narratorPresets || []).forEach((p) => {
            const o = document.createElement('option');
            o.value = p.id;
            o.textContent = t('narration:narrator-preset.' + p.id, p.id);
            sel.appendChild(o);
        });
        const values = Array.prototype.map.call(sel.options, (o) => o.value);
        sel.value = (prev != null && values.indexOf(prev) !== -1) ? prev : NARRATOR_KEEP;
        updateNarratorPreview();
    }
    // 选中预设时在预览区显示其英文画像；「保持当前」时提示试听将用当前 / 默认旁白音色。两种情况「试听」都可点。
    function updateNarratorPreview() {
        const sel = dialogRefs && dialogRefs.narratorSelect;
        if (!sel) return;
        const p = narratorPresetById(sel.value);
        if (dialogRefs.narratorPreview) {
            dialogRefs.narratorPreview.textContent = p
                ? p.instruction
                : t('narration:dialog.narrator-keep-preview', '试听将使用本作当前 / 默认旁白音色');
            dialogRefs.narratorPreview.style.display = 'block';
        }
    }
    // 「试听」按钮当前所选项要用的旁白画像：选了预设→该预设画像；「保持当前」→当前所选花名册的旁白(id 0)画像，
    // 取不到 / 无册→默认预设画像（清单首项，即温暖女声）。
    async function resolveNarratorPreviewInstruction() {
        const p = narratorPresetById(dialogRefs.narratorSelect.value);
        if (p) return p.instruction;
        const castId = peekSelectedCastId();
        if (castId > 0) {
            try {
                const d = await castApi('/api/narration/casts/' + encodeURIComponent(castId) + '/voices');
                const narrator = ((d && d.voices) || []).find((v) => v.id === 0);
                if (narrator && narrator.controlInstruction) return narrator.controlInstruction;
            } catch {}
        }
        return (narratorPresets && narratorPresets[0]) ? narratorPresets[0].instruction : '';
    }
    // 只读地解析花名册下拉当前对应的 castId（不创建）：不使用 / 新建 / 默认未建 → 0；其它 → 该册 id。
    function peekSelectedCastId() {
        const v = dialogRefs.castSelect.value;
        if (v === CAST_OPT_NONE || v === CAST_OPT_NEW) return 0;
        if (v === CAST_OPT_DEFAULT) {
            const def = castCtx.def;
            return def && def.castId != null ? def.castId : 0;
        }
        const n = Number(v);
        return Number.isFinite(n) && n > 0 ? n : 0;
    }
    // 旁白音色默认选中跟随「当前所选花名册」：已选中**已存在**的花名册（默认册已建 / 列表里的具体册）→「保持当前」
    // （避免误改已有册的旁白；借用共享册时不殃及其它作品）；尚未创建的目标（纯旁白 / 默认册未建 / 新建）→ 预选默认
    // 预设（温和提示用户选）。随花名册下拉变化重新派生；用户一旦显式动过旁白下拉则不再自动改写其选择。
    function syncNarratorDefault() {
        if (narratorUserTouched) return;
        if (!dialogRefs || !dialogRefs.narratorSelect) return;
        rebuildNarratorSelect(peekSelectedCastId() > 0 ? NARRATOR_KEEP : narratorDefaultId());
    }
    async function loadNarratorContext() {
        await loadNarratorPresets();
        syncNarratorDefault();
    }
    // 旁白试听样例：优先脚本里旁白第一句；否则取正文首个可朗读段落；都没有时回退旁白标签。
    function sampleNarratorText() {
        const fromScript = sampleLineFor(0);
        if (fromScript) return fromScript;
        if (!state.blocks.length) state.blocks = buildBlocks();
        for (const el of state.blocks) {
            const tx = (el.textContent || '').trim();
            if (tx) return tx.slice(0, 120);
        }
        return t('narration:narrator', '旁白');
    }
    // 试听旁白音色：选了预设用其画像；「保持当前」用当前 / 默认旁白画像。生成期间先显示「生成中…」（含解析当前画像
    // 的轻量请求），再交给 previewInstruction 走两态并播放。
    async function tryNarratorVoice() {
        const btn = dialogRefs.narratorTry;
        if (!btn) return;
        btn.disabled = true;
        btn.textContent = t('narration:cast.generating', '生成中…');
        let instruction = '';
        try { instruction = await resolveNarratorPreviewInstruction(); } catch { instruction = ''; }
        if (!instruction) {
            btn.disabled = false;
            btn.textContent = t('narration:cast.preview', '试听');
            return;
        }
        await previewInstruction(btn, sampleNarratorText(), instruction);
    }

    async function castApi(url, options) {
        const res = await fetch(url, Object.assign({ credentials: 'same-origin' }, options || {}));
        if (!res.ok) {
            let msg = 'HTTP ' + res.status;
            try { const j = await res.json(); if (j && j.message) msg = j.message; } catch {}
            throw new Error(msg);
        }
        if (res.status === 204) return null;
        return res.json();
    }
    function castListAll() { return castApi('/api/narration/casts').then((d) => (d && d.casts) || []); }
    function castNovelDefault() {
        return castApi('/api/narration/casts/novel/' + encodeURIComponent(state.novelId) + '/default');
    }
    function castCreate(body) {
        return castApi('/api/narration/casts', {
            method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body)
        });
    }

    function buildDialog() {
        const backdrop = document.createElement('div');
        backdrop.className = 'pt-backdrop pt-narration-backdrop';
        backdrop.innerHTML =
            '<div class="pt-modal" role="dialog" aria-modal="true">' +
            '  <div class="pt-head">' +
            '    <span class="pt-title pt-nd-title"></span>' +
            '    <button class="pt-close pt-nd-close" type="button" aria-label="close">×</button>' +
            '  </div>' +
            '  <div class="pt-body">' +
            '    <label class="pt-field">' +
            '      <span class="pt-label pt-nd-seg-label"></span>' +
            '      <input class="pt-input pt-nd-seg-input" type="number" min="0" step="100" inputmode="numeric">' +
            '    </label>' +
            '    <div class="pt-hint pt-nd-seg-hint"></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-nd-cast-label"></span>' +
            '      <div class="pt-glossary-row">' +
            '        <select class="pt-input pt-glossary-select pt-nd-cast-select"></select>' +
            '        <button class="pt-btn pt-nd-cast-edit" type="button"></button>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-nd-cast-hint"></div>' +
            '    <div class="pt-field">' +
            '      <span class="pt-label pt-nd-narrator-label"></span>' +
            '      <div class="pt-glossary-row">' +
            '        <select class="pt-input pt-glossary-select pt-nd-narrator-select"></select>' +
            '        <button class="pt-btn pt-nd-narrator-try" type="button"></button>' +
            '      </div>' +
            '    </div>' +
            '    <div class="pt-hint pt-nd-narrator-preview"></div>' +
            '    <div class="pt-hint pt-nd-narrator-hint"></div>' +
            '  </div>' +
            '  <div class="pt-foot">' +
            '    <button class="pt-btn pt-nd-cancel" type="button"></button>' +
            '    <button class="pt-btn pt-btn-primary pt-nd-confirm" type="button"></button>' +
            '  </div>' +
            '</div>';
        document.body.appendChild(backdrop);
        dialogRefs = {
            backdrop: backdrop,
            title: backdrop.querySelector('.pt-nd-title'),
            close: backdrop.querySelector('.pt-nd-close'),
            segLabel: backdrop.querySelector('.pt-nd-seg-label'),
            segInput: backdrop.querySelector('.pt-nd-seg-input'),
            segHint: backdrop.querySelector('.pt-nd-seg-hint'),
            castLabel: backdrop.querySelector('.pt-nd-cast-label'),
            castSelect: backdrop.querySelector('.pt-nd-cast-select'),
            castEdit: backdrop.querySelector('.pt-nd-cast-edit'),
            castHint: backdrop.querySelector('.pt-nd-cast-hint'),
            narratorLabel: backdrop.querySelector('.pt-nd-narrator-label'),
            narratorSelect: backdrop.querySelector('.pt-nd-narrator-select'),
            narratorTry: backdrop.querySelector('.pt-nd-narrator-try'),
            narratorPreview: backdrop.querySelector('.pt-nd-narrator-preview'),
            narratorHint: backdrop.querySelector('.pt-nd-narrator-hint'),
            cancel: backdrop.querySelector('.pt-nd-cancel'),
            confirm: backdrop.querySelector('.pt-nd-confirm')
        };
        return backdrop;
    }

    // 按当前 castCtx 重建花名册下拉
    function rebuildCastSelect(selectValue) {
        const sel = dialogRefs.castSelect;
        const prev = selectValue != null ? selectValue : sel.value;
        sel.innerHTML = '';
        const none = document.createElement('option');
        none.value = CAST_OPT_NONE;
        none.textContent = t('narration:dialog.cast-none', '不使用（纯旁白）');
        sel.appendChild(none);
        const def = castCtx.def;
        const defaultId = def && def.castId != null ? def.castId : null;
        if (def) {
            const o = document.createElement('option');
            o.value = CAST_OPT_DEFAULT;
            o.textContent = (def.name || '') + ' ' + t('narration:dialog.cast-default-suffix', '（默认）');
            sel.appendChild(o);
        }
        (castCtx.list || []).forEach((c) => {
            if (defaultId != null && c.id === defaultId) return; // 默认册已单列
            const o = document.createElement('option');
            o.value = String(c.id);
            o.textContent = c.name || ('#' + c.id);
            sel.appendChild(o);
        });
        const newOpt = document.createElement('option');
        newOpt.value = CAST_OPT_NEW;
        newOpt.textContent = t('narration:dialog.cast-new', '＋ 新建花名册');
        sel.appendChild(newOpt);

        const values = Array.prototype.map.call(sel.options, (o) => o.value);
        if (prev != null && values.indexOf(prev) !== -1 && prev !== CAST_OPT_NEW) sel.value = prev;
        else sel.value = def ? CAST_OPT_DEFAULT : CAST_OPT_NONE;
    }

    async function loadCastContext() {
        castCtx = { def: null, list: [] };
        try {
            const [def, list] = await Promise.all([
                castNovelDefault().catch(() => null),
                castListAll().catch(() => [])
            ]);
            castCtx.def = def || null;
            castCtx.list = list || [];
        } catch {}
        // 默认选中本作默认花名册（存在时），否则「不使用」。
        rebuildCastSelect(castCtx.def ? CAST_OPT_DEFAULT : CAST_OPT_NONE);
    }

    // 解析下拉当前选择对应的 castId：不使用→0（纯旁白）；默认→默认册 id（未建则按需创建）；其它→该册 id。
    async function resolveSelectedCastId() {
        const v = dialogRefs.castSelect.value;
        if (v === CAST_OPT_NONE || v === CAST_OPT_NEW) return 0;
        if (v === CAST_OPT_DEFAULT) {
            const def = castCtx.def;
            if (!def) return 0;
            if (def.castId != null) return def.castId;
            const created = await castCreate({ name: def.name, seriesId: def.seriesId, novelId: def.novelId });
            def.castId = created.id;
            return created.id;
        }
        const n = Number(v);
        return Number.isFinite(n) && n > 0 ? n : 0;
    }

    // 打开设置弹窗。返回 Promise：确认 → { segmentSize, castId }；取消 → null。
    function openDialog(opts) {
        opts = opts || {};
        if (!dialogEl) dialogEl = buildDialog();
        const r = dialogRefs;
        r.title.textContent = t('narration:dialog.title', '朗读分析设置');
        r.segLabel.textContent = t('narration:settings.segment-size', '分段字数');
        r.segInput.placeholder = t('narration:settings.segment-whole', '整章（0）');
        r.segHint.textContent = t('narration:settings.hint', '');
        r.castLabel.textContent = t('narration:dialog.cast-label', '朗读花名册');
        r.castEdit.textContent = t('narration:dialog.cast-edit', '选角与音色');
        r.castHint.textContent = t('narration:dialog.cast-hint', '');
        r.narratorLabel.textContent = t('narration:dialog.narrator-label', '旁白音色');
        r.narratorTry.textContent = t('narration:cast.preview', '试听');
        r.narratorHint.textContent = t('narration:dialog.narrator-hint', '');
        r.cancel.textContent = t('narration:dialog.cancel', '取消');
        r.confirm.textContent = opts.reanalyze
            ? t('narration:dialog.confirm-reanalyze', '重新分析')
            : t('narration:dialog.confirm', '开始分析并播放');
        const seg = parseInt(lsGet(LS.segment, '0'), 10);
        r.segInput.value = Number.isFinite(seg) && seg >= 0 ? String(seg) : '0';
        r.castSelect.innerHTML = '';
        r.narratorSelect.innerHTML = '';
        narratorUserTouched = false;
        // 旁白音色默认选中跟随所选花名册（见 syncNarratorDefault），故在花名册上下文加载完成后再建旁白下拉。
        loadCastContext().then(loadNarratorContext);

        dialogEl.classList.add('open');
        setTimeout(() => { r.segInput.focus(); }, 30);

        return new Promise((resolve) => {
            let lastCastValue = CAST_OPT_DEFAULT;
            function cleanup(result) {
                dialogEl.classList.remove('open');
                r.close.onclick = null; r.cancel.onclick = null; r.confirm.onclick = null;
                r.backdrop.onclick = null; r.castEdit.onclick = null; r.castSelect.onchange = null;
                r.narratorSelect.onchange = null; r.narratorTry.onclick = null;
                document.removeEventListener('keydown', onKey);
                resolve(result);
            }
            function onKey(e) {
                // 选角弹窗（叠在本弹窗之上）打开时让出键盘，避免一次 Escape 连关两层
                if (els.castModal && els.castModal.classList.contains('open')) return;
                if (e.key === 'Escape') cleanup(null);
            }
            async function confirmChoice() {
                let segVal = parseInt(r.segInput.value, 10);
                if (!Number.isFinite(segVal) || segVal < 0) segVal = 0;
                r.confirm.disabled = true;
                let castId;
                try { castId = await resolveSelectedCastId(); }
                catch (e) {
                    state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
                    r.confirm.disabled = false;
                    return;
                }
                r.confirm.disabled = false;
                const narratorVal = r.narratorSelect.value;
                const narratorPreset = (narratorVal && narratorVal !== NARRATOR_KEEP) ? narratorVal : null;
                cleanup({ segmentSize: segVal, castId: castId, narratorPreset: narratorPreset });
            }
            // 编辑当前所选花名册的角色音色（不使用 / 纯旁白时无册可编辑）
            async function editSelectedCast() {
                let castId;
                try { castId = await resolveSelectedCastId(); } catch { return; }
                if (castId > 0) { rebuildCastSelect(); openCastFor(castId); }
            }
            // 用户显式改动旁白音色后，切换花名册不再自动改写它（保住用户意图，也只在用户主动选时才下发 preset）。
            function onNarratorChange() {
                narratorUserTouched = true;
                updateNarratorPreview();
            }
            async function onCastChange() {
                const v = r.castSelect.value;
                if (v === CAST_OPT_NEW) {
                    const name = await feedbackPrompt(t('narration:dialog.new-name-prompt', '新花名册名称'),
                        t('narration:dialog.new-name-default', '新花名册'));
                    if (name == null || !name.trim()) { rebuildCastSelect(lastCastValue); syncNarratorDefault(); return; }
                    castCreate({ name: name.trim(), seriesId: null, novelId: null })
                        .then((created) => castListAll().then((list) => {
                            castCtx.list = list || [];
                            rebuildCastSelect(String(created.id));
                            lastCastValue = r.castSelect.value;
                            syncNarratorDefault();
                        }))
                        .catch(() => {
                            state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
                            rebuildCastSelect(lastCastValue);
                            syncNarratorDefault();
                        });
                } else {
                    lastCastValue = v;
                    syncNarratorDefault();
                }
            }
            r.close.onclick = () => cleanup(null);
            r.cancel.onclick = () => cleanup(null);
            r.confirm.onclick = confirmChoice;
            r.castEdit.onclick = editSelectedCast;
            r.castSelect.onchange = onCastChange;
            r.narratorSelect.onchange = onNarratorChange;
            r.narratorTry.onclick = tryNarratorVoice;
            r.backdrop.onclick = (e) => { if (e.target === r.backdrop) cleanup(null); };
            document.addEventListener('keydown', onKey);
        });
    }

    // 打开设置弹窗并在确认后分析。force=true 表示「重新分析」入口（覆盖缓存、不自动播放）；
    // autoPlay=true 表示首次点播放路径（分析完自动开始播放）。
    async function openAnalysisDialog(force, autoPlay) {
        if (state.loading) return;
        const choice = await openDialog({ reanalyze: !!force });
        if (!choice) return;
        lsSet(LS.segment, String(choice.segmentSize));
        await runAnalysis(choice.segmentSize, choice.castId, !!force, !!autoPlay, choice.narratorPreset);
    }


            ctx.dialog = { openAnalysisDialog };
        }
    };
})(window);
