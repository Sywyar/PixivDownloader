'use strict';

const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const scheduleRuntimeRoot = path.join(
    __dirname, '../../main/resources/static/pixiv-batch');
const runtimeSource = [
    'batch-schedule-sources-normalize.js',
    'batch-schedule-sources-runtime.js',
    'batch-schedule-sources.js'
].map(file => fs.readFileSync(path.join(scheduleRuntimeRoot, file), 'utf8')).join('\n');
const pixivModuleSource = fs.readFileSync(path.join(
    __dirname,
    '../../main/resources/static/pixiv-batch/pixiv-schedule-sources.js'
), 'utf8');

function source(overrides) {
    return Object.assign({
        sourceType: 'source-a',
        legacyAliases: [],
        ownerPluginId: 'owner-a',
        packageId: 'package-a',
        pluginGeneration: 1,
        publicationId: 11,
        activationToken: 'activation-a',
        definitionSchema: 'example.definition',
        definitionVersion: 1,
        presentation: {
            displayNamespace: 'example',
            displayNameKey: 'source.name',
            descriptionKey: 'source.description',
            iconKey: 'download',
            colorToken: 'blue'
        },
        acquisitionModes: ['user'],
        possibleWorkTypes: ['work-a'],
        frontend: {contractVersion: 1, moduleUrl: '/plugins/source-a.js'}
    }, overrides || {});
}

function manifest(revision, sources, epoch) {
    return {epoch: epoch || 'epoch-a', revision, sources};
}

function harness(manifests, installers, options) {
    const responses = manifests.slice();
    let requestCount = 0;
    const listeners = new Map();
    const document = {
        currentScript: null,
        head: null,
        documentElement: null,
        createElement(tag) {
            assert.equal(tag, 'script');
            return {
                dataset: {},
                async: false,
                src: '',
                onload: null,
                onerror: null,
                remove() {}
            };
        }
    };
    const appendChild = script => {
        queueMicrotask(() => {
            const pathname = new URL(script.src, 'http://localhost').pathname;
            const spec = installers.get(pathname);
            if (!spec) {
                script.onerror();
                return;
            }
            if (spec && spec.never) return;
            const installer = typeof spec === 'function' ? spec : spec.install;
            const evaluatedScript = spec && spec.forgeCurrentScript
                ? Object.assign({dataset: {}}, script, {dataset: Object.assign({}, script.dataset)})
                : script;
            document.currentScript = evaluatedScript;
            installer(context.window.PixivBatch.scheduleSources);
            document.currentScript = null;
            script.onload();
        });
    };
    document.head = {appendChild};
    document.documentElement = document.head;
    const context = vm.createContext({
        console: {warn() {}, log() {}, error() {}},
        URL,
        AbortController,
        CustomEvent: class CustomEvent {
            constructor(type, options) {
                this.type = type;
                this.detail = options && options.detail;
            }
        },
        queueMicrotask,
        setTimeout: options && options.fastTimeout
            ? (callback => setTimeout(callback, 0)) : setTimeout,
        clearTimeout,
        fetch: async () => {
            requestCount += 1;
            const body = responses.shift();
            if (!body) throw new Error('unexpected manifest request');
            return {ok: true, status: 200, json: async () => body};
        },
        document,
        window: {
            location: {origin: 'http://localhost'},
            PixivBatch: {},
            dispatchEvent(event) {
                (listeners.get(event.type) || []).forEach(listener => listener(event));
            },
            addEventListener(type, listener) {
                const values = listeners.get(type) || [];
                values.push(listener);
                listeners.set(type, values);
            }
        }
    });
    vm.runInContext(runtimeSource, context, {filename: 'batch-schedule-sources.js'});
    const runtime = context.window.PixivBatch.scheduleSources;
    const exposed = Object.create(runtime);
    exposed.__test = {document, get requestCount() { return requestCount; }};
    return exposed;
}

function validInitializer(moduleUrl, values) {
    return runtime => runtime.registerModule(moduleUrl, api => {
        api.registerSource(api.descriptors[0].sourceType, Object.assign({
            matches: () => true,
            capture: () => ({params: {source: {id: '1'}}}),
            restore: () => ({mode: 'user'}),
            summary: () => ({kind: 'work-a', sections: []})
        }, values || {}));
    });
}

module.exports = {source, manifest, harness, validInitializer, pixivModuleSource};
