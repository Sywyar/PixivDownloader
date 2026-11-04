/** 页面共用的短过渡；状态立即生效，动画只持有当前视图。 */
export function createViewMotion(lifetime) {
    const reduced = matchMedia('(prefers-reduced-motion: reduce)');
    const animations = new Map(), disclosures = new WeakMap();
    const stop = element => { animations.get(element)?.animation.cancel(); animations.delete(element); };
    const settle = element => { const entry = animations.get(element); stop(element); entry?.finish?.(); };
    const settleAll = () => { for (const [element] of animations) settle(element); };
    function animate(element, keyframes, duration = 220, finish) {
        stop(element);
        if (!element || reduced.matches || lifetime.aborted || document.hidden || !element.getClientRects().length) { finish?.(); return; }
        const animation = element.animate(keyframes, {duration, easing: 'cubic-bezier(.2,.8,.2,1)'});
        const entry = {animation, finish};
        animations.set(element, entry);
        animation.onfinish = () => { if (animations.get(element) !== entry) return; animations.delete(element); finish?.(); };
    }
    function reveal(element) {
        if (!element || element.hidden) return;
        const current = animations.has(element) ? getComputedStyle(element) : null;
        animate(element, [{opacity: current?.opacity ?? .45, translate: current?.translate ?? '0 4px'}, {opacity: 1, translate: '0 0'}]);
    }
    function text(element, value) {
        if (element.textContent === value) return;
        element.textContent = value;
        if (value) reveal(element);
    }
    function disclose(element, open) {
        const height = element.hidden ? 0 : element.getBoundingClientRect().height;
        const opacity = element.hidden ? 0 : getComputedStyle(element).opacity;
        stop(element); element.hidden = false; element.inert = !open;
        const end = open ? element.getBoundingClientRect().height : 0;
        element.style.overflow = 'hidden';
        animate(element, [{height: height + 'px', opacity}, {height: end + 'px', opacity: open ? 1 : 0}], 220,
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
    function remove(element) {
        const style = getComputedStyle(element);
        const from = {height: element.getBoundingClientRect().height + 'px', opacity: style.opacity,
            paddingTop: style.paddingTop, paddingBottom: style.paddingBottom, borderBottomWidth: style.borderBottomWidth};
        element.inert = true; element.setAttribute('aria-hidden', 'true'); element.style.overflow = 'hidden';
        animate(element, [from, {height: '0px', opacity: 0, paddingTop: '0px', paddingBottom: '0px', borderBottomWidth: '0px'}], 180, () => element.remove());
    }
    function insert(element) {
        const style = getComputedStyle(element);
        const end = {height: element.getBoundingClientRect().height + 'px', opacity: 1,
            paddingTop: style.paddingTop, paddingBottom: style.paddingBottom, borderBottomWidth: style.borderBottomWidth};
        element.style.overflow = 'hidden';
        animate(element, [{height: '0px', opacity: 0, paddingTop: '0px', paddingBottom: '0px', borderBottomWidth: '0px'}, end], 220,
            () => { element.style.overflow = ''; });
    }
    document.addEventListener('click', event => {
        const summary = event.target.closest('summary');
        const details = summary?.parentElement;
        if (!details || !details.closest('.editor-main, .editor-header, .component-page')) return;
        event.preventDefault();
        if (details.classList.contains('action-menu')) {
            details.open = !details.open; if (details.open) reveal(details.querySelector('.action-menu-content')); return;
        }
        setDetails(details, !(disclosures.get(details) ?? details.open));
    }, {signal: lifetime});
    reduced.addEventListener('change', settleAll, {signal: lifetime});
    document.addEventListener('visibilitychange', () => { if (document.hidden) settleAll(); }, {signal: lifetime});
    lifetime.addEventListener('abort', settleAll, {once: true});
    return {reduced, animate, reveal, text, setDisclosure, setDetails, insert, remove, stop, settle, settleAll};
}
