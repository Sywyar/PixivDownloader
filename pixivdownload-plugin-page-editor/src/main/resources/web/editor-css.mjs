/** CSS 草稿属于当前面板；只有校验成功才写入组件/视口的样式目标。 */
export function installCssEditor(editor, api, t, lifetime, allowedProperties, motion) {
    const $ = id => document.getElementById(id);
    const panel = $('css-panel'), allowed = new Set(allowedProperties);
    let cm, view, loading, target, component, device, baseline = '', baselineStyles = '', dirty = false, expanded = false;
    let request, requestSerial = 0, syncing = false;
    const serialize = () => Object.entries(target?.getStyle() || {}).map(([name, value]) => name + ': ' + value + ';').join('\n');
    const current = () => !!component && !!target && !lifetime.aborted && editor.getSelected() === component && editor.getDevice() === device
        && editor.StyleManager.getSelected() === target;
    const message = (key, error = false) => {
        $('css-message').textContent = key ? t(key) : ''; $('css-message').hidden = !key;
        panel.dataset.state = error ? 'error' : key === 'css-applied' ? 'success' : 'idle';
    };
    function controls() {
        $('css-apply').disabled = !view || !dirty || !current() || !!request;
        $('css-reset').disabled = !view || (!dirty && current() && !request);
        panel.setAttribute('aria-busy', String(!!request || !!loading));
        $('edit-css').classList.toggle('has-draft', dirty);
        $('style-toggle').classList.toggle('has-draft', dirty);
        $('style-section').hidden = !editor.getSelected() && !dirty;
    }
    function cancel() {
        const pending = !!request;
        requestSerial++; request?.abort(); request = null;
        if (pending) message(current() ? dirty ? 'css-pending' : null : 'css-stale', !current());
        controls();
    }
    function bind() {
        if (!view || lifetime.aborted) return;
        if (current() && serialize() === baselineStyles && view.state.doc.toString() === baseline) { controls(); return; }
        component = editor.getSelected(); target = editor.StyleManager.getSelected(); device = editor.getDevice();
        baseline = baselineStyles = serialize(); dirty = false;
        $('css-context').textContent = (component?.getName() || t('select-first')) + ' · ' + $('viewport').selectedOptions[0].textContent;
        syncing = true; view.setState(createState(baseline)); syncing = false;
        message(); controls();
    }
    function completion(context) {
        const before = context.state.sliceDoc(0, context.pos);
        const declaration = before.slice(before.lastIndexOf(';') + 1), word = context.matchBefore(/[\w-]*/);
        if (!word || (!context.explicit && !word.text)) return null;
        if (!declaration.includes(':')) return {from: word.from, options: [...allowed].map(label =>
            ({label, type: 'property', apply: label + ': '})), validFor: /^[\w-]*$/};
        const name = declaration.split(':')[0].trim().toLowerCase(), result = cm.cssCompletionSource(context);
        return result && {...result, options: result.options.filter(option => option.label !== 'none' && CSS.supports(name, option.label))};
    }
    function createState(doc) {
        return cm.EditorState.create({doc, extensions: [
            cm.EditorState.phrases.of({Completions: t('css-completions')}),
            cm.EditorState.transactionExtender.of(transaction => transaction.docChanged ? cm.setDiagnostics(transaction.startState, []) : null),
            cm.lineNumbers(), cm.highlightActiveLine(), cm.highlightActiveLineGutter(), cm.drawSelection(),
            cm.history(), cm.indentOnInput(), cm.bracketMatching(), cm.closeBrackets(), cm.lintGutter(),
            cm.cssLanguage.configure({top: 'Styles'}), cm.autocompletion({override: [completion]}),
            cm.syntaxHighlighting(cm.HighlightStyle.define([
                {tag: cm.tags.propertyName, class: 'css-token-property'},
                {tag: [cm.tags.number, cm.tags.unit, cm.tags.color], class: 'css-token-value'},
                {tag: [cm.tags.keyword, cm.tags.atom], class: 'css-token-keyword'},
                {tag: [cm.tags.string, cm.tags.comment], class: 'css-token-muted'}
            ])),
            cm.EditorView.lineWrapping,
            cm.EditorView.contentAttributes.of({'aria-label': t('css-title'), 'aria-describedby': 'css-message', spellcheck: 'false'}),
            cm.keymap.of([{key: 'Mod-Enter', run: () => { apply(); return true; }},
                ...cm.closeBracketsKeymap, ...cm.completionKeymap, ...cm.defaultKeymap, ...cm.historyKeymap]),
            cm.EditorView.updateListener.of(update => {
                if (!update.docChanged || syncing) return;
                cancel(); dirty = update.state.doc.toString() !== baseline;
                message(current() ? dirty ? 'css-pending' : null : 'css-stale', !current()); controls();
            })
        ]});
    }
    async function ensureView() {
        if (view) return;
        if (loading) return loading;
        message('css-loading');
        loading = (async () => {
            cm = await import('./vendor/codemirror/codemirror.mjs');
            if (lifetime.aborted) return;
            view = new cm.EditorView({state: createState(''), parent: $('css-code')}); bind();
        })().catch(() => { if (!lifetime.aborted) message('css-load-failed', true); });
        controls();
        try { await loading; }
        finally { loading = null; controls(); }
    }
    function diagnostic(css) {
        let start = 0;
        for (const part of css.split(';')) {
            const text = part.trim(), colon = text.indexOf(':');
            if (text && (colon < 1 || !allowed.has(text.slice(0, colon).trim().toLowerCase())
                || !CSS.supports(text.slice(0, colon).trim(), text.slice(colon + 1).trim()))) {
                return {from: start, to: Math.min(css.length, start + part.length), severity: 'error', message: t('css-invalid')};
            }
            start += part.length + 1;
        }
        return null;
    }
    async function apply() {
        if (!view || !dirty || !current() || request) return;
        const css = view.state.doc.toString(), issue = diagnostic(css);
        view.dispatch(cm.setDiagnostics(view.state, issue ? [issue] : []));
        if (issue) { message('css-invalid', true); return; }
        const controller = new AbortController(); request = controller;
        const serial = ++requestSerial, expected = serialize();
        message('css-checking'); controls();
        try {
            const styles = await api('styles/parse', {css}, controller.signal);
            if (controller.signal.aborted || serial !== requestSerial || !current()) return;
            if (serialize() !== expected) { message('css-stale', true); return; }
            target.setStyle(styles); baseline = css; baselineStyles = serialize(); dirty = false;
            message('css-applied'); motion.reveal($('css-message'));
        } catch (error) {
            if (controller.signal.aborted || serial !== requestSerial) return;
            const key = error.message === 'PAGE_STYLE_INVALID' ? 'css-invalid' : 'css-failed';
            message(key, true);
            view.dispatch(cm.setDiagnostics(view.state, [{from: 0, to: css.length, severity: 'error', message: t(key)}]));
        } finally { if (request === controller) request = null; controls(); }
    }
    function fullScreen(value) {
        motion.settle(panel);
        const focus = document.activeElement;
        expanded = value;
        if (value) document.body.append(panel); else $('css-anchor').append(panel);
        panel.classList.toggle('css-fullscreen', value); document.body.classList.toggle('css-fullscreen-open', value);
        $('css-expand').setAttribute('aria-pressed', String(value));
        $('css-expand').setAttribute('aria-label', t(value ? 'css-shrink' : 'css-expand'));
        $('css-expand').title = t(value ? 'css-shrink' : 'css-expand');
        motion.reveal(panel); view?.requestMeasure();
        if (focus === view?.contentDOM) view.focus(); else $('css-expand').focus({preventScroll: true});
    }
    $('edit-css').addEventListener('click', async () => {
        const open = $('edit-css').getAttribute('aria-expanded') !== 'true';
        if (!open) cancel();
        motion.setDisclosure($('edit-css'), open);
        if (open) { await ensureView(); if (!panel.hidden) view?.requestMeasure(); }
    }, {signal: lifetime});
    $('style-toggle').addEventListener('click', () => {
        const open = $('style-toggle').getAttribute('aria-expanded') !== 'true';
        if (!open) cancel();
        motion.setDisclosure($('style-toggle'), open);
    }, {signal: lifetime});
    $('css-expand').addEventListener('click', () => fullScreen(!expanded), {signal: lifetime});
    function help(open) {
        motion.setDisclosure($('css-help-toggle'), open);
    }
    $('css-help-toggle').addEventListener('click', () => help($('css-help-toggle').getAttribute('aria-expanded') !== 'true'), {signal: lifetime});
    $('css-apply').addEventListener('click', apply, {signal: lifetime});
    $('css-reset').addEventListener('click', () => { cancel(); bind(); view?.focus(); }, {signal: lifetime});
    panel.addEventListener('keydown', event => {
        if (event.key !== 'Escape' || event.defaultPrevented || (view && cm.completionStatus(view.state))) return;
        event.preventDefault(); event.stopPropagation();
        if ($('css-help-toggle').getAttribute('aria-expanded') === 'true') { help(false); $('css-help-toggle').focus(); }
        else if (expanded) fullScreen(false);
        else { cancel(); motion.setDisclosure($('edit-css'), false); $('edit-css').focus(); }
    }, {signal: lifetime});
    const selected = () => {
        cancel();
        if (dirty) { message(current() ? 'css-pending' : 'css-stale', !current()); controls(); } else bind();
    };
    editor.on('component:selected component:deselected device:select style:target', selected);
    const updated = () => { if (view && !dirty && !request && current() && serialize() !== baselineStyles) bind(); };
    editor.on('update undo redo', updated);
    lifetime.addEventListener('abort', () => {
        cancel(); if (expanded) fullScreen(false);
        editor.off('component:selected component:deselected device:select style:target', selected);
        editor.off('update undo redo', updated); view?.destroy(); view = null;
    }, {once: true});
    return {
        pending: () => dirty,
        reset() { cancel(); dirty = false; bind(); },
        async ready() {
            if (!dirty) return true;
            motion.setDisclosure($('style-toggle'), true);
            motion.setDisclosure($('edit-css'), true);
            await ensureView(); message(current() ? 'css-pending' : 'css-stale', true); view?.focus(); return false;
        }
    };
}
