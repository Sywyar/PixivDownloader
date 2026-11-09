'use strict';

const GALLERY_DATE_FIELDS = {
    date: {label: 'filter.sort.date', param: 'downloaded'},
    uploadTime: {label: 'filter.sort.upload-time', param: 'uploaded'},
    createTime: {label: 'filter.time.created', param: 'created'},
    updateTime: {label: 'filter.time.updated', param: 'updated'},
    reuploadTime: {label: 'filter.time.reuploaded', param: 'reuploaded'},
};

function filterDateMillis(value, nextDay = false) {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null;
    const date = new Date(value + 'T00:00:00');
    if (!Number.isFinite(date.getTime()) || date.getTime() <= 0
        || date.getDate() !== Number(value.slice(8))) return null;
    if (nextDay) date.setDate(date.getDate() + 1);
    return date.getTime();
}

function galleryTimeRanges() {
    const ranges = {};
    for (const row of state.dateFilters) {
        const field = GALLERY_DATE_FIELDS[row.field];
        if (!field) continue;
        const from = filterDateMillis(row.start), before = filterDateMillis(row.end, true);
        if (from != null) ranges[field.param + 'From'] = from;
        if (before != null) ranges[field.param + 'Before'] = before;
    }
    return ranges;
}

function restoreTimeRanges(payload) {
    let rows = payload.dateFilters;
    if (!Array.isArray(rows)) {
        rows = [];
        if (payload.downloadStartDate || payload.downloadEndDate)
            rows.push({field: 'date', start: payload.downloadStartDate, end: payload.downloadEndDate});
        if (payload.uploadStartDate || payload.uploadEndDate)
            rows.push({field: payload.sourceTimeField || 'uploadTime', start: payload.uploadStartDate, end: payload.uploadEndDate});
    }
    const seen = new Set();
    state.dateFilters = [];
    for (const row of rows) {
        if (!row || !Object.hasOwn(GALLERY_DATE_FIELDS, row.field) || seen.has(row.field)) continue;
        seen.add(row.field);
        let start = filterDateMillis(row.start) ? row.start : '';
        let end = filterDateMillis(row.end) ? row.end : '';
        if (start && end && start > end) start = end = '';
        state.dateFilters.push({field: row.field, start, end});
    }
    if (!state.dateFilters.length) state.dateFilters.push({field: 'date', start: '', end: ''});
}

function syncDateSort() {
    const select = document.getElementById('dateSort');
    if (select) {
        const active = Object.hasOwn(GALLERY_DATE_FIELDS, state.sort);
        select.value = active ? state.sort : '';
        select.classList.toggle('active', active);
    }
    document.querySelectorAll('.chip[data-sort]').forEach(chip => {
        const active = chip.dataset.sort === state.sort;
        chip.classList.toggle('active', active);
        chip.setAttribute('aria-pressed', String(active));
    });
}

function changeDateFilters() {
    state.page = 0;
    updateFilterBadge();
    reloadCurrentView();
}

function renderDateFilters() {
    const container = document.getElementById('dateFilters');
    if (!container) return;
    container.replaceChildren();
    const used = new Set(state.dateFilters.map(row => row.field));
    state.dateFilters.forEach((row, index) => {
        const group = document.createElement('div');
        group.className = 'filter-date-range';
        group.setAttribute('role', 'group');
        group.setAttribute('aria-label', pageI18n.t(GALLERY_DATE_FIELDS[row.field].label));
        const select = document.createElement('select');
        select.className = 'chip date-filter-kind';
        select.setAttribute('aria-label', pageI18n.t('filter.date.kind'));
        for (const [field, meta] of Object.entries(GALLERY_DATE_FIELDS)) {
            const option = document.createElement('option');
            option.value = field;
            option.textContent = pageI18n.t(meta.label);
            option.disabled = field !== row.field && used.has(field);
            select.append(option);
        }
        select.value = row.field;
        select.addEventListener('change', () => {
            row.field = select.value;
            renderDateFilters();
            container.children[index].querySelector('select').focus();
            changeDateFilters();
        });
        group.append(select);
        const inputs = {};
        for (const [key, label] of [['start', 'filter.date.from'], ['end', 'filter.date.to']]) {
            const wrapper = document.createElement('label');
            wrapper.className = 'filter-date-field';
            const text = document.createElement('span');
            text.textContent = pageI18n.t(label);
            const input = document.createElement('input');
            input.type = 'date';
            input.min = '1970-01-02';
            input.value = row[key];
            input.dataset.bound = key;
            input.setAttribute('aria-label', pageI18n.t(GALLERY_DATE_FIELDS[row.field].label) + ' · ' + pageI18n.t(label));
            input.setAttribute('aria-describedby', 'dateHint-' + index);
            inputs[key] = input;
            input.addEventListener('change', () => {
                inputs.end.setCustomValidity(inputs.start.value && inputs.end.value && inputs.start.value > inputs.end.value
                    ? pageI18n.t('filter.date.invalid') : '');
                if (!inputs.start.reportValidity() || !inputs.end.reportValidity()) return;
                row.start = inputs.start.value;
                row.end = inputs.end.value;
                changeDateFilters();
            });
            wrapper.append(text, input);
            group.append(wrapper);
        }
        const hint = document.createElement('span');
        hint.id = 'dateHint-' + index;
        hint.className = 'filter-date-hint';
        hint.textContent = pageI18n.t('filter.date.hint');
        group.append(hint);
        const actions = document.createElement('div');
        actions.className = 'date-filter-actions';
        function button(symbol, key, action) {
            const control = document.createElement('button');
            control.type = 'button';
            control.className = 'chip date-filter-action';
            control.textContent = symbol;
            control.title = pageI18n.t(key);
            control.setAttribute('aria-label', pageI18n.t(key));
            control.addEventListener('click', action);
            actions.append(control);
        }
        button('−', 'filter.date.remove', () => {
            state.dateFilters.splice(index, 1);
            if (!state.dateFilters.length) state.dateFilters.push({field: 'date', start: '', end: ''});
            renderDateFilters();
            container.children[Math.min(index, state.dateFilters.length - 1)].querySelector('select').focus();
            changeDateFilters();
        });
        if (index === state.dateFilters.length - 1 && used.size < Object.keys(GALLERY_DATE_FIELDS).length) {
            button('+', 'filter.date.add', () => {
                const field = Object.keys(GALLERY_DATE_FIELDS).find(value => !used.has(value));
                state.dateFilters.push({field, start: '', end: ''});
                renderDateFilters();
                container.lastElementChild.querySelector('select').focus();
                persistNovelGalleryState();
            });
        }
        group.append(actions);
        container.append(group);
    });
}
