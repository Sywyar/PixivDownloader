'use strict';

const fs = require('fs');
const path = require('path');
const vm = require('vm');
const assert = require('assert');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/static/js/pixiv-feedback.js'), 'utf8');

class FakeClassList {
    constructor(node) {
        this.node = node;
    }

    add(name) {
        const names = new Set(String(this.node.className || '').split(/\s+/).filter(Boolean));
        names.add(name);
        this.node.className = Array.from(names).join(' ');
    }

    remove(name) {
        this.node.className = String(this.node.className || '').split(/\s+/)
            .filter(value => value && value !== name).join(' ');
    }
}

class FakeElement {
    constructor(document, tagName) {
        this.ownerDocument = document;
        this.tagName = String(tagName).toUpperCase();
        this.children = [];
        this.parentNode = null;
        this.className = '';
        this.classList = new FakeClassList(this);
        this.dataset = {};
        this.attributes = {};
        this.listeners = {};
        this.disabled = false;
        this.value = '';
        this.textContent = '';
    }

    appendChild(child) {
        child.parentNode = this;
        this.children.push(child);
        return child;
    }

    remove() {
        if (!this.parentNode) return;
        this.parentNode.children = this.parentNode.children.filter(child => child !== this);
        this.parentNode = null;
    }

    setAttribute(name, value) {
        this.attributes[name] = String(value);
    }

    getAttribute(name) {
        return Object.prototype.hasOwnProperty.call(this.attributes, name) ? this.attributes[name] : null;
    }

    addEventListener(type, listener) {
        (this.listeners[type] || (this.listeners[type] = [])).push(listener);
    }

    emit(type, event) {
        (this.listeners[type] || []).forEach(listener => listener(event || {target: this}));
    }

    focus() {
        this.ownerDocument.activeElement = this;
    }

    querySelectorAll(selector) {
        const result = [];
        const wantsButton = selector.includes('button');
        const wantsInput = selector.includes('input');
        walk(this, node => {
            if (node === this || node.disabled) return;
            if ((wantsButton && node.tagName === 'BUTTON') || (wantsInput && node.tagName === 'INPUT')) {
                result.push(node);
            }
        });
        return result;
    }
}

function walk(root, visit) {
    visit(root);
    root.children.forEach(child => walk(child, visit));
}

function find(root, predicate) {
    let match = null;
    walk(root, node => {
        if (!match && predicate(node)) match = node;
    });
    return match;
}

function createDocument() {
    const listeners = {};
    const document = {
        activeElement: null,
        createElement(tagName) {
            return new FakeElement(document, tagName);
        },
        addEventListener(type, listener) {
            (listeners[type] || (listeners[type] = [])).push(listener);
        },
        removeEventListener(type, listener) {
            listeners[type] = (listeners[type] || []).filter(candidate => candidate !== listener);
        },
        contains(node) {
            let found = false;
            walk(document.body, candidate => { if (candidate === node) found = true; });
            return found;
        },
        emit(type, event) {
            (listeners[type] || []).slice().forEach(listener => listener(event));
        }
    };
    document.documentElement = {lang: 'zh-CN'};
    document.body = new FakeElement(document, 'body');
    document.activeElement = document.body;
    return document;
}

function loadFeedback() {
    const document = createDocument();
    const assigned = [];
    const opened = [];
    const window = {
        setTimeout,
        clearTimeout,
        location: {
            href: 'http://localhost:8080/page',
            origin: 'http://localhost:8080',
            assign(value) { assigned.push(value); }
        },
        open(value, target, features) { opened.push({value, target, features}); },
        PixivI18n: {
            create() {
                return Promise.resolve({
                    t(key, fallback, vars) {
                        return Object.keys(vars || {}).reduce((text, name) =>
                            text.split('{' + name + '}').join(String(vars[name])), fallback);
                    }
                });
            }
        }
    };
    const context = vm.createContext({
        window, document, console, Date, Promise, Array, Object, String, Number, Math, Set, URL
    });
    vm.runInContext(source, context, {filename: 'pixiv-feedback.js'});
    return {document, window, assigned, opened, feedback: window.PixivFeedback};
}

function nextTurn() {
    return new Promise(resolve => setImmediate(resolve));
}

function actionButton(document, action) {
    return find(document.body, node => node.dataset && node.dataset.feedbackAction === action);
}

function actionContainer(button) {
    return button.parentNode;
}

function clickEvent(target, values) {
    return Object.assign({
        target,
        button: 0,
        defaultPrevented: false,
        preventDefault() { this.defaultPrevented = true; },
        stopImmediatePropagation() { this.propagationStopped = true; }
    }, values || {});
}

