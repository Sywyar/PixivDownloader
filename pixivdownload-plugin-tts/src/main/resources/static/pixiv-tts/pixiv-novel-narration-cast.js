/* eslint-disable */
/** 花名册、冲突、参考音与音色编辑面板。 */
(function (global) {
    'use strict';

    const modules = global.PixivNovelNarrationModules || (global.PixivNovelNarrationModules = {});
    modules.cast = {
        install(ctx) {
            const { state, els, t, feedbackPrompt, feedbackConfirm } = ctx.core;
            const { reloadCachedScript, finish, clearCache } = ctx.playback;
    // ---------- 选角 / 冲突面板 ----------
    // 冲突触发时编辑「当前脚本所用花名册」；从设置弹窗触发时编辑「所选花名册」（借用别人的花名册即编辑那份共享册）。
    async function openCast() { return openCastFor(state.scriptCastId); }

    async function openCastFor(castId) {
        state.editCastId = castId > 0 ? castId : 0;
        renderConflicts();
        await renderCastList();
        els.castModal.classList.add('open');
    }
    function closeCast() { els.castModal.classList.remove('open'); }

    function conflictTypeLabel(type) {
        if (type === 'contradiction') return t('narration:conflict.type.contradiction', '与正文矛盾');
        if (type === 'incomplete') return t('narration:conflict.type.incomplete', '画像不完整');
        return type || '';
    }

    function renderConflicts() {
        const box = els.conflicts;
        if (!box) return;
        if (!state.conflicts.length) { box.style.display = 'none'; box.innerHTML = ''; return; }
        box.style.display = 'flex';
        box.innerHTML = '';
        state.conflicts.forEach((c, idx) => {
            const card = document.createElement('div');
            card.className = 'narration-conflict';
            const head = document.createElement('div');
            head.className = 'narration-conflict-head';
            head.innerHTML = `<span>${escapeHtml(c.name || '')}</span>`
                + `<span class="narration-conflict-type">${escapeHtml(conflictTypeLabel(c.type))}</span>`;
            const body = document.createElement('div');
            body.className = 'narration-conflict-body';
            body.innerHTML = `<div>${escapeHtml(t('narration:conflict.current', '当前画像'))}：<span class="narration-instr">${escapeHtml(c.currentInstruction || '')}</span></div>`
                + `<div>${escapeHtml(t('narration:conflict.suggestion', '建议画像'))}：<span class="narration-instr">${escapeHtml(c.suggestion || '')}</span></div>`;
            if (c.reason) body.innerHTML += `<div>${escapeHtml(c.reason)}</div>`;
            const actions = document.createElement('div');
            actions.className = 'narration-conflict-actions';
            const adopt = document.createElement('button');
            adopt.className = 'btn btn-sm';
            adopt.textContent = t('narration:conflict.adopt', '采纳建议');
            adopt.addEventListener('click', () => resolveConflict(idx, c.suggestion));
            const keep = document.createElement('button');
            keep.className = 'btn btn-sm';
            keep.textContent = t('narration:conflict.keep', '保留当前');
            keep.addEventListener('click', () => dismissConflict(idx));
            const rewrite = document.createElement('button');
            rewrite.className = 'btn btn-sm';
            rewrite.textContent = t('narration:conflict.rewrite', '改写');
            rewrite.addEventListener('click', () => rewriteConflict(idx, c));
            actions.appendChild(adopt);
            actions.appendChild(keep);
            actions.appendChild(rewrite);
            card.appendChild(head);
            card.appendChild(body);
            card.appendChild(actions);
            box.appendChild(card);
        });
    }

    async function resolveConflict(idx, instruction) {
        const c = state.conflicts[idx];
        if (!c) return;
        const ok = await saveVoice(c.characterId, instruction);
        if (ok) { state.conflicts.splice(idx, 1); renderConflicts(); renderCastList(); state.toast && state.toast(t('narration:toast.conflict-resolved', '已处理冲突'), 'success'); }
    }
    function dismissConflict(idx) {
        state.conflicts.splice(idx, 1);
        renderConflicts();
    }
    async function rewriteConflict(idx, c) {
        const instr = await feedbackPrompt(t('narration:cast.instruction-placeholder', '英文音色画像'), c.suggestion || c.currentInstruction || '');
        if (instr == null) return;
        const trimmed = String(instr).trim();
        if (!trimmed) return;
        resolveConflict(idx, trimmed);
    }

    async function renderCastList() {
        const list = els.castList;
        if (!list) return;
        let voices = [];
        if (state.editCastId > 0) {
            try {
                const r = await fetch('/api/narration/casts/' + encodeURIComponent(state.editCastId) + '/voices',
                    { credentials: 'same-origin' });
                if (r.ok) { const data = await r.json(); voices = Array.isArray(data.voices) ? data.voices : []; }
            } catch {}
        }
        if (!voices.length) {
            list.innerHTML = `<div class="narration-cast-empty">${escapeHtml(t('narration:cast.empty', '尚无角色，请先生成多角色朗读脚本。'))}</div>`;
            return;
        }
        list.innerHTML = '';
        voices.forEach((v) => list.appendChild(renderVoiceRow(v)));
    }

    function metaLabel(kind, value) {
        const key = 'narration:' + kind + '.' + (value || 'unknown');
        return t(key, value || '');
    }

    function renderVoiceRow(v) {
        const row = document.createElement('div');
        row.className = 'narration-voice';
        const head = document.createElement('div');
        head.className = 'narration-voice-head';
        const isNarrator = v.id === 0;
        const name = document.createElement('span');
        name.className = 'narration-voice-name';
        name.textContent = isNarrator ? t('narration:narrator', '旁白') : (v.name || '');
        const meta = document.createElement('span');
        meta.className = 'narration-voice-meta';
        meta.textContent = `${metaLabel('gender', v.gender)} · ${metaLabel('age', v.age)}`;
        const flag = document.createElement('span');
        flag.className = 'narration-voice-flag' + (v.editedByUser ? ' locked' : '');
        flag.textContent = v.editedByUser ? t('narration:cast.locked', '已锁定') : t('narration:cast.ai', 'AI 生成');
        const actions = document.createElement('span');
        actions.className = 'narration-voice-actions';
        const editBtn = document.createElement('button');
        editBtn.className = 'btn btn-sm';
        editBtn.textContent = t('narration:cast.edit', '编辑音色');
        const previewBtn = document.createElement('button');
        previewBtn.className = 'btn btn-sm';
        previewBtn.textContent = t('narration:cast.preview', '试听');
        actions.appendChild(previewBtn);
        actions.appendChild(editBtn);
        head.appendChild(name);
        head.appendChild(meta);
        head.appendChild(flag);
        head.appendChild(actions);

        const instr = document.createElement('div');
        instr.className = 'narration-voice-instr';
        instr.textContent = v.controlInstruction || '';

        const edit = document.createElement('div');
        edit.className = 'narration-voice-edit';
        const ta = document.createElement('textarea');
        ta.value = v.controlInstruction || '';
        ta.placeholder = t('narration:cast.instruction-placeholder', '');
        const editActions = document.createElement('div');
        editActions.className = 'narration-voice-edit-actions';
        const save = document.createElement('button');
        save.className = 'btn btn-sm';
        save.textContent = t('narration:cast.save', '保存');
        const cancel = document.createElement('button');
        cancel.className = 'btn btn-sm';
        cancel.textContent = t('narration:cast.cancel', '取消');
        editActions.appendChild(save);
        editActions.appendChild(cancel);
        edit.appendChild(ta);
        edit.appendChild(editActions);

        editBtn.addEventListener('click', () => { edit.classList.toggle('open'); });
        cancel.addEventListener('click', () => { ta.value = v.controlInstruction || ''; edit.classList.remove('open'); });
        save.addEventListener('click', async () => {
            const text = ta.value.trim();
            if (!text) return;
            save.disabled = true;
            const ok = await saveVoice(v.id, text);
            save.disabled = false;
            if (ok) { v.controlInstruction = text; v.editedByUser = true; edit.classList.remove('open'); renderCastList(); }
        });
        previewBtn.addEventListener('click', () => previewVoice(previewBtn, ta.value.trim() || v.controlInstruction || '', v));

        row.appendChild(head);
        row.appendChild(instr);
        row.appendChild(edit);
        row.appendChild(renderRefRow(v));
        return row;
    }

    // 「标准音色 / 参考音」区：状态 + [生成标准音][试听参考音][上传][删除]。配了参考音后，逐句合成用其音色克隆
    // （逐句情绪仍生效）、跨章一致；未配则用上面的音色画像。任何变更都会推进花名册 castUpdatedTime、失效音频缓存。
    function refRowStatusLabel(source) {
        if (source === 'auto') return t('narration:cast.ref.status.auto', '自动生成的标准音');
        if (source === 'upload') return t('narration:cast.ref.status.upload', '已上传参考音');
        return t('narration:cast.ref.status.none', '无参考音（使用音色画像）');
    }

    // 生成标准音用的默认示例正文（默认英文；中文等其它界面语言由 cast.ref.seed-text 的 i18n 覆盖）。
    const REF_SEED_TEXT_DEFAULT = "This is a sample reading used to lock the character's timbre; keep the tone natural, steady, and clear.";

    function renderRefRow(v) {
        const box = document.createElement('div');
        box.className = 'narration-voice-ref';
        const status = document.createElement('span');
        status.className = 'narration-voice-ref-status';
        status.textContent = refRowStatusLabel(v.refAudioSource);
        const actions = document.createElement('span');
        actions.className = 'narration-voice-ref-actions';
        const hasRef = !!v.refAudioSource;
        const genBtn = refButton(t('narration:cast.ref.generate', '生成标准音'));
        const playBtn = refButton(t('narration:cast.ref.preview', '试听参考音'));
        const uploadBtn = refButton(t('narration:cast.ref.upload', '上传'));
        const delBtn = refButton(t('narration:cast.ref.delete', '删除'));
        playBtn.style.display = hasRef ? '' : 'none';
        delBtn.style.display = hasRef ? '' : 'none';

        // 「生成标准音」展开正文输入框：预填本地化默认正文（默认英文），用户改完点「生成」才合成并采用。
        const editor = document.createElement('div');
        editor.className = 'narration-voice-ref-editor';
        editor.style.display = 'none';
        const seedLabel = document.createElement('div');
        seedLabel.className = 'narration-voice-ref-seed-label';
        seedLabel.textContent = t('narration:cast.ref.seed-label', '用于生成标准音的示例正文（可修改）');
        const textarea = document.createElement('textarea');
        textarea.className = 'narration-voice-ref-text';
        textarea.rows = 2;
        textarea.placeholder = t('narration:cast.ref.seed-placeholder', '输入用于生成标准音的示例正文');
        const editorActions = document.createElement('span');
        editorActions.className = 'narration-voice-ref-editor-actions';
        const confirmBtn = refButton(t('narration:cast.ref.generate-confirm', '生成'));
        const cancelBtn = refButton(t('narration:cast.ref.generate-cancel', '取消'));
        editorActions.appendChild(confirmBtn);
        editorActions.appendChild(cancelBtn);
        editor.appendChild(seedLabel);
        editor.appendChild(textarea);
        editor.appendChild(editorActions);

        genBtn.addEventListener('click', () => {
            if (editor.style.display !== 'none') { editor.style.display = 'none'; return; }
            if (!textarea.value.trim()) {
                textarea.value = t('narration:cast.ref.seed-text', REF_SEED_TEXT_DEFAULT);
            }
            editor.style.display = '';
            textarea.focus();
        });
        cancelBtn.addEventListener('click', () => { editor.style.display = 'none'; });
        confirmBtn.addEventListener('click', () => generateRef(v, confirmBtn, textarea.value));

        playBtn.addEventListener('click', () => playRefAudio(playBtn, v.id));
        uploadBtn.addEventListener('click', () => uploadRef(v));
        delBtn.addEventListener('click', () => deleteRef(v, delBtn));
        actions.appendChild(genBtn);
        actions.appendChild(playBtn);
        actions.appendChild(uploadBtn);
        actions.appendChild(delBtn);
        box.appendChild(status);
        box.appendChild(actions);
        box.appendChild(editor);
        return box;
    }

    function refButton(label) {
        const b = document.createElement('button');
        b.className = 'btn btn-sm';
        b.type = 'button';
        b.textContent = label;
        return b;
    }

    // 参考音变更后：清音频缓存 + 重取缓存脚本（刷新 castUpdatedTime 使音频缓存失效，不重算 LLM）+ 重渲染花名册（拉最新状态）。
    async function afterRefChange() {
        clearCache();
        await reloadCachedScript();
        await renderCastList();
    }

    // 用输入框里的正文生成并采用该角色标准音（后端走 Voice Design，正文同时作参考音转录）；
    // 留空则回退本地化默认正文（默认英文）。
    async function generateRef(v, button, text) {
        if (!state.editCastId) return;
        const seed = (text != null && text.trim())
            ? text.trim()
            : t('narration:cast.ref.seed-text', REF_SEED_TEXT_DEFAULT);
        button.disabled = true;
        button.textContent = t('narration:cast.ref.generating', '生成中…');
        let done = false;
        try {
            const r = await fetch('/api/narration/cast/voice/reference/generate', {
                method: 'POST', credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ castId: state.editCastId, characterId: v.id, text: seed })
            });
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                throw new Error(msg);
            }
            state.toast && state.toast(t('narration:toast.ref.generated', '已生成标准音并采用'), 'success');
            done = true;
            await afterRefChange();
        } catch (e) {
            state.toast && state.toast(t('narration:toast.ref.failed', '参考音操作失败：{message}',
                { message: String(e && e.message ? e.message : e) }), 'error');
        } finally {
            // 成功路径已 renderCastList 重建本行（输入框随之关闭），无需复位；失败则恢复「生成」按钮。
            if (!done) { button.disabled = false; button.textContent = t('narration:cast.ref.generate-confirm', '生成'); }
        }
    }

    // 试听已配的参考音（直接回放存盘文件，非合成）。
    async function playRefAudio(button, characterId) {
        if (!button || !state.editCastId) return;
        const restore = () => { button.disabled = false; button.textContent = t('narration:cast.ref.preview', '试听参考音'); };
        button.disabled = true;
        button.textContent = t('narration:cast.ref.loading', '加载中…');
        try {
            const r = await fetch('/api/narration/cast/voice/reference?castId='
                + encodeURIComponent(state.editCastId) + '&characterId=' + encodeURIComponent(characterId),
                { credentials: 'same-origin' });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            const audio = new Audio(url);
            const finish = () => { try { URL.revokeObjectURL(url); } catch {} restore(); };
            audio.onended = finish;
            audio.onerror = finish;
            button.textContent = t('narration:cast.previewing', '试听中…');
            audio.play().catch(finish);
        } catch (e) {
            state.toast && state.toast(t('narration:toast.ref.failed', '参考音操作失败：{message}',
                { message: String(e && e.message ? e.message : e) }), 'error');
            restore();
        }
    }

    // 上传真人参考音（wav/mp3）+ 可选转录。
    function uploadRef(v) {
        if (!state.editCastId) return;
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'audio/wav,audio/x-wav,audio/mpeg,audio/mp3,.wav,.mp3';
        input.style.display = 'none';
        input.addEventListener('change', async () => {
            const file = input.files && input.files[0];
            if (file) {
                const transcript = await feedbackPrompt(
                    t('narration:cast.ref.upload-transcript', '可选：输入参考音对应的文本（留空则不使用转录）'), '');
                const form = new FormData();
                form.append('castId', String(state.editCastId));
                form.append('characterId', String(v.id));
                form.append('file', file);
                if (transcript != null && transcript.trim()) form.append('refText', transcript.trim());
                try {
                    const r = await fetch('/api/narration/cast/voice/reference',
                        { method: 'POST', credentials: 'same-origin', body: form });
                    if (!r.ok) {
                        let msg = 'HTTP ' + r.status;
                        try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                        throw new Error(msg);
                    }
                    state.toast && state.toast(t('narration:toast.ref.uploaded', '参考音已上传'), 'success');
                    await afterRefChange();
                } catch (e) {
                    state.toast && state.toast(t('narration:toast.ref.failed', '参考音操作失败：{message}',
                        { message: String(e && e.message ? e.message : e) }), 'error');
                }
            }
            try { document.body.removeChild(input); } catch {}
        });
        document.body.appendChild(input);
        input.click();
    }

    // 删除前先 i18n 确认（误删的参考音无法从前端恢复，属数据丢失风险）；提交期间禁用「删除」按钮避免重复点击。
    async function deleteRef(v, button) {
        if (!state.editCastId) return;
        if (!await feedbackConfirm(t('narration:cast.ref.delete-confirm',
            '确定删除该角色的参考音吗？删除后将恢复使用音色画像，且无法从此处恢复。'))) return;
        if (button) {
            button.disabled = true;
            button.textContent = t('narration:cast.ref.deleting', '删除中…');
        }
        let done = false;
        try {
            const r = await fetch('/api/narration/cast/voice/reference?castId='
                + encodeURIComponent(state.editCastId) + '&characterId=' + encodeURIComponent(v.id),
                { method: 'DELETE', credentials: 'same-origin' });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            state.toast && state.toast(t('narration:toast.ref.deleted', '参考音已删除'), 'success');
            done = true;
            await afterRefChange();
        } catch (e) {
            state.toast && state.toast(t('narration:toast.ref.failed', '参考音操作失败：{message}',
                { message: String(e && e.message ? e.message : e) }), 'error');
        } finally {
            // 成功路径已 renderCastList 重建本行（删除按钮随之移除），无需复位；失败则恢复「删除」按钮。
            if (!done && button) { button.disabled = false; button.textContent = t('narration:cast.ref.delete', '删除'); }
        }
    }

    // 单角色试听：用该角色当前音色画像合成一小段示例文本（/preview）
    async function previewVoice(btn, instruction, v) {
        const sample = sampleLineFor(v.id) || t('narration:narrator', '旁白');
        await previewInstruction(btn, sample, instruction);
    }

    // 试听一段音色画像：按钮经历「生成中…→试听中…」两态——POST /preview 合成期间禁用并显示「生成中…」，
    // 拿到音频开始播放后显示「试听中…」，播放结束 / 出错 / 合成失败再恢复为「试听」。空画像不发请求、不变更按钮。
    async function previewInstruction(btn, text, instruction) {
        if (!btn) return;
        const instr = instruction == null ? '' : String(instruction).trim();
        if (!instr) return;
        const restore = () => { btn.disabled = false; btn.textContent = t('narration:cast.preview', '试听'); };
        btn.disabled = true;
        btn.textContent = t('narration:cast.generating', '生成中…');
        try {
            const r = await fetch('/api/narration/tts/preview', {
                method: 'POST',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ text: text, controlInstruction: instr })
            });
            if (!r.ok) {
                let msg = 'HTTP ' + r.status;
                try { const j = await r.json(); if (j && j.message) msg = j.message; } catch {}
                throw new Error(msg);
            }
            const blob = await r.blob();
            const url = URL.createObjectURL(blob);
            const audio = new Audio(url);
            const done = () => { try { URL.revokeObjectURL(url); } catch {} restore(); };
            audio.onended = done;
            audio.onerror = done;
            btn.textContent = t('narration:cast.previewing', '试听中…'); // 合成完成、进入播放态
            audio.play().catch(done);
        } catch (e) {
            state.toast && state.toast(t('narration:toast.synth-failed', '合成失败：{message}', { message: String(e && e.message ? e.message : e) }), 'error');
            restore();
        }
    }

    // 取该角色在脚本中的第一句作为试听样例；找不到时回退
    function sampleLineFor(speakerId) {
        const line = state.lines.find((l) => l.speakerId === speakerId && l.text && l.text.trim());
        return line ? line.text.trim().slice(0, 120) : '';
    }

    async function saveVoice(characterId, instruction) {
        try {
            const r = await fetch('/api/narration/cast/voice', {
                method: 'PUT',
                credentials: 'same-origin',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ castId: state.editCastId, characterId, controlInstruction: instruction })
            });
            if (!r.ok) throw new Error('HTTP ' + r.status);
            state.toast && state.toast(t('narration:toast.saved', '已保存'), 'success');
            // 音色变更：清音频缓存并刷新 castUpdatedTime（重取已缓存脚本，不重算 LLM），使后续合成用新音色
            clearCache();
            await reloadCachedScript();
            return true;
        } catch (e) {
            state.toast && state.toast(t('narration:toast.save-failed', '保存失败'), 'error');
            return false;
        }
    }

    function escapeHtml(s) {
        if (window.PixivNovelRender) return PixivNovelRender.escapeHtml(s);
        return String(s == null ? '' : s).replace(/[&<>"']/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
    }

    function bindModal() {
        if (!els.castModal) return;
        els.castModal.addEventListener('click', (e) => { if (e.target.id === 'modalNarrationCast') closeCast(); });
        const closeBtn = document.getElementById('narrationCastClose');
        const doneBtn = document.getElementById('narrationCastDone');
        if (closeBtn) closeBtn.addEventListener('click', closeCast);
        if (doneBtn) doneBtn.addEventListener('click', closeCast);
    }


            ctx.cast = { openCast, openCastFor, closeCast, renderConflicts, renderCastList, previewInstruction, sampleLineFor, bindModal };
        }
    };
})(window);
