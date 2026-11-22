package top.sywyar.pixivdownload.download.controller;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.download.DownloadWorkbenchPlugin;
import top.sywyar.pixivdownload.plugin.api.schedule.management.ManualBatchSubmission;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AuthorBatchControllerTest {
    @Test
    void submitsFullAuthorSourceUnderTheCallingProgramAndReturnsItsBatchLocation() {
        var batches = mock(ManualBatchSubmission.class);
        var id = UUID.randomUUID();
        when(batches.submit(anyString(), anyString(), anyString(), anyString(), anyString(), any())).thenReturn(id);
        var json = JsonMapper.builder().build();
        var controller = new AuthorBatchController(batches, json);
        var request = new MockHttpServletRequest();
        var program = new ProgramIdentity(UUID.randomUUID(), Set.of());
        request.setAttribute(ProgramIdentity.REQUEST_ATTRIBUTE, program);
        assertThatThrownBy(() -> controller.create(0, "illust", null, request)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> controller.create(42, "mixed", null, request)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(batches);
        var response = controller.create(42, "novel", " fixture ", request);
        assertThat(response.getStatusCode().value()).isEqualTo(202);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/download/batches/" + id);
        assertThat(response.getBody().id()).isEqualTo(id);
        var definition = ArgumentCaptor.forClass(String.class);
        verify(batches).submit(eq(DownloadWorkbenchPlugin.ID), eq("user-new"), anyString(), definition.capture(), eq("fixture"), eq(program));
        assertThat(json.readTree(definition.getValue())).isEqualTo(json.readTree("{\"kind\":\"novel\",\"source\":{\"userId\":\"42\"}}"));
    }
}
