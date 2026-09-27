'use strict';

const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const repo = path.resolve(__dirname, '../../../..');
const staticRoot = path.join(__dirname, '../../main/resources/static');

// 使用真实 Vue 响应式和 renderer，仅用内存节点代替浏览器 DOM。
function node(tag, text = '') { return {tag, text, props: {}, children: [], parent: null}; }
function remove(child) {
    if (child.parent) child.parent.children.splice(child.parent.children.indexOf(child), 1);
    child.parent = null;
}
function all(root, predicate) {
    return (predicate(root) ? [root] : []).concat(root.children.flatMap(child => all(child, predicate)));
}
function byClass(root, cls) { return all(root, n => String(n.props.class || '').split(' ').includes(cls)); }
function textOf(n) { return n.text + n.children.map(textOf).join(''); }

async function fixture(alt) {
    const body = node('body');
    const stats = node('div'), current = node('div'), list = node('div');
    body.children.push(stats, current, list);
    body.children.forEach(n => { n.parent = body; });
    const statsClass = alt ? 'ab-dock-stats' : 'dash-stats';
    stats.props.class = statsClass;
    current.props.id = alt ? 'abCurrentCard' : 'current-card';
    list.props.id = alt ? 'abQueueList' : 'queue-list';
    const get = selector => all(body, n => selector[0] === '#'
        ? n.props.id === selector.slice(1) : n.props.class === selector.slice(1))[0] || null;
    body.querySelector = get;
    const raf = [], errors = [], builds = [], removed = [], cancelled = [];
    let clock = 0, language = 'en';
    const state = {queue: Array.from({length:300}, (_,i) => ({id:String(i), kind:'illust', status:i ? 'pending':'downloading', title:'<unsafe '+i+'>', downloadedCount:0, totalImages:100})), stats:{}, isPaused:false};
    const sandbox = {
        state, document: {body, createElement: tag => node(tag), querySelector:get,
            getElementById:id => id === 'abDockBody' ? body : get('#'+id), contains:n => n === body || !!n.parent},
        console:{warn:(...args)=>errors.push(args),error:(...args)=>errors.push(args)},
        requestAnimationFrame:cb=>raf.push(cb), setTimeout, clearTimeout, performance:{now:()=>clock},
        bt:(key,fallback)=>language+':'+key, abIcon:()=>'<svg></svg>',
        queueDataSourceText:()=> 'source', queueSourceText:()=> 'mode',
        queueItemDisplayTitle:q=>q.title, queueItemCanonicalUrl:q=>'https://example.com/'+q.id,
        queueItemMessage:q=>String(q.downloadedCount), pct:q=>q.downloadedCount,
        progressExtras:q=>{builds.push(q.id);return null;},
        buildQueueItemHtml:q=>{builds.push(q.id);return '<div>'+q.id+':'+q.downloadedCount+'</div>';},
        computeCurrentCardHtml:items=>items[0] ? 'current:'+items[0].downloadedCount : 'empty',
        formatCurrentCardHtml:q=>q ? q.title : '',
        removeFromQueue:id=>{removed.push(id);return true;}, requestQueueItemCancel:id=>cancelled.push(id), abToast() {},
        updateStats(){}, renderQueue(){}, renderCurrent(){}, setCurrent(){}
    };
    sandbox.window = sandbox;
    sandbox.PixivBatch = {state:{state}, queueTypes:{canCancel:q=>q.status==='downloading',queueTags:()=>[]}};
    sandbox.PixivBatchAlt = {};
    vm.createContext(sandbox, {codeGeneration:{strings:false,wasm:false}});
    assert.throws(()=>vm.runInContext('new Function("return 1")',sandbox), /Code generation/);
    vm.runInContext(fs.readFileSync(path.join(repo,'pixivdownload-app/src/main/resources/static/vendor/vue/vue.global.prod.js'),'utf8'),sandbox);
    const Vue = sandbox.Vue;
    const renderer = Vue.createRenderer({
        createElement:tag=>node(tag),createText:text=>node('#text',text),createComment:()=>node('#comment'),
        setText(n,text){n.text=String(text);},setElementText(n,text){n.text=String(text);n.children=[];},
        parentNode:n=>n.parent,nextSibling:n=>n.parent?.children[n.parent.children.indexOf(n)+1]||null,remove,
        insert(child,parent,anchor=null){remove(child);const i=anchor?parent.children.indexOf(anchor):parent.children.length;assert.ok(i>=0);parent.children.splice(i,0,child);child.parent=parent;},
        patchProp(n,key,old,value){n.props[key]=value;}
    });
    sandbox.PixivVue = {ensure:async()=>Vue,mountOn:async(el,component)=>{
        const app=renderer.createApp(component);app.mount(el);return {app,el};
    }};
    vm.runInContext(fs.readFileSync(path.join(staticRoot,alt?'pixiv-batch-alt/alt-queue-vue.js':'pixiv-batch/batch-queue-vue.js'),'utf8'),sandbox);
    const api = alt ? sandbox.PixivBatchAlt.queueVue : sandbox.PixivBatch.queueVue;
    assert.equal(await (alt?api.ensure():api.mountDownloadQueue()),true);
    async function flush(){for(let i=0;i<20;i++){clock+=1000;raf.splice(0).forEach(cb=>cb());await Vue.nextTick();if(!raf.length)break;}assert.equal(raf.length,0);}
    const sync=item=>alt?api.syncList(item):api.syncDownloadList(item);
    sync();await flush();
    state.sseListeners = {};
    sandbox.setInterval = () => 1;
    sandbox.clearInterval = () => {};
    sandbox.setTimeout = () => 1;
    sandbox.clearTimeout = () => {};
    sandbox.mergeUgoiraProgress = (previous, incoming) => ({...previous, ...incoming});
    sandbox.renderQueue = sync;
    sandbox.setCurrent = sandbox.renderCurrent = sync;
    vm.runInContext(fs.readFileSync(path.join(staticRoot, alt
        ? 'pixiv-batch-alt/alt-engine-stream.js' : 'pixiv-batch/batch-sse.js'), 'utf8'), sandbox);
    return {api,Vue,state,body,stats,current,list,builds,errors,removed,cancelled,sync,flush,
        wait: id => sandbox.waitForFinalStatusBySSE(id, 10000),
        emit: (id, event) => state.sseListeners[id].slice().forEach(listener => listener(event)),
        setLanguage(value){language=value;},rowClass:alt?'ab-queue-item':'q-item-host'};
}

