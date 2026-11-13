package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ToolHistoryControllerTest {
    @TempDir Path directory;

    @Test void logDownloadUsesAttachmentAndRejectsPathsAndMissingFiles() throws Exception {
        var html = Files.createDirectory(directory.resolve("html"));
        Files.writeString(html.resolve("example.html"), "<script>alert('log')</script>");
        try (var paths = mockStatic(RuntimeFiles.class)) {
            paths.when(RuntimeFiles::logDirectory).thenReturn(directory);
            var mvc = MockMvcBuilders.standaloneSetup(new ToolHistoryController(mock(DesktopToolHistory.class))).build();
            mvc.perform(get("/api/tools/logs/example.html")).andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition", "attachment; filename=\"example.html\""))
                    .andExpect(header().string("Content-Security-Policy", "sandbox"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(content().string("<script>alert('log')</script>"));
            mvc.perform(get("/api/tools/logs/missing.html")).andExpect(status().isNotFound());
            mvc.perform(get("/api/tools/logs/private.yaml")).andExpect(status().isNotFound());
        }
    }
}