(async function run() {
    let passed = 0;
    function ok(condition, label) {
        assert.ok(condition, label);
        passed++;
    }

    {
        const {document, feedback} = loadFeedback();
        const pending = feedback.confirm({
            title: 'Confirm title',
            message: 'Confirm message',
            confirmLabel: 'Confirm',
            cancelLabel: 'Cancel'
        });
        await nextTurn();
        const accept = actionButton(document, 'accept');
        const panel = find(document.body, node => node.attributes.role === 'dialog');
        const message = find(document.body, node => node.className === 'pixiv-feedback-message');
        ok(accept && accept.textContent === 'Confirm', 'confirm renders the supplied accept label');
        ok(panel != null, 'confirm exposes an accessible dialog role');
        ok(panel.attributes['aria-describedby'] === message.id,
            'a titled dialog associates its message as the accessible description');
        ok(document.activeElement.dataset.feedbackAction === 'cancel',
            'confirm initially focuses the safe cancel action');
        actionContainer(accept).emit('click', {target: accept});
        ok(await pending === true, 'confirm resolves true from the accept action');
        ok(document.body.children.length === 0, 'confirm removes its overlay after resolution');
    }

    {
        const {document, feedback} = loadFeedback();
        const pending = feedback.prompt({
            title: 'Input title',
            message: 'Input message',
            value: '60',
            inputType: 'number',
            min: 60,
            confirmLabel: 'Confirm',
            cancelLabel: 'Cancel'
        });
        await nextTurn();
        const input = find(document.body, node => node.tagName === 'INPUT');
        const message = find(document.body, node => node.className === 'pixiv-feedback-message');
        input.value = '90';
        const accept = actionButton(document, 'accept');
        actionContainer(accept).emit('click', {target: accept});
        ok(await pending === '90', 'prompt resolves the edited input value');
        ok(input.type === 'number' && input.min === '60', 'prompt applies the supplied input constraints');
        ok(input.attributes['aria-labelledby'] === message.id,
            'prompt input uses the question as its accessible label');
    }

    {
        const {document, feedback} = loadFeedback();
        const first = feedback.confirm({message: 'First', confirmLabel: 'Yes', cancelLabel: 'No'});
        const second = feedback.confirm({message: 'Second', confirmLabel: 'Yes', cancelLabel: 'No'});
        await nextTurn();
        ok(document.body.children.length === 1, 'dialog requests are serialized');
        let accept = actionButton(document, 'accept');
        actionContainer(accept).emit('click', {target: accept});
        ok(await first === true, 'the first queued dialog resolves independently');
        await nextTurn();
        ok(document.body.children.length === 1, 'the second dialog opens after the first closes');
        const cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        ok(await second === false, 'the second confirm resolves false from cancel');
    }

    {
        const {document, feedback} = loadFeedback();
        const pending = feedback.confirm({message: 'Escape', confirmLabel: 'Yes', cancelLabel: 'No'});
        await nextTurn();
        let propagationStopped = false;
        document.emit('keydown', {
            key: 'Escape',
            preventDefault() {},
            stopImmediatePropagation() { propagationStopped = true; }
        });
        ok(await pending === false, 'Escape cancels a confirm dialog');
        ok(propagationStopped, 'Escape does not reach an underlying page modal');
    }

    {
        const {document, feedback} = loadFeedback();
        const trigger = document.createElement('button');
        document.body.appendChild(trigger);
        trigger.focus();
        const pending = feedback.confirm({message: 'Restore focus', confirmLabel: 'Yes', cancelLabel: 'No'});
        await nextTurn();
        const cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        await pending;
        ok(document.activeElement === trigger, 'dialog restores focus to the invoking control');
    }

    {
        const {document} = loadFeedback();
        const link = document.createElement('a');
        link.setAttribute('href', '/pixiv-gallery.html');
        const event = clickEvent(link);
        document.emit('click', event);
        ok(!event.defaultPrevented && document.body.children.length === 0,
            'same-origin links retain native navigation without a dialog');
    }

    {
        const {document, opened} = loadFeedback();
        const link = document.createElement('a');
        link.setAttribute('href', 'https://example.com/path');
        link.setAttribute('target', '_blank');
        const event = clickEvent(link);
        document.emit('click', event);
        await nextTurn();
        const message = find(document.body, node => node.className === 'pixiv-feedback-message');
        ok(event.defaultPrevented && event.propagationStopped,
            'external links are stopped before browser navigation');
        ok(message && message.textContent.includes('https://example.com/path'),
            'external-link confirmation displays the normalized destination');
        ok(document.activeElement.dataset.feedbackAction === 'cancel',
            'external-link confirmation initially focuses cancel');
        const accept = actionButton(document, 'accept');
        actionContainer(accept).emit('click', {target: accept});
        await nextTurn();
        ok(opened.length === 1 && opened[0].target === '_blank'
            && opened[0].features === 'noopener,noreferrer',
            'confirmed target-blank links open with opener isolation');
    }

    {
        const repoRoot = path.resolve(__dirname, '../../../..');
        const missing = [];
        fs.readdirSync(repoRoot, {withFileTypes: true})
            .filter(entry => entry.isDirectory() && /^pixivdownload-(app|plugin-)/.test(entry.name))
            .forEach(entry => {
                const staticRoot = path.join(repoRoot, entry.name, 'src', 'main', 'resources', 'static');
                if (!fs.existsSync(staticRoot)) return;
                fs.readdirSync(staticRoot)
                    .filter(name => name.endsWith('.html') && name !== 'index.html')
                    .forEach(name => {
                        const html = fs.readFileSync(path.join(staticRoot, name), 'utf8');
                        if (!html.includes('/js/pixiv-feedback.js') || !html.includes('/css/pixiv-feedback.css')) {
                            missing.push(entry.name + '/' + name);
                        }
                    });
            });
        ok(missing.length === 0, 'all site pages load the external-link guard: ' + missing.join(', '));
    }

    console.log(`pixiv-feedback.test.js: ${passed} assertions passed`);
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
