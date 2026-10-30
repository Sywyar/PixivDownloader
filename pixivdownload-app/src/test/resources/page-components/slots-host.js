window.slotFixture = {mounts: 0, destroys: 0, cleanups: 0, ticks: 0, events: 0, late: 0, aborted: 0, scopes: []};
window.slotPages = ['first', 'second'].map(id => PixivUiSlots.connect({
    root: document.getElementById(id), targetPrefix: 'acceptance.',
    properties: {label: id}, onEvent: () => { window.slotFixture.events++; }
}));
Promise.all(window.slotPages.map(page => page.refresh())).then(() => { window.slotReady = true; });
