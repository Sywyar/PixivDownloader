package top.sywyar.pixivdownload.core.db.pathprefix;

import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import top.sywyar.pixivdownload.gui.ToolApiRequests;
import top.sywyar.pixivdownload.i18n.TestI18nBeans;
import top.sywyar.pixivdownload.i18n.WebI18nService;
import java.util.List;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PathPrefixToolsControllerTest {
    @Test @org.junit.jupiter.api.DisplayName("路径修正保留分页和逐项失败，非法输入不进入事务")
    void boundsPagesAndRejectsUnsafeChangesBeforeTheTransaction() throws Exception {
        var service = mock(PathPrefixMigrationService.class);
        var mvc = MockMvcBuilders.standaloneSetup(new PathPrefixToolsController(service,
                new ToolApiRequests(TestI18nBeans.appMessages(), mock(WebI18nService.class)), TestI18nBeans.appMessages())).build();
        for (String body : List.of("{}", "{\"registerPaths\":[null]}", "{\"updates\":[{\"id\":-1,\"path\":\"x\"}]}",
                "{\"registerPaths\":[\"line\\nline\"]}"))
            mvc.perform(post("/api/tools/path-prefixes").contentType("application/json").content(body)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/tools/path-prefixes").param("limit", "1001")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
        when(service.list()).thenReturn(List.of(new PathPrefixMigrationService.PathPrefixView(1, "/one", false, false),
                new PathPrefixMigrationService.PathPrefixView(2, "/two", false, false)));
        mvc.perform(get("/api/tools/path-prefixes").param("offset", "1").param("limit", "1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.prefixes[0].id").value(2));
        when(service.pinSymbolicRoot("/missing")).thenReturn(new PathPrefixMigrationService.PathPrefixMigrationResult(
                false, 0, List.of(new PathPrefixMigrationService.PrefixError(0, "not-found"))));
        mvc.perform(post("/api/tools/path-prefixes/pin").contentType("application/json").content("{\"path\":\"/missing\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PATH_PREFIX_REJECTED"))
                .andExpect(jsonPath("$.errors[0].reason").value("not-found"));
        when(service.pinSymbolicRoot("/old")).thenReturn(new PathPrefixMigrationService.PathPrefixMigrationResult(true, 1, List.of()));
        mvc.perform(post("/api/tools/path-prefixes/pin").contentType("application/json").content("{\"path\":\"/old\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.applied").value(1));
    }
}
