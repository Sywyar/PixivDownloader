'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');
const vm = require('node:vm');

function page() {
    let document;
    class Node {
        constructor(tag) {
            this.tagName = tag; this.children = []; this.style = {}; this.dataset = {};
            this.className = ''; this.value = ''; this.textContent = ''; this.disabled = false;
            this.classList = {
                contains: value => this.className.split(' ').includes(value),
                add: value => { this.className += ' ' + value; },
                remove: value => { this.className = this.className.split(' ').filter(c => c !== value).join(' '); }
            };
        }
        get firstElementChild() { return this.children[0]; }
        get isConnected() { return document.body.contains(this); }
        appendChild(node) { this.children.push(node); node.parentNode = this; }
        removeChild(node) { this.children.splice(this.children.indexOf(node), 1); node.parentNode = null; }
        contains(node) { return this === node || this.children.some(child => child.contains(node)); }
        focus() { document.activeElement = this; }
        addEventListener(type, handler) { this[type] = handler; }
        setAttribute(name, value) { this[name] = value; }
        set innerHTML(value) { assert.equal(value, ''); this.children = []; }
        querySelector(selector) { return this.querySelectorAll(selector)[0] || null; }
        querySelectorAll(selector) {
            const matches = (node, part) => part.startsWith('.')
                ? node.classList.contains(part.slice(1)) : node.tagName === part.replace(/:not.*$/, '');
            const all = this.children.flatMap(node => [node, ...node.querySelectorAll('*')]);
            return all.filter(node => selector === '*' || selector.split(', ').some(s => {
                const parts = s.split(' '), last = parts.pop();
                return matches(node, last) && (!last.includes(':not') || !node.disabled)
                    && (!parts.length || matches(node.parentNode, parts[0]));
            }));
        }
    }
    document = {body: new Node('body'), head: new Node('head'), createElement: tag => new Node(tag),
        createTextNode: text => Object.assign(new Node('#text'), {textContent: text}),
        getElementById: () => null};
    document.activeElement = document.body;
    const window = {PixivInvitesI18n: {t: key => key}};
    vm.runInNewContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/js/invite-modals.js'), 'utf8'), {window, document});
    return {window, document};
}

for (const close of [false, true]) {
    test(close ? '关闭邀请表单后迟到选项不重开选择器或影响新草稿' : '当前邀请表单的选项读取完成后正常打开选择器', async () => {
        const {window, document} = page();
        let resolve;
        window.InviteModals.openInviteFormModal({fetchTags: () => new Promise(done => { resolve = done; })});
        const form = document.body.children[0];
        const button = form.querySelectorAll('button').find(n => n.textContent === 'invite:modal.btn.config-tags');
        button.focus();
        const pending = button.click();
        assert.equal(button.disabled, true);
        assert.equal(document.activeElement, form.querySelector('.invite-modal-close'));
        let current;
        if (close) {
            form.querySelectorAll('button').find(n => n.textContent === 'invite:modal.cancel').click();
            window.InviteModals.openInviteFormModal({prefill: {name: 'new draft'}});
            current = document.body.children[0];
        }
        resolve([{id: 7, name: 'tag'}]);
        await pending;
        assert.equal(document.body.children.length, close ? 1 : 2);
        if (close) {
            assert.equal(document.body.children[0], current);
            assert.equal(current.querySelector('input').value, 'new draft');
        } else {
            assert.equal(document.body.children[1].querySelectorAll('button')
                .filter(n => n.classList.contains('invite-picker-row')).length, 1);
            assert.equal(button.disabled, false);
        }
    });
}
