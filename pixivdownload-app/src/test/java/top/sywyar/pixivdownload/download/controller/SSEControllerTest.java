package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import top.sywyar.pixivdownload.download.DownloadProgressEvent;
import top.sywyar.pixivdownload.download.DownloadStatus;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.plugin.PluginStreamRegistry;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("SSEController 单元测试")
class SSEControllerTest {

    @Mock
    private TaskScheduler taskScheduler;
    @Mock
    private SetupService setupService;
    @Mock
    private ScheduledFuture<?> heartbeatFuture;

    private SSEController controller;
    private PluginStreamRegistry pluginStreamRegistry;

    @BeforeEach
    void setUp() {
        pluginStreamRegistry = new PluginStreamRegistry();
        controller = new SSEController(taskScheduler, setupService, TestI18nBeans.appMessages(),
                pluginStreamRegistry);
        lenient().when(taskScheduler.scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(30))))
                .thenAnswer(invocation -> heartbeatFuture);
    }

    @AfterEach
    void tearDown() {
        if (controller != null) {
            controller.shutdownProgressExecutor();
        }
        LocaleContextHolder.resetLocaleContext();
    }

    @Test
    @DisplayName("createSSEConnection 应按请求语言注册作品级连接并安排心跳")
    void shouldRegisterArtworkEmitterWithCurrentLocale() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        String ownerUuid = "123e4567-e89b-12d3-a456-426614174000";
        when(setupService.hasAdminScope(any())).thenReturn(false);
        MockHttpServletRequest request = requestWithUuid(ownerUuid);

        SseEmitter emitter = controller.createSSEConnection(12345L, request);

        String key = "user:" + ownerUuid + ":12345";
        assertThat(emitter.getTimeout()).isEqualTo(300000L);
        assertThat(artworkEmitters()).containsKey(key);
        assertThat(subscriptionLocale(artworkEmitters().get(key))).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(heartbeatTasks()).containsKey(key);
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("sendStatusUpdate 应发送本地化的建连事件")
    void shouldSendLocalizedConnectionEstablishedPayload() throws Exception {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putArtworkSubscription(12345L, emitter, Locale.US);

        controller.sendStatusUpdate(12345L);

        assertThat(emitter.events).hasSize(1);
        SentEvent event = emitter.events.get(0);
        assertThat(event.raw).contains("event:download-status");
        assertThat(event.payload).isInstanceOf(SseStatusData.class);

        SseStatusData payload = (SseStatusData) event.payload;
        assertThat(payload.getArtworkId()).isEqualTo(12345L);
        assertThat(payload.getStatus()).isEqualTo("Connecting");
        assertThat(payload.getMessage()).isEqualTo("SSE connection established");
        assertThat(payload.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("handleDownloadProgressEvent 应向作品级连接发送本地化进度")
    void shouldSendLocalizedProgressToArtworkEmitter() throws Exception {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putArtworkSubscription(777L, emitter, Locale.SIMPLIFIED_CHINESE);

        DownloadStatus status = new DownloadStatus(777L, "test", 4);
        status.setCurrentImageIndex(1);
        status.setDownloadedCount(2);
        status.setFolderName("777");

        controller.handleDownloadProgressEvent(new DownloadProgressEvent(this, 777L, status, "user-1"));

        waitUntil(() -> emitter.events.size() == 1);
        assertThat(emitter.events).hasSize(1);
        SentEvent event = emitter.events.get(0);
        assertThat(event.raw).contains("event:download-status");

        SseStatusData payload = (SseStatusData) event.payload;
        assertThat(payload.getArtworkId()).isEqualTo(777L);
        assertThat(payload.getStatus()).isEqualTo("进度更新");
        assertThat(payload.getMessage()).isEqualTo("下载进度已更新");
        assertThat(payload.getCurrentImageIndex()).isEqualTo(1);
        assertThat(payload.getTotalImages()).isEqualTo(4);
        assertThat(payload.getDownloadedCount()).isEqualTo(2);
        assertThat(payload.getProgress()).isEqualTo(50);
        assertThat(payload.getFolderName()).isEqualTo("777");
    }

    @Test
    @DisplayName("handleDownloadProgressEvent 应按 owner UUID 过滤聚合连接，并保留各自语言")
    void shouldFilterAggregatedSubscribersByOwnerAndLocale() throws Exception {
        RecordingSseEmitter matchingEmitter = new RecordingSseEmitter();
        RecordingSseEmitter otherEmitter = new RecordingSseEmitter();
        RecordingSseEmitter adminEmitter = new RecordingSseEmitter();

        putAggregatedSubscription("match", matchingEmitter, "user-1", false, Locale.US);
        putAggregatedSubscription("other", otherEmitter, "user-2", false, Locale.SIMPLIFIED_CHINESE);
        putAggregatedSubscription("admin", adminEmitter, null, true, Locale.SIMPLIFIED_CHINESE);

        DownloadStatus status = new DownloadStatus(888L, "test", 5);
        status.setCurrentImageIndex(0);
        status.setDownloadedCount(1);

        controller.handleDownloadProgressEvent(new DownloadProgressEvent(this, 888L, status, "user-1"));

        waitUntil(() -> matchingEmitter.events.size() == 1 && adminEmitter.events.size() == 1);
        assertThat(matchingEmitter.events).hasSize(1);
        assertThat(otherEmitter.events).isEmpty();
        assertThat(adminEmitter.events).hasSize(1);

        SseStatusData matchingPayload = (SseStatusData) matchingEmitter.events.get(0).payload;
        assertThat(matchingPayload.getStatus()).isEqualTo("Progress updated");
        assertThat(matchingPayload.getMessage()).isEqualTo("Download progress updated");

        SseStatusData adminPayload = (SseStatusData) adminEmitter.events.get(0).payload;
        assertThat(adminPayload.getStatus()).isEqualTo("进度更新");
        assertThat(adminPayload.getMessage()).isEqualTo("下载进度已更新");
    }

    @Test
    @DisplayName("createAggregatedSSEConnection 应记录 owner UUID 和语言")
    void shouldRegisterAggregatedEmitterWithOwnerAndLocale() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        when(setupService.hasAdminScope(any())).thenReturn(false);

        MockHttpServletRequest request = requestWithUuid("123e4567-e89b-12d3-a456-426614174000");

        SseEmitter emitter = controller.createAggregatedSSEConnection(request);

        assertThat(emitter.getTimeout()).isEqualTo(86_400_000L);
        assertThat(aggregatedEmitters()).hasSize(1);

        Object subscription = aggregatedEmitters().values().iterator().next();
        assertThat(subscriptionLocale(subscription)).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(subscriptionOwnerUuid(subscription)).isEqualTo("123e4567-e89b-12d3-a456-426614174000");
        assertThat(subscriptionAdmin(subscription)).isFalse();
        assertThat(aggregatedHeartbeatTasks()).hasSize(1);
        verify(taskScheduler).scheduleAtFixedRate(any(Runnable.class), eq(Duration.ofSeconds(30)));
    }

    @Test
    @DisplayName("closeSSEConnection 应返回本地化响应并移除连接")
    void shouldCloseConnectionAndReturnLocalizedMessage() throws Exception {
        LocaleContextHolder.setLocale(Locale.SIMPLIFIED_CHINESE);
        when(setupService.hasAdminScope(any())).thenReturn(true);

        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putArtworkSubscription(999L, emitter, Locale.US);

        var response = controller.closeSSEConnection(999L, new MockHttpServletRequest());

        assertThat(response.getBody()).isNotNull();
        DownloadResponse body = response.getBody();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getMessage()).isEqualTo("SSE连接已安全关闭");
        assertThat(artworkEmitters()).doesNotContainKey("admin:999");
        assertThat(emitter.completed).isTrue();
    }

    @Test
    @DisplayName("closeAggregatedSSEConnection 应取消心跳并完成聚合连接")
    void shouldCloseAggregatedConnectionAndCancelHeartbeat() throws Exception {
        String ownerUuid = "123e4567-e89b-12d3-a456-426614174000";
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putAggregatedSubscription("conn-1", emitter, ownerUuid, false, Locale.US);
        aggregatedHeartbeatTasks().put("conn-1", heartbeatFuture);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", ownerUuid);
        String closeToken = createAggregatedCloseToken("conn-1", ownerUuid, false);

        var response = controller.closeAggregatedSSEConnection(closeToken, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(aggregatedEmitters()).doesNotContainKey("conn-1");
        assertThat(aggregatedHeartbeatTasks()).doesNotContainKey("conn-1");
        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0).raw).contains("event:sse-closing");
        assertThat(emitter.completed).isTrue();
        verify(heartbeatFuture).cancel(false);
    }

    @Test
    @DisplayName("closeAggregatedSSEConnection 应拒绝关闭不属于当前用户的聚合连接")
    void shouldRejectClosingAggregatedConnectionOwnedByAnotherUser() throws Exception {
        String ownerUuid = "123e4567-e89b-12d3-a456-426614174000";
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putAggregatedSubscription("conn-1", emitter, ownerUuid, false, Locale.US);
        aggregatedHeartbeatTasks().put("conn-1", heartbeatFuture);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-User-UUID", "223e4567-e89b-12d3-a456-426614174000");
        String closeToken = createAggregatedCloseToken("conn-1", ownerUuid, false);

        var response = controller.closeAggregatedSSEConnection(closeToken, request);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isFalse();
        assertThat(aggregatedEmitters()).containsKey("conn-1");
        assertThat(aggregatedHeartbeatTasks()).containsKey("conn-1");
        assertThat(emitter.events).isEmpty();
        assertThat(emitter.completed).isFalse();
    }

    @Test
    @DisplayName("发送失败时应清理聚合连接但不再 complete 已损坏的响应")
    void shouldCleanupAggregatedConnectionWithoutCompleteAfterSendFailure() throws Exception {
        FailingSseEmitter emitter = new FailingSseEmitter();
        putAggregatedSubscription("broken", emitter, "user-1", false, Locale.US);
        aggregatedHeartbeatTasks().put("broken", heartbeatFuture);

        DownloadStatus status = new DownloadStatus(123L, "test", 1);
        status.setDownloadedCount(1);

        controller.handleDownloadProgressEvent(new DownloadProgressEvent(this, 123L, status, "user-1"));

        waitUntil(() -> !aggregatedEmitters().containsKey("broken"));
        assertThat(aggregatedEmitters()).doesNotContainKey("broken");
        assertThat(aggregatedHeartbeatTasks()).doesNotContainKey("broken");
        assertThat(emitter.completed).isFalse();
        verify(heartbeatFuture).cancel(false);
    }

    @Test
    @DisplayName("作品级 / 聚合连接注册进插件推流注册中心；拥有它的插件 closeForPlugin 时全部关闭、注册中心清空")
    void registersStreamsUnderOwningPluginAndClosesThemOnTeardown() {
        when(setupService.hasAdminScope(any())).thenReturn(true);

        controller.createSSEConnection(111L, new MockHttpServletRequest());
        controller.createAggregatedSSEConnection(new MockHttpServletRequest());
        assertThat(pluginStreamRegistry.activeStreamCount(DownloadWorkbenchPlugin.ID)).isEqualTo(2);

        // 拥有它的插件 quiesce / 卸载 → 生命周期服务调 closeForPlugin → 关闭全部连接、注册中心不再残留引用
        int closed = pluginStreamRegistry.closeForPlugin(DownloadWorkbenchPlugin.ID);

        assertThat(closed).isEqualTo(2);
        assertThat(pluginStreamRegistry.activeStreamCount(DownloadWorkbenchPlugin.ID)).isZero();
        assertThat(artworkEmitters()).isEmpty();
        assertThat(aggregatedEmitters()).isEmpty();
    }

    @Test
    @DisplayName("插件 closeForPlugin 关闭作品级连接：客户端收到 plugin-unavailable 事件并被 complete")
    void closeForPluginSendsUnavailableEventToArtworkClient() throws Exception {
        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putArtworkSubscription(777L, emitter, Locale.US);
        pluginStreamRegistry.register(DownloadWorkbenchPlugin.ID, "admin:777",
                () -> ReflectionTestUtils.invokeMethod(controller, "closeArtworkStreamUnavailable", "admin:777"));

        pluginStreamRegistry.closeForPlugin(DownloadWorkbenchPlugin.ID);

        assertThat(emitter.events).hasSize(1);
        assertThat(emitter.events.get(0).raw)
                .contains("event:plugin-unavailable")
                .contains("temporarily unavailable");
        assertThat(emitter.completed).isTrue();
        assertThat(artworkEmitters()).doesNotContainKey("admin:777");
    }

    @Test
    @DisplayName("作品级连接正常关闭后从插件推流注册中心注销：closeForPlugin 不再触达它")
    void normalCloseUnregistersStream() {
        when(setupService.hasAdminScope(any())).thenReturn(true);
        controller.createSSEConnection(222L, new MockHttpServletRequest());
        assertThat(pluginStreamRegistry.activeStreamCount(DownloadWorkbenchPlugin.ID)).isEqualTo(1);

        controller.closeSSEConnection(222L, new MockHttpServletRequest());

        assertThat(pluginStreamRegistry.activeStreamCount(DownloadWorkbenchPlugin.ID)).isZero();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> artworkEmitters() {
        return (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(controller, "emitters");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ScheduledFuture<?>> heartbeatTasks() {
        return (ConcurrentHashMap<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(controller, "heartbeatTasks");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, Object> aggregatedEmitters() {
        return (ConcurrentHashMap<String, Object>) ReflectionTestUtils.getField(controller, "aggregatedEmitters");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<String, ScheduledFuture<?>> aggregatedHeartbeatTasks() {
        return (ConcurrentHashMap<String, ScheduledFuture<?>>) ReflectionTestUtils.getField(controller, "aggregatedHeartbeats");
    }

    private void putArtworkSubscription(Long artworkId, SseEmitter emitter, Locale locale) throws Exception {
        artworkEmitters().put("admin:" + artworkId,
                instantiateInnerRecord("ArtworkSubscription", emitter, artworkId, null, true, locale));
    }

    private void putAggregatedSubscription(
            String connectionId,
            SseEmitter emitter,
            String ownerUuid,
            boolean admin,
            Locale locale) throws Exception {
        aggregatedEmitters().put(connectionId,
                instantiateInnerRecord("AggregatedSubscription", emitter, ownerUuid, admin, locale));
    }

    private Object instantiateInnerRecord(String simpleName, Object... args) throws Exception {
        Class<?> recordClass = Arrays.stream(SSEController.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals(simpleName))
                .findFirst()
                .orElseThrow();
        Constructor<?> constructor = Arrays.stream(recordClass.getDeclaredConstructors())
                .findFirst()
                .orElseThrow();
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private Locale subscriptionLocale(Object subscription) throws Exception {
        Method accessor = subscription.getClass().getDeclaredMethod("locale");
        accessor.setAccessible(true);
        return (Locale) accessor.invoke(subscription);
    }

    private String subscriptionOwnerUuid(Object subscription) throws Exception {
        Method accessor = subscription.getClass().getDeclaredMethod("ownerUuid");
        accessor.setAccessible(true);
        return (String) accessor.invoke(subscription);
    }

    private boolean subscriptionAdmin(Object subscription) throws Exception {
        Method accessor = subscription.getClass().getDeclaredMethod("admin");
        accessor.setAccessible(true);
        return (boolean) accessor.invoke(subscription);
    }

    private void waitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private MockHttpServletRequest requestWithUuid(String ownerUuid) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("X-User-UUID", ownerUuid);
        return request;
    }

    private String createAggregatedCloseToken(String connectionId, String ownerUuid, boolean admin) {
        return ReflectionTestUtils.invokeMethod(controller,
                "createAggregatedCloseToken",
                connectionId,
                ownerUuid,
                admin,
                System.currentTimeMillis());
    }

    private static final class RecordingSseEmitter extends SseEmitter {
        private final List<SentEvent> events = new CopyOnWriteArrayList<>();
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            StringBuilder raw = new StringBuilder();
            Object payload = null;
            for (ResponseBodyEmitter.DataWithMediaType item : builder.build()) {
                Object data = item.getData();
                if (data instanceof String text) {
                    raw.append(text);
                } else {
                    payload = data;
                }
            }
            events.add(new SentEvent(raw.toString(), payload));
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }
    }

    private static final class FailingSseEmitter extends SseEmitter {
        private boolean completed;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            throw new IOException("client disconnected");
        }

        @Override
        public void complete() {
            completed = true;
            super.complete();
        }
    }

    private record SentEvent(String raw, Object payload) {
    }
}
