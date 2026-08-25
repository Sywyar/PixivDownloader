'use strict';
(function (global) {
    global.PixivBatch = global.PixivBatch || {};
    const modules = global.PixivBatch.scheduleSourceRuntimeModules
        || (global.PixivBatch.scheduleSourceRuntimeModules = {});
    modules.normalize = Object.freeze({
        install(ctx) {
            const {CONTRACT_VERSION} = ctx;
    function text(value) {
        return value == null ? '' : String(value).trim();
    }

    function sourceEditorError(code, message) {
        const error = new Error(message);
        error.code = code;
        return error;
    }

    function normalizedModuleUrl(value) {
        const raw = text(value);
        if (!raw || raw.indexOf('\\') >= 0 || /[\u0000-\u001f\u007f]/.test(raw)) return null;
        let parsed;
        try {
            parsed = new URL(raw, window.location.origin);
        } catch (e) {
            return null;
        }
        if (parsed.origin !== window.location.origin || parsed.search || parsed.hash
            || !parsed.pathname.startsWith('/') || parsed.pathname.startsWith('//')
            || !parsed.pathname.endsWith('.js') || parsed.pathname.includes('/../')
            || parsed.pathname.includes('/./') || parsed.pathname.includes('%')) {
            return null;
        }
        return parsed.pathname;
    }

    function sourceIdentity(epoch, source) {
        return [
            epoch,
            source.ownerPluginId,
            source.packageId,
            source.pluginGeneration,
            source.publicationId,
            source.activationToken,
            source.legacyAliases.join(',')
        ].join(':');
    }

    function ownerIdentity(epoch, source) {
        return [epoch, source.ownerPluginId, source.packageId,
            source.pluginGeneration, source.publicationId, source.activationToken].join(':');
    }

    function normalizePresentation(raw) {
        const value = raw && typeof raw === 'object' ? raw : {};
        return Object.freeze({
            displayNamespace: text(value.displayNamespace),
            displayNameKey: text(value.displayNameKey),
            descriptionKey: text(value.descriptionKey),
            iconKey: text(value.iconKey),
            colorToken: text(value.colorToken)
        });
    }

    function i18nToken(value, maxLength) {
        const normalized = text(value);
        return normalized.length <= maxLength && /^[a-z0-9][a-z0-9._-]*$/i.test(normalized)
            ? normalized : '';
    }

    function normalizeFetchLimitPresentation(raw, expectedNamespace) {
        try {
            if (!raw || typeof raw !== 'object') return null;
            if (typeof raw.then === 'function') {
                Promise.resolve(raw).catch(() => {});
                return null;
            }
            const namespace = i18nToken(expectedNamespace, 64);
            const requestedNamespace = i18nToken(raw.namespace, 64);
            const watermarkHintKey = i18nToken(raw.watermarkHintKey, 192);
            const perRunHintKey = i18nToken(raw.perRunHintKey, 192);
            const fullFetchConfirmKey = i18nToken(raw.fullFetchConfirmKey, 192);
            if (!namespace || (requestedNamespace && requestedNamespace !== namespace)
                || (!watermarkHintKey && !perRunHintKey && !fullFetchConfirmKey)) {
                return null;
            }
            return Object.freeze({
                namespace,
                watermarkHintKey,
                perRunHintKey,
                fullFetchConfirmKey
            });
        } catch (e) {
            return null;
        }
    }

    function normalizeFetchLimitMode(value) {
        const mode = text(value);
        return mode === 'watermark' || mode === 'per-run' ? mode : null;
    }

    const CREDENTIAL_PRESENTATION_FIELDS = Object.freeze([
        'boundLabel', 'unboundLabel', 'overrideLabel', 'modalTitle', 'modalIntro',
        'proxyToggleLabel', 'credentialToggleLabel', 'savedCredentialLabel',
        'boundPlaceholder', 'savedSelectionPlaceholder', 'placeholder', 'proxyHint',
        'credentialHint', 'emptyCredentialMessage', 'proxyBadgeLabel',
        'clearProxyConfirm', 'clearCredentialConfirm'
    ]);

    function boundedDisplayText(value, maxLength) {
        const normalized = value == null ? '' : String(value);
        return normalized.length <= maxLength && !/[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/.test(normalized)
            ? normalized : '';
    }

    function normalizeCredentialContribution(raw, descriptor) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
        if (typeof raw.then === 'function') {
            Promise.resolve(raw).catch(() => {});
            return null;
        }
        const value = raw.presentation && typeof raw.presentation === 'object'
            && !Array.isArray(raw.presentation) ? raw.presentation : {};
        const presentation = {};
        CREDENTIAL_PRESENTATION_FIELDS.forEach(name => {
            const normalized = boundedDisplayText(value[name], 4096);
            if (normalized) presentation[name] = normalized;
        });
        const namespace = i18nToken(
            descriptor && descriptor.presentation && descriptor.presentation.displayNamespace, 64);
        const requestedNamespace = i18nToken(value.namespace, 64);
        const confirmKey = property => {
            const key = i18nToken(value[property], 192);
            return namespace && requestedNamespace === namespace && key
                ? `${namespace}:${key}` : null;
        };
        presentation.clearProxyConfirmI18nKey = confirmKey('clearProxyConfirmKey');
        presentation.clearCredentialConfirmI18nKey = confirmKey('clearCredentialConfirmKey');
        return Object.freeze({
            supportsCredential: raw.supportsCredential === true,
            supportsProxy: raw.supportsProxy === true,
            presentation: Object.freeze(presentation)
        });
    }

    function normalizeCredentialTaskPresentation(raw) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
        if (typeof raw.then === 'function') {
            Promise.resolve(raw).catch(() => {});
            return null;
        }
        const tone = text(raw.lightTone);
        return Object.freeze({
            statusLabel: boundedDisplayText(raw.statusLabel, 4096) || null,
            lightTone: ['green', 'yellow', 'red', 'gray'].includes(tone) ? tone : null,
            lightText: boundedDisplayText(raw.lightText, 4096) || null,
            suspended: raw.suspended === true,
            manualRecoveryRequired: raw.manualRecoveryRequired === true
        });
    }

    function machineToken(value, maxLength) {
        const normalized = text(value);
        return normalized.length <= maxLength && /^[A-Za-z][A-Za-z0-9._-]*$/.test(normalized)
            ? normalized : '';
    }

    function normalizeCredentialPolicyAction(raw) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
        const actionId = machineToken(raw.actionId, 128);
        const label = boundedDisplayText(raw.label, 4096);
        if (!actionId || !label) return null;
        const tone = text(raw.tone);
        let prompt = null;
        if (raw.prompt && typeof raw.prompt === 'object' && !Array.isArray(raw.prompt)) {
            const parameterName = machineToken(raw.prompt.parameterName, 128);
            const message = boundedDisplayText(raw.prompt.message, 4096);
            const inputType = text(raw.prompt.inputType);
            const min = Number(raw.prompt.min);
            const step = Number(raw.prompt.step);
            if (parameterName && message && (inputType === 'number' || inputType === 'text')) {
                prompt = Object.freeze({
                    parameterName,
                    message,
                    defaultValue: boundedDisplayText(raw.prompt.defaultValue, 256),
                    inputType,
                    min: Number.isFinite(min) ? min : null,
                    step: Number.isFinite(step) && step > 0 ? step : null
                });
            }
        }
        return Object.freeze({
            actionId,
            label,
            tone: ['danger', 'primary', 'secondary'].includes(tone) ? tone : 'secondary',
            confirmMessage: boundedDisplayText(raw.confirmMessage, 4096) || null,
            prompt
        });
    }

    function normalizeCredentialPolicyGroup(raw, entry) {
        if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return null;
        const identity = raw.identity && typeof raw.identity === 'object'
            && !Array.isArray(raw.identity) ? raw.identity : {};
        const ownerPluginId = machineToken(identity.ownerPluginId, 128);
        const policyId = machineToken(identity.policyId, 128);
        const publicationId = Number(identity.publicationId);
        const accountKey = boundedDisplayText(identity.accountKey, 1024).trim();
        const suspendReason = machineToken(identity.suspendReason, 128);
        const suspendCode = machineToken(identity.suspendCode, 160);
        if (!ownerPluginId || ownerPluginId !== entry.descriptor.ownerPluginId
                || !policyId || !Number.isSafeInteger(publicationId)
                || publicationId !== entry.descriptor.publicationId
                || !accountKey || !suspendReason || !suspendCode) return null;
        const title = boundedDisplayText(raw.title, 4096);
        const description = boundedDisplayText(raw.description, 8192);
        const actions = Array.isArray(raw.actions)
            ? raw.actions.map(normalizeCredentialPolicyAction).filter(Boolean) : [];
        if (!title || !description || !actions.length) return null;
        const normalizedIdentity = Object.freeze({
            ownerPluginId, policyId, publicationId, accountKey, suspendReason, suspendCode
        });
        return Object.freeze({
            sourceType: entry.descriptor.sourceType,
            identity: normalizedIdentity,
            identityKey: JSON.stringify(normalizedIdentity),
            title,
            description,
            actions: Object.freeze(actions)
        });
    }

    function normalizeCredentialOperationResult(raw) {
        const value = raw && typeof raw === 'object' && !Array.isArray(raw) ? raw : {};
        const status = text(value.status);
        return Object.freeze({
            ok: value.ok === true,
            status: ['bound', 'missing', 'revoked', 'applied', 'unchanged', 'failed']
                .includes(status) ? status : (value.ok === true ? 'applied' : 'failed'),
            error: boundedDisplayText(value.error, 4096) || null
        });
    }

    function normalizeCredentialValidation(value) {
        if (value == null || value === '') return null;
        return boundedDisplayText(value, 4096) || 'invalid credential';
    }

    function normalizedOperationPromise(value, normalizer) {
        return value && typeof value.then === 'function'
            ? Promise.resolve(value).then(normalizer)
            : normalizer(value);
    }

    function normalizeSource(epoch, raw) {
        if (!raw || typeof raw !== 'object') return null;
        const sourceType = text(raw.sourceType);
        const ownerPluginId = text(raw.ownerPluginId);
        const packageId = text(raw.packageId);
        const activationToken = text(raw.activationToken);
        const definitionSchema = text(raw.definitionSchema);
        const definitionVersion = Number(raw.definitionVersion);
        const pluginGeneration = Number(raw.pluginGeneration);
        const publicationId = Number(raw.publicationId);
        if (!sourceType || !ownerPluginId || !packageId || !activationToken || !definitionSchema
            || !Number.isInteger(definitionVersion) || definitionVersion <= 0
            || !Number.isSafeInteger(pluginGeneration) || pluginGeneration < 0
            || !Number.isSafeInteger(publicationId) || publicationId <= 0) {
            return null;
        }
        const frontend = raw.frontend && typeof raw.frontend === 'object'
            ? {
                contractVersion: Number(raw.frontend.contractVersion),
                moduleUrl: normalizedModuleUrl(raw.frontend.moduleUrl)
            }
            : null;
        const normalizedFrontend = frontend
            && frontend.contractVersion === CONTRACT_VERSION && frontend.moduleUrl
            ? Object.freeze(frontend)
            : null;
        const acquisitionModes = Array.isArray(raw.acquisitionModes)
            ? Object.freeze(Array.from(new Set(raw.acquisitionModes.map(text).filter(Boolean))))
            : Object.freeze([]);
        const possibleWorkTypes = Array.isArray(raw.possibleWorkTypes)
            ? Object.freeze(Array.from(new Set(raw.possibleWorkTypes.map(text).filter(Boolean))))
            : Object.freeze([]);
        const legacyAliases = Array.isArray(raw.legacyAliases)
            ? Object.freeze(Array.from(new Set(raw.legacyAliases.map(text).filter(Boolean))))
            : Object.freeze([]);
        const source = {
            sourceType,
            legacyAliases,
            ownerPluginId,
            packageId,
            pluginGeneration,
            publicationId,
            activationToken,
            definitionSchema,
            definitionVersion,
            presentation: normalizePresentation(raw.presentation),
            acquisitionModes,
            possibleWorkTypes,
            frontend: normalizedFrontend
        };
        source.identity = sourceIdentity(epoch, source);
        return Object.freeze(source);
    }

    function normalizeManifest(raw) {
        const epoch = text(raw && raw.epoch);
        const revision = Number(raw && raw.revision);
        if (!epoch || !Number.isSafeInteger(revision) || revision < 0) {
            throw new Error('invalid schedule source manifest');
        }
        const descriptors = new Map();
        const sources = Array.isArray(raw.sources) ? raw.sources : [];
        sources.forEach(item => {
            const source = normalizeSource(epoch, item);
            if (!source) return;
            if (descriptors.has(source.sourceType)) {
                throw new Error('duplicate schedule source type');
            }
            descriptors.set(source.sourceType, source);
        });
        const aliases = new Map();
        descriptors.forEach(source => source.legacyAliases.forEach(alias => {
            if (alias === source.sourceType || descriptors.has(alias) || aliases.has(alias)) {
                throw new Error('conflicting schedule source alias');
            }
            aliases.set(alias, source.sourceType);
        }));
        const identity = epoch + ':' + revision + ':' + Array.from(descriptors.values())
            .map(source => source.identity).join('|');
        return {epoch, revision, identity, descriptors, aliases};
    }


            Object.assign(ctx, {
                text, sourceEditorError, normalizedModuleUrl, sourceIdentity, ownerIdentity,
                normalizePresentation, i18nToken, normalizeFetchLimitPresentation,
                normalizeFetchLimitMode, boundedDisplayText, normalizeCredentialContribution,
                normalizeCredentialTaskPresentation, machineToken, normalizeCredentialPolicyAction,
                normalizeCredentialPolicyGroup, normalizeCredentialOperationResult,
                normalizeCredentialValidation, normalizedOperationPromise, normalizeSource,
                normalizeManifest
            });
        }
    });
})(window);