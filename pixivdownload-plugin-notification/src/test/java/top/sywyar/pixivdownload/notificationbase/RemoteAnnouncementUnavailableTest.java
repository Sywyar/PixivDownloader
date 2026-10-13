package top.sywyar.pixivdownload.notificationbase;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.database.DatabaseUnavailableException;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpClient;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpRequest;
import top.sywyar.pixivdownload.plugin.api.http.OutboundHttpStreamResponse;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("远程公告在数据库不可用时停止")
class RemoteAnnouncementUnavailableTest extends RemoteAnnouncementImporterTestSupport {
    @Test
    @DisplayName("无库启动后轮询不联网，直接导入也明确拒绝")
    void doesNotPollWithoutDatabase() {
        SigningFixture signing = SigningFixture.create();
        var mapper = new RecordingMapper();
        var inbox = new NotificationInboxService(mapper, () -> false);
        var client = new StubClient(signing);
        var importer = new RemoteAnnouncementImporter(client, new ObjectMapper(), inbox,
                signing.verifier(), CLOCK, ignored -> 0);

        importer.tick();
        importer.poll();
        assertThatThrownBy(() -> importer.importIndex(new byte[0], new byte[0]))
                .isInstanceOf(DatabaseUnavailableException.class);

        assertThat(client.requests).hasValue(0);
        assertThat(mapper.findLatest(null, false, 100)).isEmpty();
        assertThat(mapper.findRemoteAnnouncementValidators()).isNull();
    }

    @Test
    @DisplayName("索引响应返回时数据库失效，释放响应且不读取正文或发送签名请求")
    void closesResponseWithoutFollowupWhenDatabaseDisappears() {
        SigningFixture signing = SigningFixture.create();
        AtomicBoolean available = new AtomicBoolean(true);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger requests = new AtomicInteger();
        var mapper = new RecordingMapper();
        var inbox = new NotificationInboxService(mapper, available::get);
        OutboundHttpClient client = new OutboundHttpClient() {
            @Override
            public OutboundHttpStreamResponse exchangeStream(OutboundHttpRequest request) {
                assertThat(request.uri()).isEqualTo(RemoteAnnouncementImporter.INDEX_URI);
                requests.incrementAndGet();
                available.set(false);
                return new OutboundHttpStreamResponse(200, "", jsonHeaders(),
                        new ByteArrayInputStream(new byte[0]) {
                            @Override
                            public synchronized int read(byte[] data, int offset, int length) {
                                reads.incrementAndGet();
                                return super.read(data, offset, length);
                            }

                            @Override
                            public void close() {
                                closed.set(true);
                            }
                        });
            }

            @Override
            public void close() {
            }
        };
        var importer = new RemoteAnnouncementImporter(client, new ObjectMapper(), inbox,
                signing.verifier(), CLOCK, ignored -> 0);

        importer.poll();
        importer.tick();

        assertThat(requests).hasValue(1);
        assertThat(reads).hasValue(0);
        assertThat(closed).isTrue();
        assertThat(mapper.findLatest(null, false, 100)).isEmpty();
        assertThat(mapper.findRemoteAnnouncementValidators()).isNull();
    }
}
