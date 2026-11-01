import {attachPage, createOperationTarget, type PageComponentModule} from './page-components.mjs';
const target = createOperationTarget('independent:submit', state => { void state.ready; }, async signal => {
    const response = await fetch('/api/independent/prepare', {method: 'POST', signal});
    return response.json();
});
await target.prepare();
if (target.ready()) void target.request();
const root = document.createElement('main');
const mounted = await attachPage({
    pageId: 'independent:page', root, regions: {form: root}, targets: {form: target},
    title: value => { document.title = value; }, t: key => key
});
await mounted.destroy();
const component: PageComponentModule<{label: string}> = {
    mount(root, context) {
        root.textContent = context.properties.label;
        if (context.interaction) context.onCleanup(context.interaction.onState(state => {
            if (state) context.interaction?.respond(state.id, 'proof');
        }));
        if (context.interaction) {
            void context.interaction.prepare();
            context.interaction.invalidate('expired');
        }
        return {destroy() { root.replaceChildren(); }};
    }
};
// @ts-expect-error 页面目标必须是 owner 持有的操作实例。
void attachPage({pageId: 'independent:page', root, regions: {}, targets: {form: 'independent:submit'}, title() {}, t: key => key});
void component;
