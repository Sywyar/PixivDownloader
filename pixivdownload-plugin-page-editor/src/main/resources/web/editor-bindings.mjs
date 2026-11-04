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
