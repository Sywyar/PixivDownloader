import {attachPage, createOperationTarget, type PageComponentModule} from './page-components.mjs';
const target = createOperationTarget('independent:submit');
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
        return {destroy() { root.replaceChildren(); }};
    }
};
// @ts-expect-error 页面目标必须是 owner 持有的操作实例。
void attachPage({pageId: 'independent:page', root, regions: {}, targets: {form: 'independent:submit'}, title() {}, t: key => key});
void component;
