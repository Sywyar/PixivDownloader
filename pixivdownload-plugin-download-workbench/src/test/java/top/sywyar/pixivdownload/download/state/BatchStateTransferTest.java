package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import static org.assertj.core.api.Assertions.*;

@DisplayName("工作台迁移的暂停与凭据边界")
class BatchStateTransferTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test @DisplayName("清除执行关联和自动开始，未选择凭据时保留目标 Cookie 与其它本机状态")
    void pausesWithoutCopyingExecutionsOrCredentials() throws Exception {
        String saved = """
                {"pixiv_cookie":"source-secret","pixiv_cookie_fmt":"formatted-secret",
                 "pixiv_batch_settings":{"autoStartOnEnqueue":true,"interval":3,"collectionId":5},
                 "pixiv_batch_queue":{"queue":[{"id":"3","kind":"illust","status":"downloading",
                    "taskId":"old-process-id","recoveryState":"running","liveStatus":{"cookie":"not-state"}},
                    {"id":"4","status":"completed"},{"id":"5","taskObserved":true}]},
                 "pixiv_schedule_queue_1":"stale", "unrecognized":"private"}
                """;
        byte[] exported = copy(saved, null, false);
        assertThat(new String(exported, StandardCharsets.UTF_8)).doesNotContain("source-secret", "formatted-secret",
                "old-process-id", "not-state", "stale", "private");
        var document = json.readTree(exported);
        assertThat(json.readTree(document.path("pixiv_batch_settings").textValue()).path("autoStartOnEnqueue").booleanValue()).isFalse();
        var queue = json.readTree(document.path("pixiv_batch_queue").textValue());
        assertThat(queue.path("isPaused").booleanValue()).isTrue();
        assertThat(queue.path("queue").size()).isEqualTo(2);
        assertThat(queue.path("queue").get(0).path("status").textValue()).isEqualTo("idle");
        assertThat(queue.path("queue").get(1).path("status").textValue()).isEqualTo("completed");
        byte[] restored = copy(new String(exported, StandardCharsets.UTF_8), "{\"pixiv_cookie\":\"target-secret\",\"local\":7}", true);
        assertThat(json.readTree(restored).path("pixiv_cookie").textValue()).isEqualTo("target-secret");
        assertThat(json.readTree(restored).path("local").intValue()).isEqualTo(7);
        assertThat(copy(new String(exported, StandardCharsets.UTF_8), null, false)).isEqualTo(exported);
    }

    @Test @DisplayName("凭据必须显式选择，损坏与过大的保存状态拒绝迁移")
    void rejectsInvalidStateAndIncludesOnlyChosenCredentials() throws Exception {
        assertThat(new String(copy("{\"pixiv_cookie\":\"chosen-secret\"}", null, true), StandardCharsets.UTF_8))
                .contains("chosen-secret");
        assertThatThrownBy(() -> copy("{\"pixiv_batch_queue\":\"bad-json\"}", null, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> copy("{\"a\":1,\"a\":2}", null, false)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> copy(" ".repeat(8 * 1024 * 1024 + 1), null, false)).isInstanceOf(IOException.class);
    }

    private byte[] copy(String source, String target, boolean credentials) throws IOException {
        var output = new ByteArrayOutputStream();
        new BatchStateTransfer().copy("batch_state.json", new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)),
                target == null ? null : new ByteArrayInputStream(target.getBytes(StandardCharsets.UTF_8)), output, credentials);
        return output.toByteArray();
    }
}
