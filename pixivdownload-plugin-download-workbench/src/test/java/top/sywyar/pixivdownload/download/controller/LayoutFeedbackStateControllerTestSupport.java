package top.sywyar.pixivdownload.download.controller;

import jakarta.servlet.ServletInputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import top.sywyar.pixivdownload.plugin.api.storage.RuntimePathProvider;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateFiles;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;
import top.sywyar.pixivdownload.setup.InstallIdentityProvider;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class LayoutFeedbackStateControllerTestSupport {
    protected static final String SOLO = "solo";
    protected static final String MULTI = "multi";
    protected static final String INSTALL_ID = "11111111-2222-4333-8444-555555555555";
    protected static final String SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    protected static final String OTHER_SURVEY_ID = "aaaaaaaa-bbbb-cccc-dddd-ffffffffffff";
    protected static final String ENDPOINT = "/api/layout-feedback/state";

    @TempDir
    Path tempDir;

    protected LayoutFeedbackStateStore store() {
        return new LayoutFeedbackStateStore(new LayoutFeedbackStateFiles(stateDir()));
    }

    protected RuntimePathProvider stateDir() {
        Path stateDir = tempDir.resolve("state/download-workbench");
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.stateDirectory())
                .thenReturn(stateDir);
        return runtimePathProvider;
    }

    protected LayoutFeedbackStateController controller(String mode, LayoutFeedbackStateStore store,
                                                     InstallIdentityProvider identityProvider) {
        ApplicationModeProvider applicationModeProvider = mock(ApplicationModeProvider.class);
        when(applicationModeProvider.getMode()).thenReturn(mode);
        return new LayoutFeedbackStateController(
                store, applicationModeProvider,
                identityProvider == null ? () -> INSTALL_ID : identityProvider);
    }

    protected LayoutFeedbackStateController controller(String mode, LayoutFeedbackStateStore store,
                                                     InstallIdentityProvider identityProvider,
                                                     Clock clock) {
        ApplicationModeProvider applicationModeProvider = mock(ApplicationModeProvider.class);
        when(applicationModeProvider.getMode()).thenReturn(mode);
        return new LayoutFeedbackStateController(
                store, applicationModeProvider,
                identityProvider == null ? () -> INSTALL_ID : identityProvider,
                clock);
    }

    protected MockMvc mockMvc(String mode, LayoutFeedbackStateStore store,
                            InstallIdentityProvider identityProvider) {
        return org.springframework.test.web.servlet.setup.MockMvcBuilders
                .standaloneSetup(controller(mode, store, identityProvider))
                .build();
    }

    protected MockMvc mockMvc(String mode) {
        return mockMvc(mode, store(), null);
    }

    protected String commandJson(String command, String surveyId, List<String> layoutIds) {
        StringBuilder json = new StringBuilder();
        json.append("{\"surveyId\":\"").append(surveyId)
                .append("\",\"command\":\"").append(command).append("\"");
        if (layoutIds != null) {
            json.append(",\"layoutIds\":[");
            for (int i = 0; i < layoutIds.size(); i++) {
                if (i > 0) {
                    json.append(",");
                }
                json.append("\"").append(layoutIds.get(i)).append("\"");
            }
            json.append("]");
        }
        return json.append("}").toString();
    }

    protected byte[] commandBody(String command) {
        return commandBody(command, SURVEY_ID, null);
    }

    protected byte[] commandBody(String command, String surveyId, List<String> layoutIds) {
        return commandJson(command, surveyId, layoutIds).getBytes(StandardCharsets.UTF_8);
    }

    protected static String distinctIdOf(MvcResult result) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .get("distinctId").asText();
    }

    protected Path stateFilePath() {
        return tempDir.resolve("state/download-workbench/layout-feedback-state.json");
    }

    protected void writeV2Document(Path file, long revision, String statesJson) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file,
                "{\"schemaVersion\":2,\"revision\":" + revision + ",\"states\":" + statesJson
                        + ",\"seen\":{}}",
                StandardCharsets.UTF_8);
    }

    protected static org.hamcrest.Matcher<String> containsNoStorePrivate() {
        return new org.hamcrest.BaseMatcher<String>() {
            @Override
            public boolean matches(Object item) {
                return item instanceof String value
                        && value.contains("no-store") && value.contains("private");
            }

            @Override
            public void describeTo(org.hamcrest.Description description) {
                description.appendText("Cache-Control containing no-store and private");
            }
        };
    }

    protected RuntimePathProvider mockRuntimePath(Path stateDir) {
        RuntimePathProvider runtimePathProvider = mock(RuntimePathProvider.class);
        when(runtimePathProvider.stateDirectory())
                .thenReturn(stateDir);
        return runtimePathProvider;
    }

    protected java.util.List<Path> corruptFiles(Path file) throws IOException {
        try (var stream = Files.list(file.getParent())) {
            return stream.filter(path -> path.getFileName().toString().contains(".corrupt-")).toList();
        }
    }

    /** 记录 read() 调用次数 / 已消费字节的 ServletInputStream。 */
    protected static final class CountingStream extends ServletInputStream {
        private final InputStream delegate;
        private final int[] counter;

        CountingStream(InputStream delegate, int[] counter) {
            this.delegate = delegate;
            this.counter = counter;
        }

        @Override
        public int read() throws IOException {
            counter[0]++;
            return delegate.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int read = delegate.read(buffer, offset, length);
            if (read > 0) {
                counter[0] += read;
            }
            return read;
        }

        @Override
        public boolean isFinished() {
            return false;
        }

        @Override
        public boolean isReady() {
            return true;
        }

        @Override
        public void setReadListener(jakarta.servlet.ReadListener readListener) {
        }
    }
}
