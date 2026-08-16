package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台队列 Vue 岛的静态装配边界。普通队列的 API、挂载、合批、格式化复用和命令式回退由
 * {@code src/test/js/batch-queue-vue.test.js} 执行真实脚本验证；这里保留页面加载顺序、运行时单一来源、
 * 尚无运行态夹具的计划队列门面和 CSS 布局边界。
 * <p>具体守住：
 * <ul>
 *   <li>下载页 {@code pixiv-batch.html} 在 {@code batch-queue.js} 之后加载 {@code batch-queue-vue.js}（注册 reactive 岛）；</li>
 *   <li>{@code batch-queue-vue.js} 不自带或硬编码核心 Vue 运行时；</li>
 *   <li>{@code schedule.js} 三个门面（{@code renderScheduleQueueBodyInto} / {@code flushScheduleQueueRows} /
 *       {@code refreshScheduleQueueMeta}）在 Vue 激活时走 {@code syncScheduleQueue}、否则命令式回退；折叠 / 任务下线卸载 reactive 岛；</li>
 *   <li>{@code pixiv-batch.css} 行宿主 {@code .q-item-host} 为 {@code display:contents}（v-html 渲染的 .queue-item 直接参与父布局）。</li>
 * </ul>
 */
@DisplayName("队列 Vue 静态装配边界")
class BatchQueueVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = "pixiv-batch.html";
    private static final String QUEUE_VUE_JS = "pixiv-batch/batch-queue-vue.js";
    private static final String SCHEDULE_JS = "pixiv-batch/modes/schedule.js";
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
    @DisplayName("schedule.js 计划队列详情门面：Vue 激活走 syncScheduleQueue、否则命令式回退；折叠 / 下线卸载 reactive 岛")
    void scheduleQueueFacadesBranchVueWithImperativeFallback() throws IOException {
        String js = read(SCHEDULE_JS);
        assertThat(js).as("应有计划队列 Vue 岛句柄门面").contains("function scheduleQueueVue(");
        assertThat(js).as("应有给 Vue 岛喂快照的读取上下文（与命令式同口径派生）").contains("function scheduleQueueVueContext(");

        // renderScheduleQueueBodyInto：ensure 成功 → syncScheduleQueue（reactive），否则命令式 body.innerHTML。
        String renderInto = sliceBetween(js, "function renderScheduleQueueBodyInto(", "function toggleScheduleQueue(");
        int ensureAt = renderInto.indexOf("ensureScheduleQueue(Number(id)");
        int reactiveAt = renderInto.indexOf("qv.syncScheduleQueue(Number(id))");
        int imperativeAt = renderInto.indexOf("body.innerHTML = renderScheduleQueueBody(id)");
        assertThat(ensureAt).as("应先 ensureScheduleQueue 判定 Vue 接管").isGreaterThanOrEqualTo(0);
        assertThat(reactiveAt).as("Vue 接管时走 reactive syncScheduleQueue").isGreaterThan(ensureAt);
        assertThat(imperativeAt).as("命令式回退仍整块 body.innerHTML（顺序在 Vue 主路径之后）").isGreaterThan(reactiveAt);

        assertThat(js).as("flushScheduleQueueRows 在 Vue 激活时合并 reactive（不整块重建 .schedule-queue-body）")
                .contains("if (qv && qv.isScheduleActive(id)) {");
        assertThat(js).as("refreshScheduleQueueMeta 在 Vue 激活时随整份 reactive 同步").contains("qv.syncScheduleQueue(id);");
        assertThat(js).as("折叠时卸载 reactive 岛（再展开命令式首屏 + 重挂）").contains("qvCollapse.unmountScheduleQueue(id)");
        assertThat(js).as("任务下线时卸载其 reactive 岛").contains("qvRelease.unmountScheduleQueue(id)");
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
