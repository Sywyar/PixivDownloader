package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载页 Web UiSlot 槽位「字符串贡献主路径 Vue 渲染 + 命令式贡献 / 回退」契约的静态守卫：保证页面初始化后
 * 每个已开出的 {@code <template data-qt-slot>} 都映射到模板原位的稳定 {@code [data-vue-slot]} 宿主。纯字符串
 * descriptor.slots 贡献正常经 Vue 挂载；Vue 不可用、挂载失败或贡献含命令式对象时，才在同一宿主内走 fallback。
 * 模板锚点保留供 manifest 刷新后的卸载 / 恢复复用。纯静态资源 / 装配断言（不启 Spring 上下文），与
 * {@link NovelSearchVueRenderingContractTest} 同一形态。运行态行为另由 {@code src/test/js/pixiv-vue.test.js}、
 * {@code batch-queue-types.test.js} 验证。
 * <p>具体守住：
 * <ul>
 *   <li>共享 helper {@code pixiv-vue.js} 暴露幂等的 {@code prepareSlotHosts} + {@code anchorFor}，宿主插在模板
 *       **原开槽位置**（{@code insertBefore} 到模板之前），target **只经 {@code getAttribute} 精确比较、绝不拼进选择器**；</li>
 *   <li>{@code mountInto} 在挂载抛错时**还原宿主既有命令式 fallback**（先快照子节点、失败时还原再上抛）——
 *       一次失败的 Vue 升级绝不让槽位 / slot 变空白；</li>
 *   <li>{@code batch-queue-types.js} 的 {@code renderSlots} 为 async，并由 {@code bootstrap -> refresh -> install}
 *       的 await 链保证 init 时序；纯字符串贡献主路径经 {@code PixivVue.mountOn(anchor.host, component)} 逐锚点挂载，
 *       fallback 在同一宿主内追加字符串或挂载命令式对象，且不把 target 拼进选择器；</li>
 *   <li>manifest 刷新、失效或 dispose 时，旧记录先逆序 {@code app.unmount()}、再逆序执行命令式 cleanup，最后清空
 *       稳定宿主；模板锚点与宿主均保留，避免 reload 重复创建或失去恢复位置；</li>
 *   <li>{@code pixiv-batch.html} 加载 {@code /js/pixiv-vue.js}，且为作品类型插件可贡献的每个 UI 槽位 target 都开了
 *       对应 {@code <template data-qt-slot>} 锚点；</li>
 *   <li>{@code pixiv-batch.css}：非空宿主 {@code [data-vue-slot] { display:contents }}（Vue 内容作为父容器真实子节点
 *       参与布局），空宿主 {@code [data-vue-slot]:empty { display:none }}；{@code .kind-switcher} 分隔线 {@code :not(:first-child)}
 *       且为 display:contents 宿主内的 kind 选项 label 补左分隔线（与命令式视觉一致）。</li>
 * </ul>
 */
