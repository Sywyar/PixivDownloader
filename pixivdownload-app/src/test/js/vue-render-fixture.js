'use strict';
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

// 使用生产 Vue 和真实响应式调度，仅以轻量节点替代浏览器 DOM 存储。
class Element {
    constructor(tag, text = '') {
        this.tag = tag;
        this.text = text;
        this.children = [];
        this.props = {};
        this.parentNode = null;
        this.isConnected = true;
        if (tag === 'template') this.content = {childNodes: []};
    }
    get childNodes() { return this.children; }
    setAttribute(key, value) { this.props[key] = String(value); }
    getAttribute(key) { return this.props[key] ?? null; }
    appendChild(child) { insert(child, this); return child; }
    remove() { remove(this); }
    contains(node) { return node === this || this.children.some(child => child.contains(node)); }
    get innerHTML() { return this.html || ''; }
    set innerHTML(value) {
        this.children.slice().forEach(remove);
        this.html = String(value);
        if (this.content) this.content.childNodes = value ? [{}] : [];
    }
    querySelectorAll() { return []; }
}
function remove(child) {
    const parent = child.parentNode;
    if (parent) {
        const index = parent.children.indexOf(child);
        if (index >= 0) parent.children.splice(index, 1);
    }
    child.parentNode = null;
}
function insert(child, parent, anchor = null) {
    remove(child);
    const index = anchor ? parent.children.indexOf(anchor) : parent.children.length;
    if (index < 0) throw Error('Invalid renderer anchor');
    parent.children.splice(index, 0, child);
    child.parentNode = parent;
}
function installVue(sandbox) {
    if (!vm.isContext(sandbox)) vm.createContext(sandbox, {codeGeneration: {strings: false, wasm: false}});
    vm.runInContext(fs.readFileSync(path.join(__dirname,
        '../../main/resources/static/vendor/vue/vue.global.prod.js'), 'utf8'), sandbox);
    const Vue = sandbox.Vue;
    const renderer = Vue.createRenderer({
        createElement: tag => new Element(tag), createText: text => new Element('#text', text),
        createComment: () => new Element('#comment'), insert, remove,
        parentNode: n => n.parentNode,
        nextSibling: n => n.parentNode?.children[n.parentNode.children.indexOf(n) + 1] || null,
        setText(n, text) { n.text = text; },
        setElementText(n, text) { n.children.slice().forEach(remove); n.text = text; },
        patchProp(n, key, previous, value) { n.props[key] = value; },
        insertStaticContent(html, parent, anchor) {
            const fragment = new Element('#static');
            fragment.html = html;
            insert(fragment, parent, anchor);
            return [fragment, fragment];
        }
    });
    const runtime = Object.assign({}, Vue, {createApp: renderer.createApp});
    sandbox.window.Vue = runtime;
    return runtime;
}
function all(root, predicate) {
    return [root, ...root.children.flatMap(child => all(child, () => true))].filter(predicate);
}
function textOf(node) { return (node.text || '') + node.children.map(textOf).join(''); }
module.exports = {Element, installVue, all, textOf};
