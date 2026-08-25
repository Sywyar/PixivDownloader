package top.sywyar.pixivdownload.download.state;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import top.sywyar.pixivdownload.download.request.LayoutFeedbackCommandRequest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

abstract class LayoutFeedbackStateStoreTestSupport {
    protected static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    protected static final String OTHER_SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
    protected static final long NOW = 1_785_000_000_000L;

    @TempDir
    Path tempDir;

    protected Path stateFile() {
        return tempDir.resolve("state/download-workbench/layout-feedback-state.json");
    }

    protected LayoutFeedbackStateStore store() {
        return new LayoutFeedbackStateStore(stateFile());
    }

    protected LayoutFeedbackCommandRequest command(String surveyId, String command, List<String> layoutIds) {
        return new LayoutFeedbackCommandRequest(surveyId, command, layoutIds);
    }

    protected LayoutFeedbackCommandRequest recordSeen(String... layoutIds) {
        return command(SURVEY_ID, "record_seen", List.of(layoutIds));
    }

    protected LayoutFeedbackCommandRequest submitted() {
        return command(SURVEY_ID, "submitted", null);
    }

    protected LayoutFeedbackCommandRequest never() {
        return command(SURVEY_ID, "never", null);
    }

    protected LayoutFeedbackCommandRequest snooze() {
        return command(SURVEY_ID, "snooze", null);
    }

    protected String stateJson(String surveyId, long updatedAt) {
        return "{\"surveyId\":\"" + surveyId
                + "\",\"status\":\"submitted\",\"updatedAt\":" + updatedAt + ",\"snoozedUntil\":0}";
    }

    protected void writeV2Document(long revision, int stateCount) throws IOException {
        StringBuilder json = new StringBuilder("{\"schemaVersion\":2,\"revision\":" + revision + ",\"states\":{");
        for (int i = 0; i < stateCount; i++) {
            if (i > 0) {
                json.append(",");
            }
            String surveyId = String.format("aaaaaaaa-bbbb-cccc-dddd-%012d", i);
            json.append("\"").append(surveyId).append("\":").append(stateJson(surveyId, i + 1L));
        }
        json.append("},\"seen\":{}}");
        Files.createDirectories(stateFile().getParent());
        Files.writeString(stateFile(), json.toString(), StandardCharsets.UTF_8);
    }

    protected java.util.List<Path> corruptFiles() throws IOException {
        try (var stream = Files.list(stateFile().getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }

    protected java.util.List<Path> corruptFilesOf(Path file) throws IOException {
        if (!Files.isDirectory(file.getParent())) {
            return java.util.List.of();
        }
        try (var stream = Files.list(file.getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }
}