@DisplayName("Web UiSlot Vue 契约守卫：9 槽位主路径 Vue 渲染 + 命令式回退 + 失败不吞 fallback")
class WebUiSlotVueHostContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String VUE_HELPER = "js/pixiv-vue.js";
    private static final String QUEUE_TYPES = "pixiv-batch/batch-queue-types.js";
    private static final String BATCH_HTML = "pixiv-batch.html";
    private static final String BATCH_CSS = "pixiv-batch/pixiv-batch.css";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = WebUiSlotVueHostContractTest.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new NoSuchFileException(path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** 截取 {@code source} 中 [startMarker, endMarker) 区间（不含 endMarker），把顺序断言限定在单个函数体内。 */
    private static String sliceBetween(String source, String startMarker, String endMarker) {
        int start = source.indexOf(startMarker);
        assertThat(start).as("源码缺少起始标记: " + startMarker).isGreaterThanOrEqualTo(0);
        int end = source.indexOf(endMarker, start + startMarker.length());
        assertThat(end).as("源码缺少结束标记: " + endMarker).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    @DisplayName("pixiv-vue.js 暴露 prepareSlotHosts/anchorFor，宿主插在模板原开槽位置(insertBefore)而非父容器末尾")
    void helperExposesIdempotentHostPreparation() throws IOException {
        String js = read(VUE_HELPER);
        assertThat(js).as("应有幂等的槽位宿主准备入口 prepareSlotHosts").contains("function prepareSlotHosts(");
        assertThat(js).as("PixivVue 命名空间应导出 prepareSlotHosts").contains("prepareSlotHosts: prepareSlotHosts");
        assertThat(js).as("应有 anchorFor 定位宿主").contains("function anchorFor(");
        assertThat(js).as("应有共享的「确保模板宿主」内部函数").contains("function ensureHostForTemplate(");
        assertThat(js)
                .as("宿主应插在模板**原位**(insertBefore 到模板之前)，使非空 Vue 内容出现在该 slot 的真实位置")
                .contains("parent.insertBefore(host, tpl)");
        assertThat(js)
                .as("不应再把宿主统一 append 到模板父容器末尾（会把 settings-card 等挪到容器末尾、偏离原槽位）")
                .doesNotContain("parent.appendChild(host)");
    }

    @Test
    @DisplayName("target 安全：宿主定位只经 getAttribute 精确比较，绝不把 target 拼进选择器(无 CSS SyntaxError 风险)")
    void targetNeverInterpolatedIntoSelector() throws IOException {
        String js = read(VUE_HELPER);
        assertThat(js)
                .as("应经固定字面存在式选择器 + getAttribute 精确比较定位（queryByAttrValue）")
                .contains("function queryByAttrValue(")
                .contains("getAttribute(attr) === value");
        assertThat(js).as("不得把 target 拼进 data-qt-slot 选择器").doesNotContain("data-qt-slot=\"' +");
        assertThat(js).as("不得把 target 拼进 data-vue-slot 选择器").doesNotContain("data-vue-slot=\"' +");
        assertThat(js).as("不得用 querySelector 拼 target 值").doesNotContain("querySelector('[data-vue-slot=\"");
    }

    @Test
    @DisplayName("mountInto 失败前不吞 fallback：挂载前快照宿主子节点、挂载抛错时还原再上抛(由 mount/mountOn 收敛为 null)")
    void mountIntoPreservesFallbackOnFailure() throws IOException {
        String js = read(VUE_HELPER);
        assertThat(js).as("应有快照 / 还原宿主子节点的内部函数")
                .contains("function snapshotChildNodes(")
                .contains("function restoreChildNodes(");
        String mountInto = sliceBetween(js, "function mountInto(", "function mount(");
        assertThat(mountInto).as("挂载前快照宿主既有命令式 fallback").contains("snapshotChildNodes(el)");
        assertThat(mountInto)
                .as("挂载抛错时还原 fallback 并上抛（交由 mount / mountOn 收敛为 null，调用方保留 fallback）")
                .contains("restoreChildNodes(el, fallback)")
                .contains("throw e");
    }

    @Test
    @DisplayName("renderSlots 字符串主路径逐锚点走 Vue，失败/命令式贡献回退到稳定宿主并可完整清理")
    void renderSlotsRendersViaVueMainPathWithImperativeFallback() throws IOException {
        String js = read(QUEUE_TYPES);
        String mountSlot = sliceBetween(js, "async function mountSlot(", "function contributionCleanup(");
        assertThat(mountSlot)
                .as("只有纯字符串贡献进入 Vue 主路径，并合并为单个组件 template")
                .contains("contributions.some(value => typeof value !== 'string')")
                .contains("const component = {template: contributions.join('')}");
        assertThat(mountSlot)
                .as("Vue 主路径应逐个已解析的物理锚点挂到其稳定宿主")
                .contains("handles.push(await helper.mountOn(anchor.host, component))");
        assertThat(mountSlot)
                .as("全部锚点都返回 app 才算挂载成功；部分成功也必须先卸载再回退")
                .contains("handles.some(handle => !(handle && handle.app))")
                .contains("handle.app.unmount()")
                .contains("record.apps.push(handle.app)");

        String fallback = sliceBetween(js, "function injectSlotFallback(", "async function renderSlotsNow(");
        assertThat(fallback)
                .as("fallback 应在稳定宿主内部追加字符串，并把非字符串贡献交给命令式 mount")
                .contains("anchor.host.insertAdjacentHTML('beforeend', html)")
                .contains("mountNodeContribution(anchor, value, record)");

        String cleanup = sliceBetween(js, "function cleanupSlotRecord(", "function clearRenderedSlots(");
        int unmountAt = cleanup.indexOf("app.unmount()");
        int cleanupAt = cleanup.indexOf("cleanup()");
        int clearHostAt = cleanup.indexOf("clearSlotHost(anchor.host)");
        assertThat(unmountAt).as("清理记录应卸载 Vue app").isGreaterThanOrEqualTo(0);
        assertThat(cleanupAt).as("清理记录应执行命令式 contribution cleanup").isGreaterThan(unmountAt);
        assertThat(clearHostAt).as("最后应清空稳定宿主，移除残留 DOM").isGreaterThan(cleanupAt);

        String clearRendered = sliceBetween(js, "function clearRenderedSlots(", "async function mountSlot(");
        assertThat(clearRendered)
                .as("全量清理应让在途渲染失效，逆序清理所有记录并清空登记表")
                .contains("++slotRenderSequence")
                .contains("Array.from(slotMounts.values()).reverse().forEach(cleanupSlotRecord)")
                .contains("slotMounts.clear()");

        String renderNow = sliceBetween(js, "async function renderSlotsNow(", "function renderSlots() {");
        int prepareAt = renderNow.indexOf("prepareSlotHosts(");
        int anchorsAt = renderNow.indexOf("slotAnchors(target)");
        int mountAt = renderNow.indexOf("await mountSlot(");
        int injectAt = renderNow.indexOf("injectSlotFallback(");
        assertThat(prepareAt).as("renderSlotsNow 应先备 Vue 宿主").isGreaterThanOrEqualTo(0);
        assertThat(anchorsAt).as("renderSlotsNow 应据保留的模板解析稳定锚点记录").isGreaterThanOrEqualTo(0);
        assertThat(mountAt).as("renderSlotsNow 应走 Vue 主路径 mountSlot").isGreaterThanOrEqualTo(0);
        assertThat(injectAt).as("renderSlotsNow 应有同宿主 fallback injectSlotFallback").isGreaterThanOrEqualTo(0);
        assertThat(prepareAt).as("先准备宿主，再解析本轮锚点").isLessThan(anchorsAt);
        assertThat(anchorsAt).as("先解析锚点，再尝试 Vue 挂载").isLessThan(mountAt);
        assertThat(mountAt)
                .as("Vue 主路径在前、命令式回退在后")
                .isLessThan(injectAt);
        assertThat(renderNow)
                .as("只有 mountSlot 未成功才进入 fallback")
                .contains("if (!await mountSlot(target, contributions, record.anchors, record)) {")
                .contains("cleanupSlotRecord(record)")
                .contains("slotMounts.delete(target)")
                .as("模板锚点必须保留供卸载后 reload 复用")
                .doesNotContain("template[data-qt-slot]').forEach(t => t.remove())");

        String render = sliceBetween(js, "function renderSlots() {", "async function bootstrap() {");
        assertThat(render)
                .as("renderSlots 应先失效并清理旧挂载，再把本轮接到串行尾队列并返回可等待 Promise")
                .contains("clearRenderedSlots()")
                .contains("slotRenderTail.catch(() => undefined)")
                .contains(".then(() => renderSlotsNow(snapshot, sequence))")
                .contains("slotRenderTail = queued.catch(error => {")
                .contains("return queued;");
    }

    @Test
    @DisplayName("槽位锚点安全且稳定：固定字面 selector + getAttribute 精确匹配，宿主原位创建并复用")
    void renderSlotsDoesNotInterpolateTargetIntoSelector() throws IOException {
        String js = read(QUEUE_TYPES);
        String templates = sliceBetween(js, "function templatesForTarget(", "function directSlotHost(");
        assertThat(templates)
                .as("据 target 定位模板锚点应用固定字面选择器 + getAttribute 精确比较")
                .contains("querySelectorAll('template[data-qt-slot]')")
                .contains("marker.getAttribute('data-qt-slot') === target");

        String directHost = sliceBetween(js, "function directSlotHost(", "function slotAnchors(");
        assertThat(directHost)
                .as("同父已有宿主按属性精确值复用；缺失时在模板原位创建")
                .contains("child.getAttribute('data-vue-slot') === target")
                .contains("host.setAttribute('data-vue-slot', target)")
                .contains("parent.insertBefore(host, marker)");

        String anchors = sliceBetween(js, "function slotAnchors(", "function clearSlotHost(");
        assertThat(anchors)
                .as("每个模板都应携带 marker 与解析后的 host，供挂载和命令式 cleanup 使用")
                .contains("templatesForTarget(target)")
                .contains("{marker, host: directSlotHost(marker, target)}");

        assertThat(js)
                .as("不得把任意 target 拼进 data-qt-slot / data-vue-slot 选择器")
                .doesNotContain("data-qt-slot=\"' +")
                .doesNotContain("data-vue-slot=\"' +")
                .doesNotContain("querySelector('[data-vue-slot=\"");
    }

    @Test
    @DisplayName("init 时序安全：bootstrap await refresh，install await renderSlots 后才完成发布")
    void renderSlotsAwaitedByBootstrapForInitTiming() throws IOException {
        String js = read(QUEUE_TYPES);
        assertThat(js)
                .as("renderSlotsNow 执行异步 Vue 挂载，renderSlots 返回串行化后的可等待 Promise")
                .contains("async function renderSlotsNow(")
                .contains("function renderSlots() {")
                .contains("return queued;");
        String bootstrap = sliceBetween(js, "async function bootstrap() {", "function dispose() {");
        assertThat(bootstrap)
                .as("bootstrap 应先启用槽位渲染，再 await 整个 refresh/install 链")
                .contains("slotsBootstrapped = true")
                .contains("await refresh(false, true)");
        String install = sliceBetween(js, "async function install(manifest) {", "async function fetchData(");
        assertThat(install)
                .as("活动 publication 安装完成前应 await renderSlots，保证 init 读取控件时槽位已就位")
                .contains("if (slotsBootstrapped && isCandidateCurrent(activation)) await renderSlots()");
    }

    @Test
    @DisplayName("pixiv-batch.html 加载 /js/pixiv-vue.js 且保留下载类型 UI 槽位锚点")
    void downloadPageAnchorsMatchExposedUiSlots() throws IOException {
        String html = read(BATCH_HTML);
        assertThat(html).as("下载页必须加载共享 Vue helper").contains("src=\"/js/pixiv-vue.js\"");

        List<String> targets = List.of(
                "cookie-tools",
                "kind-option-user",
                "kind-option-search",
                "kind-option-quick",
                "quick-actions-bookmarks",
                "quick-actions-mine",
                "import-hint",
                "search-filter",
                "settings-card");

        for (String target : targets) {
            assertThat(html)
                    .as("下载类型插件可贡献的槽位 target '" + target
                            + "' 必须在 pixiv-batch.html 有对应 <template data-qt-slot> 锚点")
                    .contains("data-qt-slot=\"" + target + "\"");
        }
    }

    @Test
    @DisplayName("pixiv-batch.css：非空宿主 display:contents（参与父布局）、空宿主 display:none（不占 gap/空盒）")
    void vueSlotHostLayoutIsTransparentWhenFilledHiddenWhenEmpty() throws IOException {
        String css = read(BATCH_CSS);
        assertThat(css)
                .as("非空 Vue 宿主应 display:contents：其 Vue 子节点作为父容器真实子节点参与 grid/flex 布局")
                .contains("[data-vue-slot] {")
                .contains("display: contents");
        String emptyRule = sliceBetween(css, "[data-vue-slot]:empty", "}");
        assertThat(emptyRule)
                .as("空的 Vue 宿主必须 display:none（避免在 flex/grid 容器多占 gap / 插入可见空盒）")
                .contains("display: none");
    }

    @Test
    @DisplayName("pixiv-batch.css 的 .kind-switcher 分隔线 :not(:first-child) + 为 display:contents 宿主内 kind 选项补分隔线")
    void kindSwitcherDividerDecoupledFromAdjacency() throws IOException {
        String css = read(BATCH_CSS);
        assertThat(css)
                .as(".kind-switcher 分隔线应用 :not(:first-child)（与 .quick-kind-switcher 同款），不依赖严格相邻")
                .contains(".kind-switcher label:not(:first-child)");
        assertThat(css)
                .as("不应再用相邻选择器 .kind-switcher label + label")
                .doesNotContain(".kind-switcher label + label");
        assertThat(css)
                .as("Vue 主路径下 kind 选项 <label> 在 display:contents 宿主内、不再是 .kind-switcher 直接子节点 → "
                        + "需为宿主内 label 补左分隔线（与命令式路径视觉一致）")
                .contains(".kind-switcher [data-vue-slot] > label");
    }
}
