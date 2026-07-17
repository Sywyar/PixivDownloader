package top.sywyar.pixivdownload.core.work;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("作品动作结果")
class WorkActionResultTest {

    @Test
    @DisplayName("四种工厂方法保留稳定状态码和消息")
    void factoriesKeepStableStatusAndMessage() {
        assertThat(WorkActionResult.success("ok"))
                .extracting(WorkActionResult::getStatus, WorkActionResult::getMessage)
                .containsExactly(WorkActionResult.SUCCESS, "ok");
        assertThat(WorkActionResult.failed("bad"))
                .extracting(WorkActionResult::getStatus, WorkActionResult::getMessage)
                .containsExactly(WorkActionResult.FAILED, "bad");
        assertThat(WorkActionResult.skipped("skip"))
                .extracting(WorkActionResult::getStatus, WorkActionResult::getMessage)
                .containsExactly(WorkActionResult.SKIPPED, "skip");
        assertThat(WorkActionResult.exists("exists"))
                .extracting(WorkActionResult::getStatus, WorkActionResult::getMessage)
                .containsExactly(WorkActionResult.EXISTS, "exists");
    }
}
