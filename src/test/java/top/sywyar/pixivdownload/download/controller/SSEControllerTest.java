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
import top.sywyar.pixivdownload.download.response.DownloadResponse;
import top.sywyar.pixivdownload.download.response.SseStatusData;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.setup.SetupService;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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

    @BeforeEach
    void setUp() {
        controller = new SSEController(taskScheduler, setupService, TestI18nBeans.appMessages());
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

        SseEmitter emitter = controller.createSSEConnection(12345L);

        assertThat(emitter.getTimeout()).isEqualTo(300000L);
        assertThat(artworkEmitters()).containsKey(12345L);
        assertThat(subscriptionLocale(artworkEmitters().get(12345L))).isEqualTo(Locale.SIMPLIFIED_CHINESE);
        assertThat(heartbeatTasks()).containsKey(12345L);
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

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("User-Agent", "JUnit");
        request.addHeader("X-User-UUID", "123e4567-e89b-12d3-a456-426614174000");

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

        RecordingSseEmitter emitter = new RecordingSseEmitter();
        putArtworkSubscription(999L, emitter, Locale.US);

        var response = controller.closeSSEConnection(999L);

        assertThat(response.getBody()).isNotNull();
        DownloadResponse body = response.getBody();
        assertThat(body.isSuccess()).isTrue();
        assertThat(body.getMessage()).isEqualTo("SSE连接已安全关闭");
        assertThat(artworkEmitters()).doesNotContainKey(999L);
        assertThat(emitter.completed).isTrue();
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, Object> artworkEmitters() {
        return (ConcurrentHashMap<Long, Object>) ReflectionTestUtils.getField(controller, "emitters");
    }

    @SuppressWarnings("unchecked")
    private ConcurrentHashMap<Long, ScheduledFuture<?>> heartbeatTasks() {
        return (ConcurrentHashMap<Long, ScheduledFuture<?>>) ReflectionTestUtils.getField(controller, "heartbeatTasks");
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
        artworkEmitters().put(artworkId, instantiateInnerRecord("ArtworkSubscription", emitter, locale));
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

    private record SentEvent(String raw, Object payload) {
    }
}
