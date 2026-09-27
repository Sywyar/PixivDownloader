'use strict';

function scheduleSourceSummary(task) {
    const runtime = altScheduleSources();
    let summary;
    if (task.sourceAvailable !== false && runtime?.isAvailable(task.sourceType || task.type)) {
        try { summary = runtime.summary(task, {}); } catch (e) { /* Keep the stored presentation when the owner is unavailable. */ }
    }
    const presentation = scheduleTaskPresentation(task);
    const description = typeof summary?.description === 'string' ? summary.description
        : typeof presentation.summary === 'string' ? presentation.summary : '';
    const kind = summary?.kind || scheduleTaskKind(task);
    return summaryJoin([description || scheduleTypeLabel(task), kind ? scheduleKindLabel(kind) : null]);
}

function scheduleCadenceLabel(task) {
    if (task.triggerKind !== 'cron') return bt('schedule.manage.every-minutes', '每 {minutes} 分钟',
        {minutes: task.intervalMinutes || 0});
    // Only translate an exact daily expression; complex Cron remains authoritative and visible.
    const parts = String(task.cronExpr || '').trim().split(/\s+/);
    if (parts.length === 6 && parts[0] === '0' && /^\d{1,2}$/.test(parts[1])
        && /^\d{1,2}$/.test(parts[2]) && Number(parts[1]) < 60 && Number(parts[2]) < 24
        && ['*', '?'].includes(parts[3]) && parts[4] === '*' && ['*', '?'].includes(parts[5])
        && !(parts[3] === '?' && parts[5] === '?')) {
        return bt('schedule.manage.daily', '每天 {time}',
            {time: parts[2].padStart(2, '0') + ':' + parts[1].padStart(2, '0')});
    }
    return scheduleTriggerLabel(task);
}

function scheduleNextLabel(task, now = new Date()) {
    if (!task.enabled || task.suspendReason || ['PAUSED', 'OVERUSE_PAUSED'].includes(task.lastStatus)) return '';
    const next = new Date(task.nextRunTime);
    if (!task.nextRunTime || !Number.isFinite(next.getTime())) return '';
    const tomorrow = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1);
    const sameDay = date => next.getFullYear() === date.getFullYear()
        && next.getMonth() === date.getMonth() && next.getDate() === date.getDate();
    const time = next.toLocaleTimeString(pageI18n?.lang || undefined, {hour: '2-digit', minute: '2-digit'});
    const relative = sameDay(now) ? bt('schedule.manage.today', '今天 {time}', {time})
        : sameDay(tomorrow) ? bt('schedule.manage.tomorrow', '明天 {time}', {time}) : fmtScheduleTime(task.nextRunTime);
    return bt('schedule.manage.next', '下次：{time}', {time: relative});
}

function scheduleResults(task, light) {
    const section = el('div', 'ab-schedule-results');
    section._scheduleTask = task;
    const footer = el('div', 'ab-schedule-result-line');
    const lamp = el('span', 'ab-lamp ab-lamp--' + light.tone + (light.live ? ' is-live' : ''));
    lamp.append(el('span', 'ab-lamp-dot'), el('span', '', light.text));
    const toggle = el('button', 'ab-schedule-queue-toggle');
    toggle.type = 'button';
    toggle.id = 'abScheduleQueueToggle-' + task.id;
    toggle.setAttribute('aria-controls', 'abScheduleQueueRegion-' + task.id);
    toggle.append(el('span', '', bt('schedule.manage.results', '本轮结果')), abIconEl('chevron-down'));
    footer.append(lamp, toggle);
    const region = el('div', 'ab-schedule-queue-region');
    region.id = 'abScheduleQueueRegion-' + task.id;
    region.setAttribute('role', 'region');
    region.setAttribute('aria-labelledby', toggle.id);
    const clip = el('div', 'ab-schedule-queue-clip');
    const box = el('div', 'ab-schedule-queue');
    clip.appendChild(box);
    region.appendChild(clip);
    section.append(footer, region);
    const sync = () => {
        const open = scheduleState.expandedQueues.has(task.id);
        section.classList.toggle('is-expanded', open);
        toggle.setAttribute('aria-expanded', String(open));
        region.inert = !open;
        region.setAttribute('aria-hidden', String(!open));
        box.id = open ? 'abScheduleQueue-' + task.id : '';
    };
    const clear = () => {
        if (scheduleState.expandedQueues.has(task.id)) return;
        scheduleQueueVue()?.unmountScheduleQueue?.(task.id);
        box.replaceChildren();
    };
    region.addEventListener('transitionend', event => {
        if (event.target === region && event.propertyName === 'grid-template-rows') clear();
    });
    toggle.addEventListener('click', () => {
        const open = !scheduleState.expandedQueues.has(task.id);
        if (open) scheduleState.expandedQueues.add(task.id);
        else scheduleState.expandedQueues.delete(task.id);
        sync();
        if (open) {
            if (!box.children.length) box.appendChild(el('p', 'ab-loading-line', bt('common.loading', '加载中…')));
            loadScheduleQueue(section._scheduleTask);
        } else if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) clear();
        startScheduleQueuePolling();
    });
    sync();
    return section;
}
