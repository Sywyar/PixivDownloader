package top.sywyar.pixivdownload.quota;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatcher;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.GlobalExceptionHandler;
import top.sywyar.pixivdownload.download.db.ArtworkRecord;
import top.sywyar.pixivdownload.download.db.PixivDatabase;
import top.sywyar.pixivdownload.setup.SetupService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
@DisplayName("ArchiveController 单元测试")
class ArchiveControllerTest {

    @TempDir
    Path tempDir;

    @Mock
    private UserQuotaService userQuotaService;
    @Mock
    private SetupService setupService;
    @Mock
    private PixivDatabase pixivDatabase;

    private MockMvc mockMvc;
    private MultiModeConfig multiModeConfig;

    @BeforeEach
    void setUp() {
        multiModeConfig = new MultiModeConfig();
        multiModeConfig.getQuota().setEnabled(true);
        multiModeConfig.getQuota().setArchiveExpireMinutes(60);

        ArchiveController controller = new ArchiveController(
                userQuotaService, multiModeConfig, setupService, pixivDatabase);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    @DisplayName("管理员初始化配额时应返回 adminMode")
    void shouldReturnAdminModeForLoggedInAdmin() throws Exception {
        when(setupService.isAdminLoggedIn(any())).thenReturn(true);

        mockMvc.perform(post("/api/quota/init"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.adminMode").value(true));
    }

    @Test
    @DisplayName("未登录时管理员打包接口应返回 401")
    void shouldRejectAdminPackWhenNotLoggedIn() throws Exception {
        when(setupService.isAdminLoggedIn(any())).thenReturn(false);

                mockMvc.perform(post("/api/archive/pack-artworks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\":[1,2]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("Unauthorized"));

        verify(userQuotaService, never()).triggerAdminArchive(any());
    }

    @Test
    @DisplayName("空作品列表应返回 400")
    void shouldReturn400ForEmptyArtworkIds() throws Exception {
        when(setupService.isAdminLoggedIn(any())).thenReturn(true);

                mockMvc.perform(post("/api/archive/pack-artworks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no artworks to pack"));
    }

    @Test
    @DisplayName("没有可用文件夹时应返回 204")
    void shouldReturn204WhenNoValidFoldersFound() throws Exception {
        when(setupService.isAdminLoggedIn(any())).thenReturn(true);
        when(pixivDatabase.getArtwork(1L)).thenReturn(new ArtworkRecord(
                1L, "测试", tempDir.resolve("missing").toString(),
                1, "jpg", 100L, false, null, null, false, null, null, null
        ));

        mockMvc.perform(post("/api/archive/pack-artworks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\":[1]}"))
                .andExpect(status().isNoContent());

        verify(userQuotaService, never()).triggerAdminArchive(any());
    }

    @Test
    @DisplayName("存在有效文件夹时应触发管理员打包并优先使用 moveFolder")
    void shouldTriggerAdminArchiveWithResolvedFolders() throws Exception {
        when(setupService.isAdminLoggedIn(any())).thenReturn(true);
        when(userQuotaService.triggerAdminArchive(any())).thenReturn("archive-token");

        Path movedFolder = Files.createDirectories(tempDir.resolve("12345-moved"));
        when(pixivDatabase.getArtwork(12345L)).thenReturn(new ArtworkRecord(
                12345L, "测试", tempDir.resolve("12345").toString(),
                2, "jpg", 100L, true, movedFolder.toString(), 200L, false, null, null, null
        ));

        mockMvc.perform(post("/api/archive/pack-artworks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"artworkIds\":[12345]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.archiveToken").value("archive-token"))
                .andExpect(jsonPath("$.archiveExpireSeconds").value(3600));

        verify(userQuotaService).triggerAdminArchive(argThat(pathListContains(movedFolder)));
    }

    private ArgumentMatcher<List<Path>> pathListContains(Path expected) {
        return paths -> paths != null && paths.size() == 1 && expected.equals(paths.get(0));
    }
}
