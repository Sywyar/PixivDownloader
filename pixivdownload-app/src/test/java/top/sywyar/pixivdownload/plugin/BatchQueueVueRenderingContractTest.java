package top.sywyar.pixivdownload.plugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 下载工作台「队列 / 统计 + 速度 / 计划任务本轮队列详情」Vue reactive 岛的静态接线守卫：保证高频渲染热点的
 * reactive 主路径、命令式回退、合批与「与命令式共用同一套格式化口径（不分叉）」都不被后续改动悄悄破坏。
 * 纯静态资源内容断言（不启 Spring 上下文），与 {@link NovelSearchVueRenderingContractTest} /
 * {@link WebUiSlotVueHostContractTest} 同一形态。运行态行为另由 {@code src/test/js/batch-queue-vue.test.js} 验证。
 * <p>具体守住：
 * <ul>
 *   <li>下载页 {@code pixiv-batch.html} 在 {@code batch-queue.js} 之后加载 {@code batch-queue-vue.js}（注册 reactive 岛）；</li>
 *   <li>{@code batch-queue-vue.js} 暴露 {@code window.PixivBatch.queueVue} 既定 API、经共享 helper {@code PixivVue.mountOn}
 *       挂载、用 {@code requestAnimationFrame} 合批、行 / 当前卡共用 {@code buildQueueItemHtml} / {@code formatCurrentCardHtml}（不另造 HTML 语义）；</li>
 *   <li>{@code batch-queue.js} 四个门面（{@code renderQueue} / {@code updateStats} / {@code setCurrent} /
 *       {@code renderDownloadSpeed}）在 Vue 激活时合并到 reactive store、否则命令式回退（{@code renderQueueImperative} /
 *       {@code setStatCount} / 速度 span 仍在）；</li>
 *   <li>{@code schedule.js} 三个门面（{@code renderScheduleQueueBodyInto} / {@code flushScheduleQueueRows} /
 *       {@code refreshScheduleQueueMeta}）在 Vue 激活时走 {@code syncScheduleQueue}、否则命令式回退；折叠 / 任务下线卸载 reactive 岛；</li>
 *   <li>{@code pixiv-batch.css} 行宿主 {@code .q-item-host} 为 {@code display:contents}（v-html 渲染的 .queue-item 直接参与父布局）。</li>
 * </ul>
 */
@DisplayName("队列 Vue 契约守卫：下载队列 / 统计速度 / 计划队列详情 reactive 主路径 + 命令式回退 + 共享格式化口径")
class BatchQueueVueRenderingContractTest {

    private static final String STATIC_ROOT = "static/";
    private static final String BATCH_HTML = "pixiv-batch.html";
    private static final String QUEUE_JS = "pixiv-batch/batch-queue.js";
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
    @DisplayName("batch-queue-vue.js 暴露 queueVue 既定 API（普通队列 + 计划队列详情 + flush）")
    void queueVueExposesExpectedApi() throws IOException {
        String js = read(QUEUE_VUE_JS);
        assertThat(js).as("应挂到 window.PixivBatch.queueVue 命名空间").contains("PixivBatch.queueVue");
        for (String fn : new String[]{
                "mountDownloadQueue:", "isDownloadActive:", "syncDownloadList:", "syncDownloadStats:",
                "syncDownloadCurrent:", "syncDownloadSpeed:",
                "ensureScheduleQueue:", "isScheduleActive:", "syncScheduleQueue:", "unmountScheduleQueue:",
                "flush:"}) {
            assertThat(js).as("queueVue 门面应导出 " + fn).contains(fn);
        }
    }

    @Test
    @DisplayName("batch-queue-vue.js 经共享 helper PixivVue.mountOn 挂载、用 requestAnimationFrame 合批")
    void queueVueMountsViaHelperAndCoalescesWithRaf() throws IOException {
        String js = read(QUEUE_VUE_JS);
        assertThat(js).as("应经共享 helper 的 mountOn 挂载（target 由本模块自行解析、不进选择器）").contains("helper().mountOn(");
        assertThat(js).as("应经共享 helper 懒加载运行时（单一来源）").contains("helper().ensure(");
        assertThat(js).as("高频更新应合并到 requestAnimationFrame（缺失时回退 setTimeout）").contains("requestAnimationFrame");
        assertThat(js).as("应有同 key 去重的合批调度器").contains("function schedule(key, fn)");
        assertThat(js).as("不得自带 / 硬编码核心 Vue 运行时路径（只经 helper 解析单一来源）").doesNotContain("/vendor/vue/");
    }

    @Test
    @DisplayName("batch-queue-vue.js 行 / 当前卡共用 buildQueueItemHtml / formatCurrentCardHtml（不另造第二套 HTML 语义）")
    void queueVueReusesSharedFormatters() throws IOException {
        String js = read(QUEUE_VUE_JS);
        assertThat(js).as("行 HTML 必须复用共享 buildQueueItemHtml").contains("'buildQueueItemHtml'");
        assertThat(js).as("当前卡必须复用共享 formatCurrentCardHtml").contains("'formatCurrentCardHtml'");
        assertThat(js).as("列表 / 计划详情行宿主为 q-item-host（v-html 透明宿主）").contains("q-item-host");
        assertThat(js).as("普通队列与计划队列列表都用 :key=q.id 复用宿主、仅 patch 变化行").contains(":key=\"q.id\"");
    }

    @Test
    @DisplayName("batch-queue.js 普通队列门面：Vue 激活合并 reactive、否则命令式回退（renderQueueImperative / setStatCount / 速度 span 仍在）")
    void downloadQueueFacadesBranchVueWithImperativeFallback() throws IOException {
        String js = read(QUEUE_JS);
        assertThat(js).as("应有 Vue 岛激活判定门面").contains("function downloadQueueVueActive(");
        assertThat(js).as("命令式整块渲染应保留为独立函数").contains("function renderQueueImperative(");

        // renderQueue：Vue 激活 → syncDownloadList，否则命令式 renderQueueImperative（顺序与互斥）。
        String renderQueue = sliceBetween(js, "function renderQueue() {", "function renderQueueImperative(");
        int vueAt = renderQueue.indexOf("syncDownloadList()");
        int impAt = renderQueue.indexOf("renderQueueImperative()");
        assertThat(vueAt).as("renderQueue 应有 Vue 主路径 syncDownloadList").isGreaterThanOrEqualTo(0);
        assertThat(impAt).as("renderQueue 应有命令式回退 renderQueueImperative").isGreaterThan(vueAt);

        assertThat(js).as("updateStats 应在 Vue 激活时合并到 reactive store").contains("syncDownloadStats({");
        assertThat(js).as("updateStats 命令式回退仍逐项写 5 张统计卡").contains("setStatCount('stat-count-pending', pending)");
        assertThat(js).as("setCurrent 应在 Vue 激活时写 reactive store").contains("syncDownloadCurrent(item)");
        assertThat(js).as("renderDownloadSpeed 应在 Vue 激活时写 reactive store").contains("syncDownloadSpeed(value, unit)");
        assertThat(js).as("renderDownloadSpeed 命令式回退仍写速度 span").contains("getElementById('stat-speed-value')");
        assertThat(js).as("速度文案两路共享 formatSpeed 口径").contains("formatSpeed(bytesPerSec)");
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
