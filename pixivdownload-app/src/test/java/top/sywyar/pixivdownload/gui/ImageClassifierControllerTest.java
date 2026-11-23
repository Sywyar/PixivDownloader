package top.sywyar.pixivdownload.gui;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.core.download.ArtworkMoveService;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@DisplayName("分类工具 HTTP 文件操作")
class ImageClassifierControllerTest {
    @TempDir Path root;
    MockMvc mvc;
    ArtworkMoveService moves;

    @BeforeEach void setup() {
        var messages = TestI18nBeans.appMessages();
        moves = mock(ArtworkMoveService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ImageClassifierController(new DownloadConfig(), moves,
                new ToolApiRequests(messages, mock(WebI18nService.class)), messages)).build();
    }

    @Test
    @DisplayName("移动真实文件且分别报告删源与数据库记录结果")
    void movesFilesAndReportsIncompleteCleanup() throws Exception {
        Path source = Files.createDirectory(root.resolve("42"));
        Path target = root.resolve("selected");
        Files.write(source.resolve("42_p0.jpg"), new byte[]{1, 2, 3});
        Files.writeString(source.resolve("keep.txt"), "unrelated");
        when(moves.recordMove(eq(42L), anyString(), anyLong(), anyString())).thenReturn(true);
        mvc.perform(post("/api/tools/classifier/moves").contentType("application/json").content(body(source, target)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourceRemoved").value(false))
                .andExpect(jsonPath("$.recorded").value(true))
                .andExpect(jsonPath("$.issues[0]").value("CLASSIFIER_SOURCE_REMAINS"));
        assertThat(Files.readAllBytes(target.resolve("42_p0.jpg"))).containsExactly(1, 2, 3);
        assertThat(source.resolve("42_p0.jpg")).doesNotExist();
        assertThat(Files.readString(source.resolve("keep.txt"))).isEqualTo("unrelated");
        mvc.perform(delete("/api/tools/classifier/folders").param("path", source.toString()))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASSIFIER_DIRECTORY_NOT_EMPTY"));
    }

    @Test
    @DisplayName("同名目标不覆盖，失败不登记；图片附件不开放其它扩展名")
    void rejectsCollisionAndNonImageReads() throws Exception {
        Path source = Files.createDirectory(root.resolve("42"));
        Path target = Files.createDirectory(root.resolve("selected"));
        Files.write(source.resolve("42_p0.jpg"), new byte[]{1, 2, 3});
        Files.write(target.resolve("42_p0.jpg"), new byte[]{9});
        mvc.perform(post("/api/tools/classifier/moves").contentType("application/json").content(body(source, target)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CLASSIFIER_TARGET_EXISTS"));
        assertThat(Files.readAllBytes(target.resolve("42_p0.jpg"))).containsExactly(9);
        assertThat(Files.readAllBytes(source.resolve("42_p0.jpg"))).containsExactly(1, 2, 3);
        verifyNoInteractions(moves);
        mvc.perform(get("/api/tools/classifier/image").param("path", source.resolve("42_p0.jpg").toString()))
                .andExpect(status().isOk()).andExpect(header().string("Content-Disposition", "attachment"))
                .andExpect(content().bytes(new byte[]{1, 2, 3}));
        mvc.perform(get("/api/tools/classifier/image").param("path", root.resolve("config.properties").toString()))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST"))
                .andExpect(jsonPath("$.in").value("query")).andExpect(jsonPath("$.field").value("path"))
                .andExpect(jsonPath("$.reason").value("unsupported"));
    }

    @Test
    @DisplayName("目录按桌面数值顺序分页，缺图及嵌套目标在写入前拒绝")
    void pagesFoldersAndRejectsInvalidMoves() throws Exception {
        Path source = Files.createDirectory(root.resolve("2"));
        Files.createDirectory(root.resolve("10"));
        Files.createDirectory(root.resolve("name"));
        mvc.perform(get("/api/tools/classifier/folders").param("path", root.toString()).param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.paths[0]").value(source.toString()));
        Path target = root.resolve("new-target");
        mvc.perform(post("/api/tools/classifier/moves").contentType("application/json").content(body(source, target)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("CLASSIFIER_EMPTY"));
        assertThat(target).doesNotExist();
        mvc.perform(post("/api/tools/classifier/moves").contentType("application/json").content(body(source, source.resolve("nested"))))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(moves);
    }

    @Test
    @DisplayName("数据库记录失败保留已移动文件并明确返回未确认状态，空目录可删除")
    void reportsRecordFailureWithoutRepeatingMove() throws Exception {
        Path source = Files.createDirectory(root.resolve("42"));
        Path target = root.resolve("selected");
        Files.write(source.resolve("42_p0.jpg"), new byte[]{4});
        when(moves.recordMove(eq(42L), anyString(), anyLong(), anyString())).thenThrow(new IllegalStateException("private detail"));
        mvc.perform(post("/api/tools/classifier/moves").contentType("application/json").content(body(source, target)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.recorded").value(false))
                .andExpect(jsonPath("$.issues[0]").value("CLASSIFIER_RECORD_NOT_CONFIRMED"));
        assertThat(Files.readAllBytes(target.resolve("42_p0.jpg"))).containsExactly(4);
        Path empty = Files.createDirectory(root.resolve("empty"));
        mvc.perform(delete("/api/tools/classifier/folders").param("path", empty.toString())).andExpect(status().isNoContent());
        assertThat(empty).doesNotExist();
    }

    private static String body(Path source, Path target) {
        return JsonMapper.builder().build().writeValueAsString(Map.of("source", source.toString(), "target", target.toString(), "artworkId", 42));
    }
}
