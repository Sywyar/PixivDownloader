package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.db.DatabaseProviderTestFixtures;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;

import java.nio.file.Path;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class DirectoryToolsControllerTest {
    @TempDir Path directory;

    @Test void pagesMissingDirectoriesAndRepairsCurrentDatabaseWithoutMovingFiles() throws Exception {
        var database = directory.resolve("artworks.db");
        var dataSource = new org.sqlite.SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + database);
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE artworks (artwork_id INTEGER PRIMARY KEY, title TEXT, folder TEXT, moved INTEGER, move_folder TEXT, deleted INTEGER, time INTEGER)");
            statement.execute("INSERT INTO artworks VALUES (1, 'one', 'missing-one', 0, NULL, 0, 1), (2, 'two', 'missing-two', 0, NULL, 0, 1)");
        }
        var download = new DownloadConfig();
        download.setRootFolder(directory.resolve("{artwork_id}").toString());
        var messages = TestI18nBeans.appMessages();
        var mvc = MockMvcBuilders.standaloneSetup(new DirectoryToolsController(dataSource,
                DatabaseProviderTestFixtures.sqlite(database), download,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
        mvc.perform(get("/api/tools/directories").param("offset", "1").param("limit", "1"))
                .andDo(result -> { if (result.getResolvedException() != null) throw new AssertionError(result.getResolvedException()); })
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.missing").value(2)).andExpect(jsonPath("$.inaccessible.length()").value(1))
                .andExpect(jsonPath("$.inaccessible[0].artworkId").value(1));
        mvc.perform(get("/api/tools/directories").param("limit", "1001")).andExpect(status().isBadRequest());
        String path = tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(directory.toString());
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                .content("{\"artworkId\":1,\"moved\":false,\"path\":" + path + "}"))
                .andExpect(status().isNoContent());
        mvc.perform(get("/api/tools/directories")).andExpect(status().isOk())
                .andExpect(jsonPath("$.missing").value(1)).andExpect(jsonPath("$.inaccessible[0].artworkId").value(2));
        mvc.perform(post("/api/tools/directories").contentType("application/json")
                .content("{\"artworkId\":999,\"moved\":false,\"path\":" + path + "}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ARTWORK_NOT_FOUND"));
    }
}
