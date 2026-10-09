'use strict';
window.PixivBatch.onboarding = {
    bindTourButton(controller) {
        const button = document.getElementById('batch-guide-button');
        if (!button || !controller) return;
        button.hidden = false;
        button.onclick = () => controller.start(true);
        const label = bt('tour:common.help', '操作指引');
        button.setAttribute('aria-label', label);
        button.title = label;
        const text = button.querySelector('[data-onboarding-label]');
        if (text) text.textContent = label;
    },
    exampleItem(id) {
        return state.queue.find(item => (item.kind || 'illust') === 'illust'
            && String(item.id) === String(id));
    },
    exampleProgress(id) {
        const item = this.exampleItem(id);
        return {
            status: item ? item.status : 'removed',
            running: state.isRunning,
            paused: state.isPaused,
            message: item ? (item.statusMessageKey
                ? bt(item.statusMessageKey, item.lastMessage || '', {count: item.downloadedCount})
                : item.lastMessage || '') : ''
        };
    },
    retryExample(id) {
        const item = this.exampleItem(id);
        if (item?.status === 'downloading') return false;
        if (item) removeFromQueue(item.id);
        return !this.exampleItem(id);
    }
};
