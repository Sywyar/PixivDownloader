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

    removeAttribute(name) { delete this.attributes[name]; }

    animate(keyframes, options) {
        const timing = {...options};
        const animation = {
            timing, currentTime: 0, playState: 'running',
            effect: {updateTiming(value) { Object.assign(timing, value); }},
            pause() { this.playState = 'paused'; },
            play() { this.playState = 'running'; },
            cancel() { this.playState = 'idle'; }
        };
        this.animation = animation;
        return animation;
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

    closest(selector) {
        for (let node = this; node; node = node.parentNode) {
            if (selector === 'details:not([open])' && node.tagName === 'DETAILS' && !node.open) return node;
        }
        return null;
    }

    querySelectorAll(selector) {
        const result = [];
        const wantsButton = selector.includes('button');
        const wantsInput = selector.includes('input');
        const wantsSummary = selector.includes('summary');
        walk(this, node => {
            if (node === this || node.disabled) return;
            if ((wantsButton && node.tagName === 'BUTTON') || (wantsInput && node.tagName === 'INPUT') || (wantsSummary && node.tagName === 'SUMMARY')) {
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

function loadFeedback(extras) {
    const document = createDocument();
    const assigned = [];
    const opened = [];
    const window = {
        setTimeout,
        clearTimeout,
        performance,
        matchMedia: () => ({matches: false, addEventListener() {}, removeEventListener() {}}),
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
    Object.assign(window, extras || {});
    const context = vm.createContext({
        window, document, console, Date, Promise, Array, Object, String, Number, Math, Set, URL, AbortController
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
        let complete, signal;
        const pending = feedback.prompt({
            message: 'CSS declarations', confirmLabel: 'Apply', cancelLabel: 'Cancel',
            multiline: true, validationErrorLabel: 'Try again',
            validate(value, abort) { signal = abort; return new Promise(resolve => { complete = resolve; }); }
        });
        await nextTurn();
        const input = find(document.body, node => node.tagName === 'TEXTAREA');
        input.value = 'color: red;\npadding: 8px;';
        const enter = clickEvent(input, {key: 'Enter'});
        input.emit('keydown', enter);
        ok(!enter.defaultPrevented, 'multiline Enter preserves a newline');
        let apply = actionButton(document, 'accept');
        actionContainer(apply).emit('click', {target: apply});
        ok(apply.disabled && input.readOnly, 'validation admits one request and freezes its input');
        complete('Invalid CSS');
        await nextTurn();
        ok(document.contains(input) && input.value.includes('padding') && !input.readOnly, 'validation failure retains editable text');
        ok(input.getAttribute('aria-invalid') === 'true', 'validation failure is accessible');
        actionContainer(apply).emit('click', {target: apply});
        const cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        ok(await pending === null && signal.aborted, 'cancel aborts pending validation');
        complete(null);
        await nextTurn();
        ok(!document.contains(input), 'late validation cannot restore a closed dialog');
    }

    {
        const loads = [], disposed = [];
        const {document, feedback} = loadFeedback({PixivI18n: {
            create(options) {
                return new Promise(resolve => loads.push({lang: options.lang, resolve}));
            }
        }});
        const client = name => ({
            t(key, fallback) { return name + ':' + key; },
            dispose() { disposed.push(name); }
        });
        document.documentElement.lang = 'en-US';
        const old = feedback.confirmExternalLink('https://example.invalid/old');
        document.documentElement.lang = 'zh-CN';
        const current = feedback.confirmExternalLink('https://example.invalid/current');
        loads[1].resolve(client('current'));
        await nextTurn();
        ok(find(document.body, node => node.className === 'pixiv-feedback-message').textContent
            === 'current:external-link.confirm.message', '新语言先完成时正常显示外链确认');
        let cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        ok(await current === false, '取消确认不授权外部跳转');
        loads[0].resolve(client('old'));
        await nextTurn();
        assert.deepStrictEqual(disposed, ['old'], '迟到的旧语言词典只释放一次');
        cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        ok(await old === false, '释放旧词典仍保留已排队确认的取消行为');
        const reused = feedback.confirmExternalLink('https://example.invalid/reused');
        await nextTurn();
        ok(loads.length === 2 && disposed.length === 1, '同语言复用当前词典，不重复请求或释放');
        cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        await reused;
        document.documentElement.lang = 'en-US';
        const replaced = feedback.confirmExternalLink('https://example.invalid/replaced');
        loads[2].resolve(client('replacement'));
        await nextTurn();
        assert.deepStrictEqual(disposed, ['old', 'current'], '已完成的旧语言词典在替换时释放');
        cancel = actionButton(document, 'cancel');
        actionContainer(cancel).emit('click', {target: cancel});
        await replaced;
    }

    {
        const {document, feedback} = loadFeedback();
        const controller = new AbortController();
        const pending = feedback.choose({
            message: 'Choose a path action', confirmLabel: 'Continue', cancelLabel: 'Wait',
            choices: [{value: 'TRUNCATE', label: 'Truncate', disabled: true},
                {value: 'DEFAULT_NAME', label: 'Default name'}, {value: 'CANCEL', label: 'Cancel work'}],
            rememberLabel: 'Use for the rest of this batch', signal: controller.signal
        });
        await nextTurn();
        const remember = find(document.body, node => node.type === 'checkbox');
        remember.checked = true;
        const accept = actionButton(document, 'accept');
        actionContainer(accept).emit('click', {target: accept});
        const result = await pending;
        ok(result.value === 'DEFAULT_NAME' && result.remember,
            'choice returns an enabled action and explicit batch preference');
        const waiting = feedback.choose({message: 'Another work', confirmLabel: 'Continue', cancelLabel: 'Wait',
            choices: [{value: 'CANCEL', label: 'Cancel work'}], signal: controller.signal});
        await nextTurn();
        controller.abort();
        ok(await waiting === null && document.body.children.length === 0,
            'aborting closes the active dialog without authorizing an action');
        ok(await feedback.choose({signal: controller.signal}) === null,
            'an expired queued dialog never opens');
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
        const {document, feedback} = loadFeedback();
        const payload = '<img src=x onerror=alert(1)>';
        const pending = feedback.confirm({
            message: 'Review package', confirmLabel: 'Install', cancelLabel: 'Cancel',
            sections: [{title: 'Package', fields: [{label: 'Name', value: payload}]},
                {title: 'Checksum', collapsed: true, fields: [{label: 'SHA-256', value: 'a'.repeat(64), mono: true}]}]
        });
        await nextTurn();
        const value = find(document.body, node => node.tagName === 'DD' && node.textContent === payload);
        ok(!!value && value.children.length === 0, 'structured fields retain untrusted markup as literal text');
        ok(!find(document.body, node => node.tagName === 'IMG'), 'structured confirmation never injects HTML');
        const disclosure = find(document.body, node => node.tagName === 'DETAILS');
        ok(!!disclosure && !disclosure.open, 'checksum disclosure starts collapsed');
        const accept = actionButton(document, 'accept');
        accept.focus();
        let prevented = false;
        document.emit('keydown', {key: 'Tab', preventDefault() { prevented = true; }});
        ok(prevented && document.activeElement.tagName === 'SUMMARY', 'Tab wraps to the disclosure summary');
        document.emit('keydown', {key: 'Tab', shiftKey: true, preventDefault() {}});
        ok(document.activeElement === accept, 'Shift Tab wraps from summary to the final action');
        actionContainer(accept).emit('click', {target: accept});
        ok(await pending === true, 'structured confirmation retains acceptance semantics');
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

    {
        const {document, feedback} = loadFeedback();
        const trigger = document.createElement('button'); document.body.appendChild(trigger); trigger.focus();
        const pending = feedback.confirm({message: 'Animated confirmation', confirmLabel: 'Save', cancelLabel: 'Cancel'});
        await nextTurn();
        const backdrop = find(document.body, node => node.tagName === 'DIALOG');
        let finishMotion, closed = false;
        backdrop.close = () => { closed = true; };
        backdrop.getAnimations = () => [{finished: new Promise(resolve => { finishMotion = resolve; })}];
        const accept = actionButton(document, 'accept');
        actionContainer(accept).emit('click', {target: accept});
        ok(await pending === true, 'confirmation resolves before its exit animation finishes');
        ok(closed && backdrop.inert && document.activeElement === trigger, 'closing releases modal interaction and restores focus immediately');
        ok(document.contains(backdrop), 'closing surface remains available to its exit animation');
        finishMotion(); await nextTurn();
        ok(!document.contains(backdrop), 'finished exit releases the dialog DOM');
    }

    {
        const timers = new Map();
        let sequence = 0, clock = 0;
        const motion = new EventTarget();
        motion.matches = false;
        const {document, feedback} = loadFeedback({
            setTimeout(fn, delay) { const id = ++sequence; timers.set(id, {fn, delay}); return id; },
            clearTimeout(id) { timers.delete(id); },
            performance: {now: () => clock},
            matchMedia: () => motion
        });
        const fire = delay => {
            const item = [...timers].find(([, value]) => value.delay === delay);
            assert.ok(item, 'expected scheduled timer: ' + delay);
            timers.delete(item[0]); clock += delay; item[1].fn();
        };
        const controller = new AbortController();
        const handle = feedback.toast({message: 'Saving', kind: 'progress', durationMs: 0, signal: controller.signal});
        const node = find(document.body, node => node.className === 'pixiv-feedback-toast pixiv-feedback-toast--progress');
        ok(timers.size === 0, 'progress remains until the operation settles');
        handle.update({message: 'Saved', description: '<b>plain text</b>', kind: 'success', dismissLabel: 'Close'});
        ok(node.children[1].children[1].textContent === '<b>plain text</b>', 'toast details are plain text');
        ok(node.children[1].getAttribute('role') === 'status', 'success is announced politely');
        node.emit('pointerenter');
        ok(timers.size === 0, 'hover pauses automatic dismissal');
        node.emit('pointerleave');
        fire(3500);
        ok(node.className.includes('--leaving') && document.contains(node), 'timeout starts a fade before removing the toast');
        handle.update({message: 'Conflict', kind: 'error', durationMs: 0, dismissLabel: 'Close'});
        ok(timers.size === 0 && !node.className.includes('--leaving'), 'new feedback cancels an in-flight exit');
        ok(node.children[1].getAttribute('role') === 'alert', 'errors are announced assertively');
        node.children[2].emit('click');
        fire(180);
        ok(!document.contains(node) && !handle.update({message: 'Too late'}), 'dismissed handles cannot revive detached nodes');
        const pending = feedback.toast({message: 'Saved', signal: controller.signal});
        controller.abort();
        ok(timers.size === 0 && !pending.update({message: 'Stale'}), 'page disposal releases timers and rejects late updates');
        feedback.toast({message: 'Already aborted', signal: controller.signal});
        ok(timers.size === 0 && document.body.children[0].children.length === 0, 'aborted pages cannot create a toast');
        const legacy = feedback.toast({message: 'Existing caller'});
        ok([...timers.values()][0].delay === 3500, 'existing callers retain their default timeout');
        legacy.close(); fire(180);
        const counted = feedback.toast({message: 'Countdown', durationMs: 5000, countdown: true, dismissLabel: 'Close'});
        const countedNode = document.body.children[0].children[0];
        const counter = countedNode.children[3];
        ok(counter.textContent === '' && counter.getAttribute('aria-hidden') === 'true', 'bottom border countdown has no numeric or repeated live announcement');
        ok(counter.animation.timing.duration === 5000 && timers.size === 1, 'visual duration matches the single dismissal timer');
        clock += 1250; countedNode.emit('pointerenter');
        clock += 10000; countedNode.emit('focusin'); countedNode.emit('pointerleave');
        ok(counter.animation.currentTime === 1250 && counter.animation.playState === 'paused' && timers.size === 0, 'hover and focus freeze both the border and deadline');
        motion.matches = true; motion.dispatchEvent(new Event('change'));
        ok(counter.animation.timing.easing === 'steps(5, end)' && counter.animation.currentTime === 1250, 'reduced motion changes the border without resetting the countdown');
        motion.matches = false; motion.dispatchEvent(new Event('change'));
        ok(counter.animation.timing.easing === 'linear', 'normal motion restores continuous border shrinkage');
        countedNode.emit('focusout');
        ok(counter.animation.currentTime === 1250 && counter.animation.playState === 'running' && [...timers.values()][0].delay === 3750, 'resume continues from the remaining border length and time');
        const oldAnimation = counter.animation;
        counted.update({message: 'Failure', kind: 'error', countdown: true, durationMs: 8000, dismissLabel: 'Close'});
        ok(oldAnimation.playState === 'idle' && counter.animation.timing.duration === 8000, 'replacement cancels the old animation and starts the new deadline');
        fire(8000);
        ok(countedNode.className.includes('--leaving'), 'errors can auto-dismiss after their countdown');
        fire(180);
        ok(timers.size === 0 && !document.contains(countedNode) && counter.animation.playState === 'idle', 'countdown dismissal releases its timer, animation and DOM');
        feedback.toast({message: 'Delayed callback', countdown: true, durationMs: 1000});
        const delayed = document.body.children[0].children[0];
        clock += 1200; delayed.emit('pointerenter');
        ok(delayed.className.includes('--leaving'), 'entering after the deadline cannot pin an expired countdown');
        fire(180);
    }

    console.log(`pixiv-feedback.test.js: ${passed} assertions passed`);
})().catch(error => {
    console.error(error);
    process.exitCode = 1;
});
