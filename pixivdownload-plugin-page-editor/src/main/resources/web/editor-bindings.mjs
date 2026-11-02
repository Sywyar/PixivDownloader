const canonical = value => Array.isArray(value) ? value.map(canonical)
    : value && typeof value === 'object' ? Object.fromEntries(Object.keys(value).sort().map(key => [key, canonical(value[key])])) : value;

function compatible(left, right) {
    return left.id === right.id && left.version === right.version
        && ['stateSchema', 'responseSchema'].every(key => JSON.stringify(canonical(JSON.parse(left[key]))) === JSON.stringify(canonical(JSON.parse(right[key]))));
}

// 最近的 owner 表单作用域截断搜索，不能越过不兼容的内层表单绑定到外层。
export function resolveBinding(component, region, page, selected = '') {
    if (!component?.participation) return {candidates: [], target: '', status: 'none'};
    const visited = new Set();
    while (region && !visited.has(region)) {
        visited.add(region);
        const scoped = page.targets.filter(target => target.region === region);
        if (scoped.length) {
            const candidates = scoped.filter(target => target.protocols.some(protocol => compatible(protocol, component.participation.protocol)));
            const target = candidates.find(target => target.id === selected) || (candidates.length === 1 ? candidates[0] : undefined);
            return {candidates, target: target?.id || '', status: target ? 'bound' : candidates.length ? 'ambiguous' : 'unmatched'};
        }
        region = page.regions.find(entry => entry.id === region)?.parent;
    }
    return {candidates: [], target: '', status: 'unmatched'};
}

export function publicationSections(plan, t, restoring = false) {
    return [
        {title: t('publication-pages'), paragraphs: [t('publication-pages-help')],
            fields: plan.pages.map(page => ({label: page,
                value: plan.drafts?.some(draft => draft.page === page)
                    ? t(restoring ? 'publication-previous' : 'publication-draft') : t('publication-refresh')}))},
        {title: t('publication-operations'), fields: plan.impacts.map(impact => ({label: impact.operation,
            value: `${impact.before.join(', ') || t('publication-none')} → ${impact.after.join(', ') || t('publication-none')} (${impact.pages.join(', ')})`}))},
        {title: t('publication-resources'), paragraphs: [t('publication-resources-help')],
            fields: plan.permissions.map(permission => ({label: `${permission.owner} / ${permission.policy} / ${permission.profile.id}`,
                value: permission.profile.sources.map(source => `${source.kind}: ${source.origin}`).join('\n')}))}
    ];
}
