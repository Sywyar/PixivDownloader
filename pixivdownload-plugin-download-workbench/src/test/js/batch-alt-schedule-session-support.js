'use strict';
const {readFileSync} = require('node:fs');
const {resolve} = require('node:path');
const vm = require('node:vm');
const {MiniElement} = require('./pixiv-layout-feedback-test-dom');

class Element extends MiniElement {
    remove() { this.parentNode?.removeChild(this); }
    append(...nodes) { nodes.forEach(node => this.appendChild(node)); }
    prepend(...nodes) { nodes.reverse().forEach(node => this.insertBefore(node, this.children[0] || null)); }
    after(node) { this.parentNode.insertBefore(node, this.nextSibling); }
    replaceChildren(...nodes) { this.children.slice().forEach(node => this.removeChild(node)); this.append(...nodes); }
    matches(selector) {
        if (selector.includes(',')) return selector.split(',').some(value => this.matches(value.trim()));
        if (selector.endsWith(':checked')) return this.checked && this.matches(selector.slice(0,-8));
        if (selector.includes(' ')) {
            const [parent,child]=selector.split(' ');
            return this.matches(child) && !!this.parentNode?.closest(parent);
        }
        return super.matches(selector);
    }
    get previousElementSibling() { return this.parentNode?.children[this.parentNode.children.indexOf(this)-1] || null; }
}

function setup(overrides={}) {
    const requests=[],toasts=[],handlers=new Map();
    let current=true,failure=false,confirm=false,drawer=null;
    const params={kind:'illust',source:{word:'original',order:'date_d',sMode:'s_tag',mode:'r18',maxPages:-1},
        filters:{content:'r18',bookmarksMin:99},download:{fileNameTemplate:'{author_id}',mediaQuality:82},
        fetchLimit:7,custom:{preserved:true}};
    const task={id:42,name:'Saved search',sourceType:'search',stateVersion:9,sourceActivationToken:'activation-a',
        paramsJson:JSON.stringify(params),triggerKind:'cron',cronExpr:'0 0 10 * * ?',...overrides};
    const document={createElement:tag=>new Element(tag,document),addEventListener(){}};
    document.body=document.createElement('body');
    document.getElementById=id=>document.body.querySelector('#'+id);
    document.querySelector=query=>document.body.querySelector(query);
    document.querySelectorAll=query=>document.body.querySelectorAll(query);
    const panel=document.createElement('div');panel.id='abModePanel';document.body.append(panel);
    const context=vm.createContext({document,console,URLSearchParams,AbortController,setTimeout,clearTimeout});
    context.window=context;context.addEventListener=()=>{};context.scrollTo=()=>{};
    context.PixivBatch={};context.PixivBatchAlt={schedule:{},modes:{}};
    for(const name of ['commitQueueItemPatch','addItemsToQueue','removeFromQueue','renderQueue','updateStats',
        'syncAllResultsQueueState','getCookie','getCookieFmt','getStoredCookie','setStoredCookie','removeStoredCookie',
        'parseCookieToHeaderString','getCookieHeaderStringFor','enterScheduleMode','loadScheduleTasks','startSchedulePolling',
        'stopSchedulePolling','renderScheduleMode','renderScheduleTaskList','scheduleStatusLight','openScheduleSnapshot',
        'toggleDock','animateWorkspace'])context[name]=()=>{};
    const read=file=>readFileSync(resolve(__dirname,'../../main/resources/static/pixiv-batch-alt',file),'utf8');
    for(const file of ['alt-core.js','alt-state.js','alt-filters.js','alt-settings.js','alt-modes.js','alt-mode-discovery.js',
        'alt-extensions.js','../pixiv-batch/pixiv-schedule-defaults.js','../pixiv-batch/media-settings.js','alt-schedule-presentation.js',
        'alt-schedule-pixiv.js','alt-schedule-session.js','alt-schedule-editor.js'])
        vm.runInContext(read(file),context);
    const lease=sourceType=>({sourceType,activationToken:'activation-a',signal:new AbortController().signal,
        assertCurrent(){if(!current)throw new Error('Stale source');}});
    context.PixivBatch.scheduleSources={
        registerModule(url,init){init({descriptors:['search','user-new','user-request','series','my-bookmarks','follow-latest','collection']
            .map(sourceType=>({sourceType})),registerSource:(type,handler)=>handlers.set(type,handler)});},
        activationLease:lease,restoreTask:value=>handlers.get(value.sourceType).restore(value),
        captureForMode(mode,ctx){
            const [sourceType,handler]=Array.from(handlers).find(([,value])=>value.matches(ctx)) || [];
            if(!handler)throw new Error('Unavailable');
            return {...handler.capture(ctx),sourceType,activationToken:'activation-a'};
        }
    };
    context.PixivBatch.queueTypes={resolveSelectionForMode:kind=>kind==='request'?'illust':kind,
        typesForDataSource:()=>[{type:'illust'},{type:'novel'}],acquisition:()=>({}),
        acquisitionList:()=>[],contributionsOf:()=>[]};
    vm.runInContext(read('../pixiv-batch/pixiv-schedule-sources.js'),context);
    Object.assign(context,{bt:(key,fallback)=>fallback||key,abToast:(...args)=>toasts.push(args),
        abConfirm:async()=>confirm,
        openDrawer:value=>{drawer=value;document.body.append(value.body,value.footer);},
        closeDrawer:()=>{drawer?.body.remove();drawer?.footer.remove();drawer=null;},
        scheduleHttpError:async()=>new Error('Version conflict'),
        fetch:async(url,init)=>{requests.push({url,body:JSON.parse(init.body),method:init.method});
            return {ok:!failure,json:async()=>({...task,stateVersion:10})};}});
    const run=code=>vm.runInContext(code,context);
    run(`isAdmin=true; state.mode='schedule';
        renderStage=()=>{
            const panel=document.getElementById('abModePanel');panel.replaceChildren();panel.dataset.mode=state.mode;
            const composer=el('div','ab-composer');const input=el('input');
            input.id=state.mode==='user'?'abUserInput':'abSearchInput';
            input.value=state.mode==='user'?userState.input:searchState.word;
            composer.append(input);panel.append(composer);mountScheduleEdit(panel);
        };`);
    return {context,task,params,requests,toasts,run,panel,handlers,field:id=>document.getElementById(id),
        get drawer(){return drawer;},stale(){current=false;},fail(){failure=true;},confirm(value){confirm=value;},
        options:()=>panel.querySelector('.ab-schedule-options').listeners.get('click')[0](),
        save:async()=>{const root=run('scheduleState.editing.element');
            await root.querySelector('.ab-btn--primary').listeners.get('click')[0]();}};
}

module.exports = {setup};
