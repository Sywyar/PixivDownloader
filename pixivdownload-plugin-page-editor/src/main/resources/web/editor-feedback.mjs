const errorGroups = {
    duplicate: ['PAGE_BINDING_DUPLICATE', 'PAGE_HOOK_DUPLICATE'],
    binding: ['PAGE_BINDING_INVALID', 'PAGE_BINDING_UNMATCHED', 'PAGE_BINDING_AMBIGUOUS'],
    capacity: ['PAGE_BINDING_CAPACITY', 'PAGE_HOOK_CAPACITY', 'PAGE_ACTION_CAPACITY'],
    component: ['PAGE_COMPONENT_UNAVAILABLE', 'PAGE_OWNER_UNAVAILABLE', 'PAGE_OWNER_CHANGED'],
    configuration: ['PAGE_CONFIGURATION_INVALID', 'PAGE_COMPONENT_PROPERTIES_INVALID'],
    source: ['PAGE_COMPONENT_SOURCE_INVALID'],
    origin: ['PAGE_COMPONENT_ORIGIN'],
    private: ['PAGE_COMPONENT_PRIVATE'],
    budget: ['PAGE_DOCUMENT_BUDGET', 'PAGE_COMPONENT_BUDGET'],
    preview: ['PAGE_COMPONENT_PREVIEW_LIMIT'],
    storage: ['PAGE_STORAGE_FAILED', 'PAGE_COMPONENT_STORAGE_FAILED', 'PAGE_PUBLICATION_FAILED'],
    conflict: ['PAGE_REVISION_CONFLICT'],
    stale: ['PAGE_PUBLICATION_STALE'],
    unavailable: ['PAGE_PUBLICATION_UNAVAILABLE'],
    shared: ['PAGE_OPERATION_CONFLICT'],
    previous: ['PAGE_PREVIOUS_UNAVAILABLE'],
    approval: ['PAGE_RESOURCE_APPROVAL_REQUIRED'],
    contract: ['PAGE_CONTEXT_MISMATCH', 'PAGE_POLICY_MISMATCH', 'PAGE_PROTOCOL_MISMATCH', 'PAGE_HOOK_CONTRACT_MISMATCH', 'PAGE_HOOK_CONTRACT_INVALID'],
    hook: ['PAGE_HOOK_INVALID', 'PAGE_HOOK_BINDING_INVALID', 'PAGE_HOOK_GROUP_INVALID', 'PAGE_HOOK_ID_INVALID', 'PAGE_ACTION_INVALID'],
    target: ['PAGE_HOOK_TARGET_UNAVAILABLE', 'PAGE_HOOK_TARGET_FORBIDDEN', 'PAGE_HOOK_UNAVAILABLE'],
    structure: ['PAGE_REQUIRED_REGION', 'PAGE_REGION_PARENT', 'PAGE_REGION_DUPLICATE'],
    connection: ['PAGE_CONNECTION_CYCLE', 'PAGE_CONNECTION_INVALID'],
    document: ['PAGE_DOCUMENT_INVALID', 'PAGE_REVISION_INVALID', 'PAGE_NODE_INVALID', 'PAGE_TEXT_INVALID', 'PAGE_ID_INVALID', 'PAGE_ID_CONFLICT', 'PAGE_INSTANCE_INVALID', 'PAGE_COMPONENT_ID_INVALID'],
    style: ['PAGE_STYLE_INVALID']
};
const errorKeys = new Map(Object.entries(errorGroups).flatMap(([key, codes]) => codes.map(code => [code, 'error.' + key])));

export function errorMessage(error, translate) {
    return translate(errorKeys.get(typeof error === 'string' ? error : error?.message) || 'failed');
}

export function createEditorFeedback(signal, translate) {
    let current;
    return (kind, message, description = '') => {
        if (!message) { current?.close(); return; }
        const options = {
            kind: kind === 'busy' ? 'progress' : kind === 'idle' ? 'info' : kind,
            message, description, dismissLabel: translate('close'),
            durationMs: kind === 'busy' ? 0 : kind === 'error' ? 8000 : 5000,
            countdown: kind !== 'busy',
            signal
        };
        if (!current?.update(options)) current = window.PixivFeedback.toast(options);
    };
}
