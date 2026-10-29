package top.sywyar.pixivdownload.download.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.AfterEach;
import top.sywyar.pixivdownload.testsupport.TestDirectoryCleanup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.download.request.BatchStateRequest;
import top.sywyar.pixivdownload.download.response.status.BatchStateResponse;
import top.sywyar.pixivdownload.download.state.BatchStateFiles;
import top.sywyar.pixivdownload.i18n.MessageResolver;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("工作台状态保存与恢复")
class BatchStateControllerTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String ENDPOINT = "/api/batch/state";
    @TempDir Path directory;

    @AfterEach
    void cleanDirectoryAfterResourcesClose() throws Exception {
        TestDirectoryCleanup.delete(directory);
    }

    @Test
    @DisplayName("缺失状态为空对象，完整替换后查询和重启均恢复原始 JSON")
    void persistsAndReloadsState() throws Exception {
        Path file = directory.resolve("owner/batch_state.json");
        BatchStateController controller = controller(file, "solo");
        controller.loadState();
        assertThat(state(controller)).isEqualTo("{}");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"state\":{\"template\":\"作品😀\",\"queue\":[1,2]}}".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isOk());
        mvc.perform(get(ENDPOINT)).andExpect(status().isOk())
                .andExpect(jsonPath("$.state.template").value("作品😀"))
                .andExpect(jsonPath("$.state.queue[1]").value(2));
        BatchStateController restarted = controller(file, "solo");
        restarted.loadState();
        assertThat(state(restarted)).isEqualTo(state(controller));
        controller.saveState(request("{\"fresh\":true}"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo("{\"fresh\":true}");
        try (var entries = Files.list(file.getParent())) {
            assertThat(entries.toList()).containsExactly(file);
        }
    }

    @Test
    @DisplayName("父路径不可写时传播失败，查询不发布未保存的状态")
    void failedDirectoryCreationDoesNotPublishCache() throws Exception {
        Path blocker = directory.resolve("blocked");
        Files.writeString(blocker, "preserved", StandardCharsets.UTF_8);
        BatchStateController controller = controller(blocker.resolve("batch_state.json"), "solo");
        assertThatThrownBy(() -> controller.saveState(request("{\"new\":true}")))
                .isInstanceOf(IOException.class);
        assertThat(state(controller)).isEqualTo("{}");
        assertThat(Files.readString(blocker, StandardCharsets.UTF_8)).isEqualTo("preserved");
    }

    @Test
    @DisplayName("目标替换失败保留已加载状态和冲突内容，清理临时文件后可重试")
    void failedReplacementKeepsPreviousState() throws Exception {
        Path file = directory.resolve("batch_state.json");
        Files.writeString(file, "{\"old\":true}", StandardCharsets.UTF_8);
        BatchStateController controller = controller(file, "solo");
        controller.loadState();
        Path backup = directory.resolve("previous.json");
        Files.move(file, backup);
        Files.createDirectory(file);
        Path blocker = file.resolve("occupied");
        Files.writeString(blocker, "preserved", StandardCharsets.UTF_8);
        assertThatThrownBy(() -> controller.saveState(request("{\"new\":true}")))
                .isInstanceOf(IOException.class);
        assertThat(state(controller)).isEqualTo("{\"old\":true}");
        assertThat(Files.readString(backup, StandardCharsets.UTF_8)).isEqualTo(state(controller));
        assertThat(Files.readString(blocker, StandardCharsets.UTF_8)).isEqualTo("preserved");
        try (var entries = Files.list(directory)) {
            assertThat(entries.toList()).containsExactlyInAnyOrder(file, backup);
        }
        Files.delete(blocker);
        Files.delete(file);
        controller.saveState(request("{\"new\":true}"));
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(state(controller));
    }

    @Test
    @DisplayName("并发整份保存后缓存、磁盘和重启读取属于同一份完整快照")
    void concurrentWritersPublishCompleteSnapshots() throws Exception {
        Path file = directory.resolve("batch_state.json");
        BatchStateController controller = controller(file, "solo");
        var executor = Executors.newFixedThreadPool(4);
        var start = new CountDownLatch(1);
        var futures = new ArrayList<Future<?>>();
        var snapshots = new ArrayList<String>();
        try {
            for (int i = 0; i < 24; i++) {
                String snapshot = JSON.createObjectNode().put("id", i)
                        .put("value", ("作品" + i).repeat(2048)).toString();
                snapshots.add(snapshot);
                futures.add(executor.submit(() -> {
                    start.await();
                    controller.saveState(request(snapshot));
                    assertThat(JSON.readTree(Files.readString(file, StandardCharsets.UTF_8)).isObject()).isTrue();
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(15, TimeUnit.SECONDS);
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
        }
        String saved = Files.readString(file, StandardCharsets.UTF_8);
        assertThat(saved).isIn(snapshots);
        assertThat(state(controller)).isEqualTo(saved);
        BatchStateController restarted = controller(file, "solo");
        restarted.loadState();
        assertThat(state(restarted)).isEqualTo(saved);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"state\":null}", "{\"state\":[]}", "{\"state\":42}", "{\"state\":\"text\"}"})
    @DisplayName("缺失或非对象状态返回 400，保留已保存内容")
    void rejectsInvalidState(String body) throws Exception {
        Path file = directory.resolve("batch_state.json");
        BatchStateController controller = controller(file, "solo");
        controller.saveState(request("{\"old\":true}"));
        MockMvcBuilders.standaloneSetup(controller).build()
                .perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("error.request.param.invalid"));
        assertThat(state(controller)).isEqualTo("{\"old\":true}");
        assertThat(Files.readString(file, StandardCharsets.UTF_8)).isEqualTo(state(controller));
    }

    @Test
    @DisplayName("多人模式拒绝读写且不创建状态文件")
    void multiModeCannotReadOrWriteState() throws Exception {
        Path file = directory.resolve("batch_state.json");
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller(file, "multi")).build();
        mvc.perform(get(ENDPOINT)).andExpect(status().isForbidden());
        mvc.perform(post(ENDPOINT).contentType(MediaType.APPLICATION_JSON).content("{\"state\":{}}"))
                .andExpect(status().isForbidden());
        assertThat(file).doesNotExist();
    }

    private BatchStateController controller(Path file, String mode) {
        BatchStateFiles files = mock(BatchStateFiles.class);
        when(files.stateFile()).thenReturn(file);
        MessageResolver messages = mock(MessageResolver.class);
        when(messages.get("error.request.param.invalid")).thenReturn("Invalid request parameters");
        return new BatchStateController(files, () -> mode, messages);
    }

    private static BatchStateRequest request(String state) throws IOException {
        return new BatchStateRequest(JSON.readTree(state));
    }

    private static String state(BatchStateController controller) {
        return ((BatchStateResponse) controller.getState().getBody()).getState();
    }
}
