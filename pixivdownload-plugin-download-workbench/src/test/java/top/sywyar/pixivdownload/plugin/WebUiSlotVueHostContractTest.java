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
 * 下载页 Web UiSlot 槽位的静态页面与布局边界。Vue 主路径、挂载失败回退、命令式贡献清理、恶意 target、
 * init 等待和 publication 切换由 {@code batch-queue-types.test.js} 执行真实脚本验证。
 * <p>具体守住：
 * <ul>
 *   <li>{@code pixiv-batch.html} 加载 {@code /js/pixiv-vue.js}，且为作品类型插件可贡献的每个 UI 槽位 target 都开了
 *       对应 {@code <template data-qt-slot>} 锚点；</li>
 *   <li>{@code pixiv-batch.css}：非空宿主 {@code [data-vue-slot] { display:contents }}（Vue 内容作为父容器真实子节点
 *       参与布局），空宿主 {@code [data-vue-slot]:empty { display:none }}；{@code .kind-switcher} 分隔线 {@code :not(:first-child)}
 *       且为 display:contents 宿主内的 kind 选项 label 补左分隔线（与命令式视觉一致）。</li>
 * </ul>
 */
@DisplayName("Web UiSlot Vue 页面与布局边界")
class WebUiSlotVueHostContractTest {

    private static final String STATIC_ROOT = "static/";
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
