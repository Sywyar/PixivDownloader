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
 * 下载页 Web UiSlot 槽位「主路径 Vue 渲染 + 命令式回退」契约的静态守卫：保证页面初始化后每个已开出的
 * {@code <template data-qt-slot>} 槽位的 descriptor.slots 片段**正常由 Vue 渲染**（mount 进该槽位在模板原位
 * 备好的 {@code [data-vue-slot]} 宿主），命令式 {@code insertAdjacentHTML} 注入**只在 Vue 不可用 / 运行时加载
 * 失败 / 挂载失败时作为回退**。纯静态资源 / 装配断言（不启 Spring 上下文），与 {@link NovelSearchVueRenderingContractTest}
 * 同一形态。运行态行为另由 {@code src/test/js/pixiv-vue.test.js}、{@code batch-queue-types.test.js} 验证。
 * <p>具体守住：
 * <ul>
 *   <li>共享 helper {@code pixiv-vue.js} 暴露幂等的 {@code prepareSlotHosts} + {@code anchorFor}，宿主插在模板
 *       **原开槽位置**（{@code insertBefore} 到模板之前），target **只经 {@code getAttribute} 精确比较、绝不拼进选择器**；</li>
 *   <li>{@code mountInto} 在挂载抛错时**还原宿主既有命令式 fallback**（先快照子节点、失败时还原再上抛）——
 *       一次失败的 Vue 升级绝不让槽位 / slot 变空白；</li>
 *   <li>{@code batch-queue-types.js} 的 {@code renderSlots} 为 async、由 {@code bootstrap} {@code await}（init 时序安全），
 *       **主路径经 {@code window.PixivVue.mount} 把片段作为 Vue 组件 template 渲染**，命令式 {@code insertAdjacentHTML}
 *       仅在挂载未成功时回退，且**不把 target 拼进选择器**（固定字面 {@code template[data-qt-slot]} + getAttribute）；</li>
 *   <li>{@code pixiv-batch.html} 加载 {@code /js/pixiv-vue.js}，且为 novel 插件可贡献的每个 UI 槽位 target 都开了
 *       对应 {@code <template data-qt-slot>} 锚点；</li>
 *   <li>{@code pixiv-batch.css}：非空宿主 {@code [data-vue-slot] { display:contents }}（Vue 内容作为父容器真实子节点
 *       参与布局），空宿主 {@code [data-vue-slot]:empty { display:none }}；{@code .kind-switcher} 分隔线 {@code :not(:first-child)}
 *       且为 display:contents 宿主内的 kind 选项 label 补左分隔线（与命令式视觉一致）。</li>
 * </ul>
 */
@DisplayName("Web UiSlot Vue 契约守卫：8 槽位主路径 Vue 渲染 + 命令式回退 + 失败不吞 fallback")
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
    @DisplayName("renderSlots 主路径走 Vue（PixivVue.mount 渲染片段），命令式 insertAdjacentHTML 仅作回退(挂载失败时)")
    void renderSlotsRendersViaVueMainPathWithImperativeFallback() throws IOException {
        String js = read(QUEUE_TYPES);
        assertThat(js)
                .as("Vue 主路径：经 window.PixivVue.mount 把槽位片段作为 Vue 组件 template 渲染")
                .contains("window.PixivVue.mount(target, { template: html })");
        assertThat(js)
                .as("命令式回退用 insertAdjacentHTML beforebegin（仅 Vue 不可用 / 挂载失败时）")
                .contains("insertAdjacentHTML('beforebegin', html)");

        String render = sliceBetween(js, "async function renderSlots() {", "return {");
        int prepareAt = render.indexOf("prepareSlotHosts(");
        int mountAt = render.indexOf("mountSlotViaVue(");
        int injectAt = render.indexOf("injectSlotImperative(");
        int removeAt = render.indexOf("template[data-qt-slot]').forEach(t => t.remove())");
        assertThat(prepareAt).as("renderSlots 应先备 Vue 宿主").isGreaterThanOrEqualTo(0);
        assertThat(mountAt).as("renderSlots 应走 Vue 主路径 mountSlotViaVue").isGreaterThanOrEqualTo(0);
        assertThat(injectAt).as("renderSlots 应有命令式回退 injectSlotImperative").isGreaterThanOrEqualTo(0);
        assertThat(removeAt).as("renderSlots 末尾移除全部模板").isGreaterThanOrEqualTo(0);
        assertThat(prepareAt).as("先备宿主、再移除模板").isLessThan(removeAt);
        assertThat(mountAt)
                .as("Vue 主路径在前、命令式回退在后（回退仅在挂载未成功时 if(!mounted)）")
                .isLessThan(injectAt);
        assertThat(render)
                .as("命令式注入只在 Vue 挂载未成功时回退").contains("if (!mounted) injectSlotImperative(");
    }

    @Test
    @DisplayName("renderSlots 不把 target 拼进选择器：固定字面 template[data-qt-slot] + getAttribute 精确比较")
    void renderSlotsDoesNotInterpolateTargetIntoSelector() throws IOException {
        String js = read(QUEUE_TYPES);
        assertThat(js).as("renderSlots 不得把 target 拼进 data-qt-slot 选择器").doesNotContain("data-qt-slot=\"' +");
        assertThat(js)
                .as("据 target 定位模板锚点应用固定字面选择器 + getAttribute 精确比较")
                .contains("querySelectorAll('template[data-qt-slot]')")
                .contains("t.getAttribute('data-qt-slot') === target");
    }

    @Test
    @DisplayName("init 时序安全：renderSlots 为 async 且由 bootstrap await（控件就位后 init 才读取 kind/设置卡/筛选）")
    void renderSlotsAwaitedByBootstrapForInitTiming() throws IOException {
        String js = read(QUEUE_TYPES);
        assertThat(js).as("renderSlots 应为 async（主路径异步 Vue 挂载）").contains("async function renderSlots(");
        assertThat(js)
                .as("bootstrap 应 await renderSlots（init `await bootstrap()` → 控件就位后才读取，无竞态）")
                .contains("await renderSlots()");
    }

    @Test
    @DisplayName("pixiv-batch.html 加载 /js/pixiv-vue.js 且保留 novel UI 槽位锚点")
    void downloadPageAnchorsMatchExposedUiSlots() throws IOException {
        String html = read(BATCH_HTML);
        assertThat(html).as("下载页必须加载共享 Vue helper").contains("src=\"/js/pixiv-vue.js\"");

        List<String> targets = List.of(
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
                    .as("novel 插件可贡献的槽位 target '" + target
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
