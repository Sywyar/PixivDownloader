'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const {Element, installVue, all, textOf} = require('./vue-render-fixture');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/static/js/pixiv-page-sections.js'), 'utf8');
const sections = [
    {id:'links',titleNamespace:'demo',titleI18nKey:'title',navPlacement:'side.links'},
    {id:'body',titleNamespace:'demo',titleI18nKey:'body',moduleUrl:'/demo/body.js',actionHref:'/demo',actionTitleI18nKey:'add'}
];
async function fixture({fallback=false, fail=false}={}) {
    const slot = new Element('div'); slot.setAttribute('data-section-slot','side.sections');
    const head = new Element('head'), errors=[], mounts=[], events=[];
    let language='en', data=sections, slots=[slot], navRefreshes=0;
    const sandbox={console:{warn:(...args)=>errors.push(args),error:(...args)=>errors.push(args)},Promise,
        document:{head,readyState:'complete',querySelectorAll:()=>slots,createElement:tag=>new Element(tag)},
        fetch:async()=>({ok:!fail,status:fail?500:200,json:async()=>data}),
        CustomEvent:class {constructor(type){this.type=type;}},dispatchEvent:e=>events.push(e.type),
        PixivI18n:{create:async()=>{const lang=language;return {tns:(ns,key)=>lang+':'+key};},onLanguageChange(){}},
        PixivNav:{refresh(){navRefreshes++;}}};
    sandbox.window=sandbox;
    const Vue=installVue(sandbox);
    assert.throws(()=>vm.runInContext('new Function("return 1")',sandbox),/Code generation/);
    if(!fallback)sandbox.PixivVue={ensure:async()=>Vue,mountOn:async(el,component)=>{
        const app=Vue.createApp(component); app.mount(el); mounts.push(app); return {app};
    }};
    vm.runInContext(source,sandbox);await sandbox.PixivPageSections.ready();await Vue.nextTick();
    return {slot,head,errors,mounts,events,Vue,api:sandbox.PixivPageSections,
        get navRefreshes(){return navRefreshes;},setLanguage:value=>{language=value;},setData:value=>{data=value;},
        removeSlot:()=>{slots=[];}};
}
test('严格 CSP 下区块真实挂载，语言更新保留骨架与外部子节点',async()=>{
    const f=await fixture();assert.equal(f.mounts.length,1);assert.deepEqual(f.errors,[]);
    const title=all(f.slot,n=>n.props.class==='section-title')[0];
    const nav=all(f.slot,n=>n.tag==='nav')[0];
    const body=all(f.slot,n=>String(n.props.class||'').includes('page-section-body'))[0];
    const externalNav=new Element('a','external nav'), externalBody=new Element('p','external body');
    nav.appendChild(externalNav);body.appendChild(externalBody);
    f.setLanguage('ja');await Promise.all([f.api.refresh(),f.api.refresh()]);await f.Vue.nextTick();
    assert.equal(f.mounts.length,1);assert.equal(all(f.slot,n=>n.props.class==='section-title')[0],title);
    assert.equal(title.text,'ja:title');assert.equal(nav.children[0],externalNav);assert.equal(body.children[0],externalBody);
    assert.equal(f.head.children.length,1);assert.ok(f.navRefreshes>=2);assert.ok(f.events.includes('pixivpagesections:rendered'));
});
test('区块贡献替换复用实例，空贡献和移除宿主释放旧节点',async()=>{
    const f=await fixture();const oldTitle=all(f.slot,n=>n.props.class==='section-title')[0];
    f.setData([{...sections[0],titleI18nKey:'changed'}]);await f.api.mount();await f.Vue.nextTick();
    assert.equal(f.mounts.length,1);assert.equal(all(f.slot,n=>n.props.class==='section-title')[0],oldTitle);
    assert.equal(oldTitle.text,'en:changed');assert.equal(all(f.slot,n=>n.props['data-section-id']==='body').length,0);
    f.setData([]);await f.api.mount();await f.Vue.nextTick();assert.equal(textOf(f.slot),'');
    f.removeSlot();await f.api.mount();assert.equal(f.slot.children.length,0);
});
test('Vue 缺席保持命令式回退，请求失败不伪造贡献',async()=>{
    const fallback=await fixture({fallback:true});assert.match(fallback.slot.innerHTML,/data-nav-slot="side.links"/);
    const failed=await fixture({fail:true});assert.equal(failed.slot.innerHTML,'');assert.equal(failed.mounts.length,0);
});
