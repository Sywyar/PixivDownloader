const assert = require('node:assert/strict');
const test = require('node:test');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const source = fs.readFileSync(path.join(__dirname, '../../main/resources/userscripts/pixiv-batch-downloader-import.user.js'), 'utf8');
const flush = async () => { for (let i=0;i<20;i++) await Promise.resolve(); };
function browser(storage = new Map()) {
    const listeners = {}, calls = [], alerts = [], timers = [];
    let offline = false;
    const context = vm.createContext({URL, TextEncoder, Date, navigator:{language:'en-US'}, crypto:{randomUUID:()=> 'session'},
        window:{location:{origin:'https://www.pixiv.net'},addEventListener:(name,callback)=>listeners[name]=callback},
        GM_getValue:(key,fallback)=>storage.has(key)?storage.get(key):fallback,
        GM_setValue:(key,value)=>storage.set(key,value), GM_deleteValue:key=>storage.delete(key), GM_listValues:()=>[...storage.keys()],
        GM_registerMenuCommand:()=>{}, alert:text=>alerts.push(text), prompt:()=>null,
        setTimeout:fn=>timers.push(fn),
        GM_xmlhttpRequest:options=>{
            calls.push(options);
            if(offline) return options.onerror();
            options.onload({status:200, finalUrl:options.url, responseText:JSON.stringify(options.method==='GET'?{token:'once'}:{code:'IMPORTED'})});
        }
    });
    vm.runInContext(source,context);
    return {storage, calls, alerts, timers, offline:()=>offline=true, online:()=>offline=false,
        event:(name,data)=>listeners[name]?.({detail:{data}})};
}
const meta = (id=42,type=0,pages=1)=>({idNum:id,type,title:'Title',pageCount:pages,xRestrict:0,aiType:1,
    userId:'84',user:'Author',tags:['tag'],novelMeta:{content:'Novel content'}});
// 上游成功事件只携带作品、标签页和实际路径，不回传下载请求的 taskBatch。
const success = (id='42_p0',name='/downloads/custom.png')=>({id,tabId:2,blobURLFront:'blob:https://www.pixiv.net/download',browserSetFilename:name});
test('all four types upload complete events automatically without copy or confirmation', async()=>{
    const b=browser(); await flush();
    for(let type=0;type<4;type++) {
        const id=42+type;
        b.event('addResult',meta(id,type));
        b.event('downloadStart',{});
        b.event('downloadSuccess',success(type<2?`${id}_p0`:String(id)));
        await flush();
    }
    const posts=b.calls.filter(x=>x.method==='POST');
    assert.equal(posts.length,4);
    assert.deepEqual(posts.map(x=>JSON.parse(x.data).metadata.type),[0,1,2,3]);
    assert.equal(posts[0].anonymous,true);
    assert.equal(posts[0].headers['X-Import-Token'],'once');
    assert.ok(posts.every(call=>new URL(call.url).pathname==='/api/pixiv-batch-downloader-import'));
    assert.ok(b.calls.filter(call=>call.method==='GET').every(call=>
        new URL(call.url).pathname==='/api/pixiv-batch-downloader-import/token'));
    assert.ok(b.calls.every(call=>call.headers.Origin==='https://www.pixiv.net' && call.anonymous));
    assert.equal(b.alerts.length,0);
    assert.equal(b.storage.size,0);
});
test('partial, skipped and unconfirmed downloads do not become completed gallery works',async()=>{
    const b=browser(); await flush();
    b.event('addResult',meta(42,1,2));
    b.event('downloadStart',{});
    const first=success();
    b.event('downloadSuccess',first); await flush();
    b.event('skipDownload',{id:'42_p1'});
    b.event('downloadComplete',{}); await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadSuccess',{...first,id:'42_p1',noReply:true});await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadSuccess',{...first,id:'42_p1',browserSetFilename:'/downloads/other.png'});await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(JSON.parse(b.calls.find(x=>x.method==='POST').data).files.length,2);
});
test('offline pending work survives page reload and retries automatically',async()=>{
    const b=browser();await flush();b.offline();
    b.event('addResult',meta()); b.event('downloadStart',{}); b.event('downloadSuccess',success());await flush();
    assert.equal(b.storage.size,1);
    const restored=browser(b.storage);await flush();
    assert.equal(restored.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(restored.storage.size,0);
});
test('duplicate success is deduplicated and conflicting paths remain blocked',async()=>{
    const b=browser();await flush();b.offline();
    b.event('addResult',meta(42,1,2));
    b.event('downloadStart',{});
    const first=success();
    b.event('downloadSuccess',first); b.event('downloadSuccess',first);await flush();
    assert.equal(JSON.parse([...b.storage.values()][0]).files.length,1);
    b.event('downloadSuccess',{...first,browserSetFilename:'/downloads/conflict.png'});
    b.online(); await b.timers[0]();await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,0);
    assert.equal(JSON.parse([...b.storage.values()][0]).error,'AMBIGUOUS_PAGE');
});

test('success before download start and after a new crawl cannot reuse prior metadata',async()=>{
    const b=browser();await flush();
    b.event('addResult',meta());b.event('downloadSuccess',success());await flush();
    assert.equal(b.calls.length,0);
    b.event('downloadStart',{});b.event('crawlStart',{});
    b.event('downloadSuccess',success());await flush();
    assert.equal(b.calls.length,0);
});

test('pause and resume complete the same manga while a new crawl keeps its pages separate',async()=>{
    const b=browser();await flush();
    b.event('addResult',meta(42,1,2));b.event('downloadStart',{});
    b.event('downloadSuccess',success());await flush();
    b.event('downloadPause',{});b.event('downloadStart',{});
    b.event('downloadSuccess',success('42_p1','/downloads/page-1.png'));await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    const completed=JSON.parse(b.calls.find(x=>x.method==='POST').data);
    assert.ok(Number.isSafeInteger(completed.taskBatch) && completed.taskBatch>0);
    b.event('crawlStart',{});b.event('addResult',meta(42,1,2));b.event('downloadStart',{});
    b.event('downloadSuccess',success('42_p1','/downloads/new-page-1.png'));await flush();
    assert.equal(b.calls.filter(x=>x.method==='POST').length,1);
    assert.equal(JSON.parse([...b.storage.values()][0]).files.length,1);
});
