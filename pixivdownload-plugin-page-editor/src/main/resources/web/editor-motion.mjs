import {createViewMotion} from './view-motion.mjs';

/** 动效只属于编辑器视图，不写入项目样式。 */
export function installEditorMotion(editor, lifetime) {
    const motion = createViewMotion(lifetime);
    const {reduced, animate, reveal, stop, settleAll} = motion;
    const folds = new Map();
    let frame, previous = new Map(), dragging = false, reset = true;
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
    const startDrag = () => { dragging = true; settleAll(); document.getElementById('canvas-area').classList.add('is-dragging'); };
    const endDrag = () => { dragging = false; document.getElementById('canvas-area').classList.remove('is-dragging'); schedule(); };
    editor.on('update component:mount', schedule);
    editor.on('project:load canvas:frame:load device:select', loaded);
    editor.on('block:drag:start component:drag:start', startDrag);
    editor.on('block:drag:stop component:drag:end', endDrag);
    // GrapesJS 的折叠标题不是原生按钮；适配层补齐键盘和辅助技术状态。
    const foldTitles = '#styles .gjs-sm-sector-title, #layers [data-toggle-open], #blocks .gjs-title';
    function foldTarget(toggle) {
        const group = toggle.closest('.gjs-sm-sector, .gjs-layer, .gjs-block-category');
        const sector = group?.classList.contains('gjs-sm-sector'), block = group?.classList.contains('gjs-block-category');
        const content = group?.querySelector(sector ? ':scope > .gjs-sm-properties' : block ? ':scope > .gjs-blocks-c' : ':scope > .gjs-layer-children');
        return {group, content, sector, block};
    }
    let nextGroupId = 0;
    function syncFolds() {
        document.querySelectorAll(foldTitles).forEach(toggle => {
            const {group, content, sector, block} = foldTarget(toggle);
            if (!content) return;
            if (!content.id) content.id = 'editor-fold-' + ++nextGroupId;
            const open = group.classList.contains(sector ? 'gjs-sm-open' : block ? 'gjs-open' : 'open');
            toggle.setAttribute('role', 'button'); toggle.tabIndex = 0;
            toggle.setAttribute('aria-controls', content.id); toggle.setAttribute('aria-expanded', String(open));
            if (!sector && !block) toggle.setAttribute('aria-label', group.querySelector('.gjs-layer-name')?.textContent || '');
            content.inert = !open;
        });
    }
    const foldObserver = new MutationObserver(syncFolds);
    for (const id of ['styles', 'layers', 'blocks']) foldObserver.observe(document.getElementById(id), {subtree: true, childList: true, attributes: true, attributeFilter: ['class']});
    syncFolds();
    document.addEventListener('keydown', event => {
        if (!['Enter', ' '].includes(event.key) || event.defaultPrevented) return;
        const toggle = event.target.closest(foldTitles);
        if (!toggle) return;
        event.preventDefault(); toggle.click();
    }, {signal: lifetime});
    document.addEventListener('click', event => {
        const toggle = event.target.closest(foldTitles);
        if (!toggle) return;
        const {group, content, sector, block} = foldTarget(toggle);
        if (!content) return;
        const height = content.getBoundingClientRect().height, opacity = getComputedStyle(content).opacity;
        cancelAnimationFrame(folds.get(content));
        folds.set(content, requestAnimationFrame(() => {
            folds.delete(content);
            if (lifetime.aborted || !content.isConnected) return;
            // 原生点击的微任务检查点早于框架的冒泡处理；下一帧读取框架已提交的状态。
            const open = group.classList.contains(sector ? 'gjs-sm-open' : block ? 'gjs-open' : 'open');
            stop(content); content.style.display = block ? 'grid' : 'block'; content.inert = !open;
            const end = open ? content.getBoundingClientRect().height : 0;
            content.style.overflow = 'hidden';
            animate(content, [{height: height + 'px', opacity: height ? opacity : 0}, {height: end + 'px', opacity: open ? 1 : 0}], 220,
                () => { content.style.display = (sector || block) && !open ? 'none' : ''; content.style.overflow = ''; content.inert = !open; });
        }));
    }, {capture: true, signal: lifetime});
    lifetime.addEventListener('abort', () => {
        cancelAnimationFrame(frame); previous.clear();
        for (const pending of folds.values()) cancelAnimationFrame(pending);
        folds.clear();
        foldObserver.disconnect();
        editor.off('update component:mount', schedule); editor.off('project:load canvas:frame:load device:select', loaded);
        editor.off('block:drag:start component:drag:start', startDrag); editor.off('block:drag:stop component:drag:end', endDrag);
    }, {once: true});
    schedule();
    return motion;
}
