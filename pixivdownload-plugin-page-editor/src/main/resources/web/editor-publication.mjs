export function publicationLabels(catalog, label, t) {
    const pages = catalog.pages.map(entry => entry.definition);
    const components = catalog.components;
    const page = id => {
        const found = pages.find(page => page.id === id);
        if (!found) return t('page') + ' (' + id + ')';
        const name = label(found);
        if (name === id) return t('page') + ' (' + id + ')';
        return pages.filter(page => label(page) === name).length > 1 ? name + ' (' + id + ')' : name;
    };
    const operationName = id => {
        const names = pages.flatMap(page => page.targets.filter(target => target.operation === id && target.labelKey)
            .flatMap(target => {
                const name = label({...target, namespace: page.namespace});
                if (name === target.id) return [];
                const region = page.regions.find(region => region.id === target.region);
                const form = region?.labelKey ? label({...region, namespace: page.namespace}) : '';
                return [form && form !== region.id && form !== name ? form + ' · ' + name : name];
            }));
        return [...new Set(names)].join(' / ') || t('hook.action') + ' (' + id + ')';
    };
    const operationIds = [...new Set(pages.flatMap(page => page.targets.map(target => target.operation)))];
    const operation = id => {
        const name = operationName(id);
        return operationIds.some(other => other !== id && operationName(other) === name) ? name + ' (' + id + ')' : name;
    };
    const policies = new Map();
    for (const entry of components) {
        const definition = entry.definition, id = definition.participation?.policy;
        if (!id) continue;
        const owner = definition.type.split(':')[0], key = JSON.stringify([owner, id]);
        if (!policies.has(key)) policies.set(key, {id, owner, definitions: []});
        policies.get(key).definitions.push(definition);
    }
    const policyName = entry => [...new Set(entry.definitions.map(label).filter(name =>
        name && !entry.definitions.some(definition => definition.type === name)))].join(' / ')
        || t('hook.definition') + ' (' + entry.owner + ' / ' + entry.id + ')';
    const policy = (id, owner) => {
        const entries = [...policies.values()], found = entries.filter(entry => entry.id === id && (!owner || entry.owner === owner));
        return found.map(entry => {
            const name = policyName(entry);
            return entries.some(other => other !== entry && policyName(other) === name)
                ? name + ' (' + entry.owner + ' / ' + id + ')' : name;
        }).join(' / ') || t('hook.definition') + ' (' + (owner ? owner + ' / ' : '') + id + ')';
    };
    return {page, operation, policy};
}

export function publicationSections(plan, t, restoring = false, names = publicationLabels({pages: [], components: []}, () => '', t)) {
    const checks = (values, operation) => values.map(value => {
        // 宿主的 Hook 摘要包含执行时机与目标；不能把后置观察说成提交前验证。
        const hook = /^(.*)@(BEFORE_INVOKE|AFTER_COMPLETION):(.*)$/.exec(value);
        return hook ? names.policy(hook[1]) + ' · ' + t('hook.' + hook[2])
            + (hook[3] === operation ? '' : ' · ' + names.operation(hook[3])) : names.policy(value);
    }).join('\n') || t('publication-none');
    const permissions = new Map();
    for (const permission of plan.permissions) {
        const key = JSON.stringify([permission.owner, permission.policy]);
        if (!permissions.has(key)) permissions.set(key, {label: names.policy(permission.policy, permission.owner), sources: new Set()});
        for (const source of permission.profile.sources) {
            const origin = source.origin === 'self' ? t('publication-self') : source.origin === 'blob:' ? t('publication-local') : source.origin;
            permissions.get(key).sources.add(t('publication-resource-' + source.kind) + ': ' + origin);
        }
    }
    const access = bindings => bindings.map(binding => [
        names.policy(binding.hook, binding.owner) + ' · ' + t('hook.' + binding.phase),
        t(binding.endpoint ? 'hook.endpoint' : 'hook.operation') + ': ' + names.operation(binding.endpoint || binding.operation),
        ...(binding.phase === 'AFTER_COMPLETION' ? [t('hook.completion') + ': ' + t('hook.' + binding.completion)] : []),
        t('hook.order') + ': ' + binding.order,
        binding.fields.length ? binding.fields.map(field =>
            t('publication-field-' + field.source) + ': ' + field.path + ' → ' + field.name).join('\n') : t('publication-fields-none')
    ].join('\n')).join('\n\n') || t('publication-none');
    const hookChanges = (plan.hooks || []).filter(change => change.before.length || change.after.length);
    return [
        {title: t('publication-pages'), paragraphs: [t('publication-pages-help')],
            fields: plan.pages.map(page => ({label: names.page(page),
                value: plan.drafts?.some(draft => draft.page === page)
                    ? t(restoring ? 'publication-previous' : 'publication-draft') : t('publication-refresh')}))},
        {title: t('publication-operations'), paragraphs: plan.impacts.length ? [t('publication-operations-help')] : [t('publication-none')],
            fields: plan.impacts.map(impact => ({label: names.operation(impact.operation),
                value: (JSON.stringify(impact.before) === JSON.stringify(impact.after) ? ''
                    : t('publication-before') + '\n' + checks(impact.before, impact.operation) + '\n')
                    + t('publication-after') + '\n' + checks(impact.after, impact.operation)
                    + '\n' + t('publication-used-by') + ': ' + impact.pages.map(names.page).join(', ')}))},
        {title: t('publication-resources'), paragraphs: [t(permissions.size ? 'publication-resources-help' : 'publication-resources-none')],
            fields: [...permissions.values()].map(permission => ({label: permission.label,
                value: [...permission.sources].join('\n') || t('publication-resources-none')}))},
        ...(hookChanges.length ? [{title: t('publication-data'), paragraphs: [t('publication-data-help')],
            fields: hookChanges.map(change => ({label: names.page(change.page),
                value: (JSON.stringify(change.before) === JSON.stringify(change.after) ? ''
                    : t('publication-before') + '\n' + access(change.before) + '\n\n')
                    + t('publication-after') + '\n' + access(change.after)}))}] : []),
        {title: t('publication-technical'), collapsed: true,
            fields: [
                ...plan.impacts.map(impact => ({label: impact.operation,
                    value: `${impact.before.join(', ') || '—'} → ${impact.after.join(', ') || '—'} (${impact.pages.join(', ')})`, mono: true})),
                ...plan.permissions.map(permission => ({label: `${permission.owner} / ${permission.policy} / ${permission.profile.id}`,
                    value: permission.profile.revision + '\n' + permission.profile.sources.map(source => `${source.kind}: ${source.origin}`).join('\n'), mono: true}))
            ]}
    ];
}

// 批量选择只收集页面身份；草稿修订在预检前读取，正式写入仍需单独确认。
export async function selectPages(pages, current, label, t, signal, restoring = false) {
    const titles = pages.map(label);
    const selected = await window.PixivFeedback.choose({
        title: t(restoring ? 'restore-batch' : 'publish-batch'), message: t(restoring ? 'restore-select-help' : 'publication-select-help'),
        confirmLabel: t('publication-review'), cancelLabel: t('cancel'), signal, multiple: true,
        choices: pages.map((page, index) => ({value: page.id, label: titles.filter(title => title === titles[index]).length > 1 ? titles[index] + ' (' + page.id + ')' : titles[index],
            checked: page.id === current, disabled: page.id === current}))
    });
    return selected?.filter(id => id !== current) ?? null;
}
