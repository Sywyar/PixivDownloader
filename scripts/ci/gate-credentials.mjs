// 只解释 Actions 表达式中的凭据来源；不把变量名或任意 shell 源码当成权限证明。
function expressions(text) {
    const result = [];
    for (let start = text.indexOf('${{'); start >= 0; start = text.indexOf('${{', start)) {
        let end = start + 3, quoted = false;
        for (; end < text.length; end += 1) {
            if (text[end] === "'") {
                if (quoted && text[end + 1] === "'") end += 1;
                else quoted = !quoted;
            }
            if (!quoted && text.slice(end, end + 2) === '}}') break;
        }
        if (end === text.length) throw new Error('unterminated Actions expression');
        result.push(text.slice(start + 3, end));
        start = end + 2;
    }
    return result;
}

function references(expression) {
    const tokens = expression.match(/'(?:[^']|'')*'|[a-zA-Z_][a-zA-Z0-9_-]*|[^\s]/gu) || [];
    const result = [];
    for (let i = 0; i < tokens.length; i += 1) {
        if (!/^[a-zA-Z_]/u.test(tokens[i]) || ['.', '['].includes(tokens[i - 1])) continue;
        const parts = [tokens[i].toLowerCase()];
        while (i + 1 < tokens.length) {
            if (tokens[i + 1] === '.' && /^[a-zA-Z_]/u.test(tokens[i + 2] || '')) {
                parts.push(tokens[i + 2].toLowerCase()); i += 2;
            } else if (tokens[i + 1] === '[' && /^'(?:[^']|'')*'$/u.test(tokens[i + 2] || '')
                && tokens[i + 3] === ']') {
                parts.push(tokens[i + 2].slice(1, -1).replaceAll("''", "'").toLowerCase()); i += 3;
            } else break;
        }
        result.push(parts);
    }
    return result;
}

export function credentialSources(value, bindings = new Map()) {
    const sources = new Set();
    const visit = (item) => {
        if (typeof item === 'string') {
            for (const expression of expressions(item)) {
                for (const parts of references(expression)) {
                    const [context, key] = parts;
                    if (context === 'secrets') sources.add(key === 'github_token' ? 'github' : 'secret');
                    if (context === 'github' && (!key || key === 'token')) sources.add('github');
                    if (context === 'env' && key === 'actions_runtime_token') sources.add('runtime');
                    if (context === 'env' && /^actions_id_token_request_(?:token|url)$/u.test(key || '')) sources.add('oidc');
                    const reference = parts.join('.');
                    for (const [name, values] of bindings) {
                        if (name === reference || name.startsWith(reference + '.')) values.forEach((source) => sources.add(source));
                    }
                }
            }
        } else if (Array.isArray(item)) item.forEach(visit);
        else if (item && typeof item === 'object') {
            if (/^actions\/create-github-app-token@[0-9a-f]{40}$/u.test(item.uses || '')) sources.add('app');
            Object.values(item).forEach(visit);
        }
    };
    visit(value);
    return sources;
}

// 显式 job map 替换 workflow map；未列出的权限为 none。调用方上限只能缩小权限。
export function effectivePermissions(workflow, job, caller) {
    const selected = job === undefined ? workflow : job;
    const level = (permissions, name) => typeof permissions === 'string'
        ? permissions === 'write-all' ? 'write' : permissions === 'read-all' ? (name === 'id-token' ? 'none' : 'read') : 'none'
        : permissions?.[name] || 'none';
    const names = new Set(['contents', 'id-token', ...Object.keys(typeof selected === 'object' && selected || {}),
        ...Object.keys(typeof caller === 'object' && caller || {})]);
    const rank = ['none', 'read', 'write'];
    return Object.fromEntries([...names].map((name) => [name, caller === undefined ? level(selected, name)
        : rank[Math.min(rank.indexOf(level(selected, name)), rank.indexOf(level(caller, name)))]]));
}

export function privilegedCredentials(value, workflowPermissions, jobPermissions, bindings) {
    const sources = credentialSources(value, bindings);
    const permissions = effectivePermissions(workflowPermissions, jobPermissions);
    return sources.has('secret') || sources.has('app') || sources.has('oidc') || sources.has('runtime')
        || Object.values(permissions).includes('write');
}

// 追踪 YAML 明确引用的输出；不猜测脚本打印的 token，也不把未知输出仅凭名字判成凭据。
export function workflowCredentialBindings(doc) {
    const jobs = new Map(Object.keys(doc.jobs).map((id) => [id, new Map()]));
    const shared = new Map();
    let changed = true;
    const add = (map, name, sources) => {
        const current = map.get(name) || new Set();
        for (const source of sources) if (!current.has(source)) { current.add(source); changed = true; }
        map.set(name, current);
    };
    const outputs = (steps, inherited) => {
        const bindings = new Map(inherited);
        for (const step of steps || []) {
            if (!step.id) continue;
            const prefix = `steps.${step.id.toLowerCase()}.outputs.`;
            if (/^actions\/create-github-app-token@[0-9a-f]{40}$/u.test(step.uses || '')) {
                bindings.set(prefix + 'token', new Set(['app']));
            }
            if (step.gateLocalAction) {
                const inputs = new Map(Object.entries(step.with || {}).map(([name, value]) =>
                    ['inputs.' + name.toLowerCase(), credentialSources(value, bindings)]));
                const internal = outputs(step.gateLocalAction.runs.steps, inputs);
                for (const [name, spec] of Object.entries(step.gateLocalAction.outputs || {})) {
                    bindings.set(prefix + name.toLowerCase(), credentialSources(spec.value, internal));
                }
            }
        }
        return bindings;
    };
    while (changed) {
        changed = false;
        for (const [id, job] of Object.entries(doc.jobs)) {
            const bindings = jobs.get(id);
            for (const [name, values] of shared) add(bindings, name, values);
            for (const [name, values] of outputs(job.steps, shared)) add(bindings, name, values);
            for (const [name, value] of Object.entries(job.outputs || {})) {
                add(shared, `needs.${id.toLowerCase()}.outputs.${name.toLowerCase()}`, credentialSources(value, bindings));
            }
        }
    }
    return jobs;
}