for(const alt of [false,true]) {
    test((alt?'侧栏':'经典')+'下载队列在禁止动态编译时挂载，进度仅重新计算变动行',async()=>{
        const f=await fixture(alt);
        const rows=()=>byClass(f.list,f.rowClass);
        assert.equal(rows().length,300);
        const stable=rows()[299];
        const counts={pending:299,success:2,failed:3,active:1,skipped:4};
        (alt?f.api.syncStats:f.api.syncDownloadStats)(counts);
        (alt?f.api.syncSpeed:f.api.syncDownloadSpeed)('8','MiB/s');
        await f.flush();
        const id=value=>all(f.stats,n=>n.props.id===value)[0];
        assert.equal(textOf(id(alt?'abStatPending':'stat-count-pending')),'299');
        assert.equal(textOf(id(alt?'abStatSpeedUnit':'stat-speed-unit')),'MiB/s');
        assert.match(textOf(f.stats),/en:.*queued/);
        if(alt) {
            const click={stopPropagation(){}};
            all(rows()[0],n=>n.tag==='button')[0].props.onClick(click);
            all(rows()[1],n=>n.tag==='button')[0].props.onClick(click);
            assert.deepEqual(f.cancelled,['0']);assert.deepEqual(f.removed,['1']);
            assert.equal(textOf(byClass(rows()[0],'ab-queue-name')[0]),'<unsafe 0>');
        }
        f.builds.length=0;
        const completed = f.wait('0');
        for(let i=0;i<60;i++) f.emit('0', {downloadedCount:i+1});
        await f.flush();
        assert.deepEqual(f.builds,['0']);
        assert.equal(rows()[299],stable);
        assert.match(alt?textOf(rows()[0]):rows()[0].props.innerHTML,/60/);
        assert.match(all(f.current,n=>!!n.props.innerHTML).map(n=>n.props.innerHTML).join(''),/current:60/);
        f.emit('0', {completed:true});
        assert.equal((await completed).completed, true);
        assert.equal(Object.keys(f.state.sseListeners).length, 0);
        // 同一批中的不同作品都必须更新，不能因按帧合并而丢掉前一个作品。
        f.builds.length=0;
        f.state.queue[0].downloadedCount=70;f.sync(f.state.queue[0]);
        f.state.queue[1].downloadedCount=80;f.sync(f.state.queue[1]);
        await f.flush();
        assert.deepEqual(f.builds.sort(),['0','1']);
        f.state.queue.reverse();f.sync();await f.flush();
        assert.equal(rows()[0],stable);
        f.state.queue=[];f.sync();await f.flush();
        assert.equal(rows().length,0);
        assert.match(textOf(f.list),/queue-empty/);
        assert.deepEqual(f.errors,[]);
    });

    test((alt?'侧栏':'经典')+'计划详情在禁止动态编译时响应状态与空列表',async()=>{
        const f=await fixture(alt),box=node('section');box.parent=f.body;f.body.children.push(box);
        box.querySelector=()=>null;
        let model=alt?{startedText:'start',statsText:'one',empty:false,truncated:true,truncatedText:'more',currentHtml:'current',rows:[{key:'a',status:'pending',html:'<div>&lt;unsafe&gt; · AI · waiting</div>'}]}
            :{statusText:'start',statsText:'one',current:{title:'current'},items:[{id:'a',kind:'illust',downloadedCount:1}]};
        f.api.ensureScheduleQueue(7,alt?{boxEl:box,read:()=>model}:{bodyEl:box,read:()=>model});
        await new Promise(resolve=>setImmediate(resolve));await f.flush();
        assert.equal(f.api.isScheduleActive(7),true);
        assert.match(textOf(box),/start/);
        if(alt){
            const rows=all(box,n=>n.props['data-queue-key']==='a');
            assert.equal(rows.length,1);
            assert.equal(rows[0].props.innerHTML,model.rows[0].html);
            assert.equal(rows[0].props['data-status'],'pending');
            assert.equal(byClass(box,'ab-round-current')[0].props.innerHTML,'current');
            model.rows=[{key:'a',status:'completed',html:'<div>completed</div>'}];
            f.api.syncScheduleQueue(7);await f.flush();
            assert.equal(rows[0].props['data-status'],'completed');
            assert.equal(rows[0].props.innerHTML,'<div>completed</div>');
        }
        else assert.equal(byClass(box,'q-item-host').length,1);
        model=alt?{empty:true,emptyText:'empty',rows:[]}:{items:[]};
        f.api.syncScheduleQueue(7);await f.flush();
        assert.equal(alt?all(box,n=>n.props['data-queue-key']==='a').length:byClass(box,'q-item-host').length,0);
        f.api.unmountScheduleQueue(7);
        assert.equal(f.api.isScheduleActive(7),false);
        assert.deepEqual(f.errors,[]);
    });
}
