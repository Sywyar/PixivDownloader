const paths = {
    component: 'M12 3 3 8l9 5 9-5-9-5ZM3 8v9l9 5 9-5V8M12 13v9',
    box: 'M4 4h16v16H4zM8 4v16M4 9h16',
    text: 'M4 5h16M12 5v14M8 19h8',
    link: 'm10 13 4-4M8 16l-1 1a4 4 0 0 1-6-6l4-4a4 4 0 0 1 6 0M16 8l1-1a4 4 0 0 1 6 6l-4 4a4 4 0 0 1-6 0',
    header: 'M4 4h16v16H4zM4 9h16M8 6.5h8',
    main: 'M4 4h16v16H4zM8 9h8M8 12h8M8 15h5',
    footer: 'M4 4h16v16H4zM4 15h16M8 17.5h8',
    code: 'm8 6-6 6 6 6m8-12 6 6-6 6M14 4l-4 16',
    properties: 'M4 7h16M4 17h16M8 4v6m8 4v6',
    history: 'M3 4v5h5M4 8a9 9 0 1 1-1 7M12 7v5l3 2',
    search: 'M10.5 3a7.5 7.5 0 1 0 0 15 7.5 7.5 0 0 0 0-15ZM16 16l5 5',
    refresh: 'M20 7a9 9 0 0 0-16 2M20 3v5h-5M4 17a9 9 0 0 0 16-2M4 21v-5h5',
    plus: 'M12 5v14M5 12h14',
    chevron: 'm9 5 7 7-7 7',
    play: 'm8 4 12 8-12 8V4Z',
    library: 'M4 4h5v16H4zM9 4h5v16H9zM16 5l4-1 4 15-4 1z'
};
export function icon(name) {
    return `<svg class="editor-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" focusable="false"><path d="${paths[name] || paths.component}"/></svg>`;
}
export function decorateIcons(root) {
    for (const element of root.querySelectorAll('[data-icon]')) element.innerHTML = icon(element.dataset.icon);
}
