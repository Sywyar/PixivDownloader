package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/** 队列页面的资源装配与样式约束。 */
@DisplayName("队列 Vue 静态装配边界")
class BatchQueueVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = "pixiv-batch.html";
    private static final String QUEUE_VUE_JS = "pixiv-batch/batch-queue-vue.js";
    private static final String BATCH_CSS = "pixiv-batch/pixiv-batch.css";

    private static String read(String resource) throws IOException {
        String path = STATIC_ROOT + resource;
        try (InputStream in = BatchQueueVueRenderingContractTest.class.getClassLoader().getResourceAsStream(path)) {
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
    @DisplayName("下载页在 batch-queue.js 之后加载 batch-queue-vue.js（注册队列 reactive 岛）")
    void downloadPageLoadsQueueVueAfterQueue() throws IOException {
        String html = read(BATCH_HTML);
        int queueAt = html.indexOf("src=\"/pixiv-batch/batch-queue.js\"");
        int queueVueAt = html.indexOf("src=\"/pixiv-batch/batch-queue-vue.js\"");
        assertThat(queueAt).as("下载页应加载 batch-queue.js").isGreaterThanOrEqualTo(0);
        assertThat(queueVueAt).as("下载页应加载 batch-queue-vue.js").isGreaterThanOrEqualTo(0);
        assertThat(queueVueAt).as("batch-queue-vue.js 必须在 batch-queue.js 之后加载（依赖其全局格式化函数）").isGreaterThan(queueAt);
        assertThat(html).as("下载页必须加载共享 Vue 挂载 helper").contains("src=\"/js/pixiv-vue.js\"");
    }

    @Test
    @DisplayName("batch-queue-vue.js 不自带或硬编码核心 Vue 运行时")
    void queueVueDoesNotBundleRuntime() throws IOException {
        String js = read(QUEUE_VUE_JS);
        assertThat(js).as("不得自带 / 硬编码核心 Vue 运行时路径（只经 helper 解析单一来源）").doesNotContain("/vendor/vue/");
        assertThat(js).as("不得自带 Vue 全局构建版").doesNotContain("vue.global");
    }

    @Test
    @DisplayName("pixiv-batch.css 行宿主 .q-item-host display:contents（v-html 渲染的 .queue-item 直接参与父布局）")
    void queueRowHostIsDisplayContents() throws IOException {
        String css = read(BATCH_CSS);
        String rule = sliceBetween(css, ".q-item-host {", "}");
        assertThat(rule).as(".q-item-host 必须 display:contents（行内容作为 #queue-list / .schedule-queue-list 真实子节点）")
                .contains("display: contents");
    }
}
