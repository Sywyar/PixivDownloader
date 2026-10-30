/** 动效只属于编辑器视图，不写入项目样式。 */
export function installEditorMotion(editor, lifetime) {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const animations = new Map(), disclosures = new WeakMap(), folds = new Map();
    let frame, previous = new Map(), dragging = false, reset = true;
    const stop = element => { animations.get(element)?.animation.cancel(); animations.delete(element); };
    const settle = element => { const entry = animations.get(element); stop(element); entry?.finish?.(); };
    function animate(element, keyframes, duration = 220, finish) {
        stop(element);
        if (!element || reduced.matches || lifetime.aborted) { finish?.(); return; }
        const animation = element.animate(keyframes, {duration, easing: 'cubic-bezier(.2,.8,.2,1)'});
        const entry = {animation, finish};
        animations.set(element, entry);
        animation.onfinish = () => { if (animations.get(element) !== entry) return; animations.delete(element); finish?.(); };
    }
    const reveal = element => {
        if (!element || element.hidden) return;
        animate(element, [{opacity: .45, transform: 'translateY(4px)'}, {opacity: 1, transform: 'translateY(0)'}]);
    };
    function disclose(element, open) {
        const height = element.hidden ? 0 : element.getBoundingClientRect().height;
        stop(element); element.hidden = false; element.inert = !open;
        const end = open ? element.getBoundingClientRect().height : 0;
        element.style.overflow = 'hidden';
        animate(element, [{height: height + 'px', opacity: height ? 1 : 0}, {height: end + 'px', opacity: open ? 1 : 0}], 220,
            () => { element.hidden = !open; element.style.overflow = ''; });
    }
    function setDisclosure(control, open) {
        control.setAttribute('aria-expanded', String(open));
        disclose(document.getElementById(control.getAttribute('aria-controls')), open);
    }
    function setDetails(details, open) {
        const summary = details.querySelector(':scope > summary');
        disclosures.set(details, open);
        const height = details.getBoundingClientRect().height;
        stop(details); details.open = true;
        summary.setAttribute('aria-expanded', String(open));
        for (const child of details.children) if (child !== summary) child.inert = !open;
        const end = open ? details.getBoundingClientRect().height : summary.getBoundingClientRect().height
            + parseFloat(getComputedStyle(details).paddingTop) + parseFloat(getComputedStyle(details).paddingBottom);
        details.style.overflow = 'hidden';
        animate(details, [{height: height + 'px'}, {height: end + 'px'}], 220, () => {
            details.open = open; details.style.overflow = ''; disclosures.delete(details);
        });
    }
    function layout() {
        frame = null;
        const next = new Map();
        const visit = model => {
            const element = model.getEl();
            if (element && !element.hidden) next.set(model, {element, box: element.getBoundingClientRect()});
            model.components().forEach(visit);
        };
        visit(editor.getWrapper());
        if (!reset && !dragging && !reduced.matches) {
            for (const [model, value] of next) {
                if (model === editor.getWrapper()) continue;
                const old = previous.get(model), parent = model.parent();
                if (!old) { reveal(value.element); continue; }
                const dx = old.box.x - value.box.x, dy = old.box.y - value.box.y;
                const parentOld = previous.get(parent), parentNew = next.get(parent);
                if (parentOld && parentNew && (Math.abs(parentOld.box.x - parentNew.box.x) > 1 || Math.abs(parentOld.box.y - parentNew.box.y) > 1)) continue;
                if (Math.abs(dx) + Math.abs(dy) > 1) animate(value.element,
                    [{transform: `translate(${dx}px, ${dy}px)`}, {transform: 'translate(0,0)'}], 240);
            }
        }
        previous = next; reset = false;
    }
    const schedule = () => { if (!frame && !lifetime.aborted) frame = requestAnimationFrame(layout); };
    const loaded = () => { reset = true; schedule(); };
    const startDrag = () => { dragging = true; for (const [element] of animations) settle(element); document.getElementById('canvas-area').classList.add('is-dragging'); };
    const endDrag = () => { dragging = false; document.getElementById('canvas-area').classList.remove('is-dragging'); schedule(); };
    editor.on('update component:mount', schedule);
    editor.on('project:load canvas:frame:load device:select', loaded);
    editor.on('block:drag:start component:drag:start', startDrag);
    editor.on('block:drag:stop component:drag:end', endDrag);
    document.addEventListener('click', event => {
        const summary = event.target.closest('summary');
        const details = summary?.parentElement;
        if (!details || !details.closest('.editor-main, .editor-header')) return;
        event.preventDefault();
        if (details.classList.contains('action-menu')) {
            details.open = !details.open; if (details.open) reveal(details.querySelector('.action-menu-content')); return;
        }
        setDetails(details, !(disclosures.get(details) ?? details.open));
    }, {signal: lifetime});
    // GrapesJS 的折叠标题不是原生按钮；适配层补齐键盘和辅助技术状态。
    let nextGroupId = 0;
    function syncFolds() {
        document.querySelectorAll('#styles .gjs-sm-sector-title, #layers [data-toggle-open]').forEach(toggle => {
            const sector = toggle.closest('.gjs-sm-sector'), group = sector || toggle.closest('.gjs-layer');
            const content = group?.querySelector(sector ? ':scope > .gjs-sm-properties' : ':scope > .gjs-layer-children');
            if (!content) return;
            if (!content.id) content.id = 'editor-fold-' + ++nextGroupId;
            const open = group.classList.contains(sector ? 'gjs-sm-open' : 'open');
            toggle.setAttribute('role', 'button'); toggle.tabIndex = 0;
            toggle.setAttribute('aria-controls', content.id); toggle.setAttribute('aria-expanded', String(open));
            if (!sector) toggle.setAttribute('aria-label', group.querySelector('.gjs-layer-name')?.textContent || '');
            content.inert = !open;
        });
    }
    const foldObserver = new MutationObserver(syncFolds);
    for (const id of ['styles', 'layers']) foldObserver.observe(document.getElementById(id), {subtree: true, childList: true, attributes: true, attributeFilter: ['class']});
    syncFolds();
    document.addEventListener('keydown', event => {
        if (!['Enter', ' '].includes(event.key) || event.defaultPrevented) return;
        const toggle = event.target.closest('#styles .gjs-sm-sector-title, #layers [data-toggle-open]');
        if (!toggle) return;
        event.preventDefault(); toggle.click();
    }, {signal: lifetime});
    document.addEventListener('click', event => {
        const toggle = event.target.closest('#styles .gjs-sm-sector-title, #layers [data-toggle-open]');
        if (!toggle) return;
        const sector = toggle.closest('.gjs-sm-sector'), group = sector || toggle.closest('.gjs-layer');
        const content = group?.querySelector(sector ? ':scope > .gjs-sm-properties' : ':scope > .gjs-layer-children');
        if (!content) return;
        const height = content.getBoundingClientRect().height;
        cancelAnimationFrame(folds.get(content));
        folds.set(content, requestAnimationFrame(() => {
            folds.delete(content);
            if (lifetime.aborted || !content.isConnected) return;
            const open = group.classList.contains(sector ? 'gjs-sm-open' : 'open');
            stop(content); content.style.display = 'block'; content.inert = !open;
            const end = open ? content.getBoundingClientRect().height : 0;
            content.style.overflow = 'hidden';
            animate(content, [{height: height + 'px', opacity: height ? 1 : 0}, {height: end + 'px', opacity: open ? 1 : 0}], 220,
                () => { content.style.display = sector && !open ? 'none' : ''; content.style.overflow = ''; content.inert = !open; });
        }));
    }, {capture: true, signal: lifetime});
    const preferences = () => { for (const [element] of animations) settle(element); };
    reduced.addEventListener('change', preferences, {signal: lifetime});
    lifetime.addEventListener('abort', () => {
        cancelAnimationFrame(frame); preferences(); previous.clear();
        for (const pending of folds.values()) cancelAnimationFrame(pending);
        folds.clear();
        foldObserver.disconnect();
        editor.off('update component:mount', schedule); editor.off('project:load canvas:frame:load device:select', loaded);
        editor.off('block:drag:start component:drag:start', startDrag); editor.off('block:drag:stop component:drag:end', endDrag);
    }, {once: true});
    schedule();
    return {reveal, setDisclosure, setDetails, settle};
}
