package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.browser.NovelBrowserFetchTicketStore;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("浏览器授权小说导入端点")
class NovelBrowserImportControllerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MessageResolver messages = mock(MessageResolver.class);

    @Test
    @DisplayName("本机单人模式把受约束 Pixiv 响应换成一次性下载票据")
    void localSoloImportIssuesOneTimeFetchTicket() throws Exception {
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore();
        NovelBrowserImportController controller = controller("solo", store);
        MockHttpServletRequest tokenRequest = localRequest();

        var tokenResponse = controller.issueImportToken(tokenRequest);
        var importToken = ((NovelBrowserImportController.ImportTokenResponse) tokenResponse.getBody()).token();
        MockHttpServletRequest importRequest = localRequest();
        importRequest.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, importToken);
        importRequest.setContentType("application/json");
        importRequest.setContent("""
                {"error":false,"body":{"id":"42","title":"restricted","content":"body",
                "userId":"7","userName":"author","tags":{"tags":[]}}}
                """.getBytes(StandardCharsets.UTF_8));

        var response = controller.importNovel(42L, importRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        var fetchToken = ((NovelBrowserImportController.FetchTicketResponse) response.getBody()).fetchToken();
        NovelBrowserFetchTicketStore.ImportedNovel imported = store.consumeFetchTicket(
                fetchToken, 42L, RequestOwnerIdentity.adminScope(), null, true).orElseThrow();
        assertThat(imported.rawMetaJson())
                .doesNotContain("content", "textEmbeddedImages")
                .contains("\"title\":\"restricted\"");
        assertThat(store.consumeFetchTicket(
                fetchToken, 42L, RequestOwnerIdentity.adminScope(), null, true)).isEmpty();
    }

    @Test
    @DisplayName("远程请求、多人模式与作品 ID 不一致均被拒绝")
    void rejectsRemoteMultiAndMismatchedNovel() throws Exception {
        NovelBrowserFetchTicketStore soloStore = new NovelBrowserFetchTicketStore();
        NovelBrowserImportController solo = controller("solo", soloStore);
        MockHttpServletRequest remote = localRequest();
        remote.setRemoteAddr("192.0.2.10");
        assertThat(solo.issueImportToken(remote).getStatusCode().value()).isEqualTo(403);

        NovelBrowserImportController multi = controller("multi", new NovelBrowserFetchTicketStore());
        assertThat(multi.issueImportToken(localRequest()).getStatusCode().value()).isEqualTo(403);

        var tokenResponse = solo.issueImportToken(localRequest());
        var importToken = ((NovelBrowserImportController.ImportTokenResponse) tokenResponse.getBody()).token();
        MockHttpServletRequest mismatch = localRequest();
        mismatch.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, importToken);
        mismatch.setContentType("application/json");
        mismatch.setContent("""
                {"error":false,"body":{"id":"43","title":"other","content":"body",
                "userId":"7","userName":"author"}}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(solo.importNovel(42L, mismatch).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("导入响应体超过上限时在 JSON 解析前拒绝")
    void rejectsOversizedPayloadBeforeParsing() throws Exception {
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore();
        NovelBrowserImportController controller = controller("solo", store);
        var tokenResponse = controller.issueImportToken(localRequest());
        var importToken = ((NovelBrowserImportController.ImportTokenResponse) tokenResponse.getBody()).token();
        MockHttpServletRequest request = localRequest();
        request.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, importToken);
        request.setContent(new byte[NovelBrowserImportController.MAX_RESPONSE_BYTES + 1]);

        assertThat(controller.importNovel(42L, request).getStatusCode().value()).isEqualTo(413);
    }

    @Test
    @DisplayName("剪除正文后原始元数据仍超限时拒绝签发下载票据")
    void rejectsOversizedRawMetadata() throws Exception {
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore();
        NovelBrowserImportController controller = controller("solo", store);
        String importToken = ((NovelBrowserImportController.ImportTokenResponse)
                controller.issueImportToken(localRequest()).getBody()).token();
        MockHttpServletRequest request = localRequest();
        request.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, importToken);
        request.setContentType("application/json");
        request.setContent(("{\"error\":false,\"body\":{\"id\":\"42\",\"title\":\"title\","
                + "\"content\":\"body\",\"userId\":\"7\",\"userName\":\"author\","
                + "\"unexpected\":\"" + "x".repeat(256 * 1024) + "\"}}")
                .getBytes(StandardCharsets.UTF_8));

        assertThat(controller.importNovel(42L, request).getStatusCode().value()).isEqualTo(413);
    }

    @Test
    @DisplayName("重复字段、尾随 JSON 与非 Pixiv 图片地址不会进入下载票据")
    void rejectsMalformedJsonAndUnsafeImageUrls() throws Exception {
        NovelBrowserFetchTicketStore store = new NovelBrowserFetchTicketStore();
        NovelBrowserImportController controller = controller("solo", store);

        String duplicateToken = ((NovelBrowserImportController.ImportTokenResponse)
                controller.issueImportToken(localRequest()).getBody()).token();
        MockHttpServletRequest duplicate = localRequest();
        duplicate.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, duplicateToken);
        duplicate.setContentType("application/json");
        duplicate.setContent("""
                {"error":false,"body":{"id":"42","title":"first","title":"second",
                "content":"body","userId":"7","userName":"author"}}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(controller.importNovel(42L, duplicate).getStatusCode().value()).isEqualTo(400);

        String trailingToken = ((NovelBrowserImportController.ImportTokenResponse)
                controller.issueImportToken(localRequest()).getBody()).token();
        MockHttpServletRequest trailing = localRequest();
        trailing.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, trailingToken);
        trailing.setContentType("application/json");
        trailing.setContent("""
                {"error":false,"body":{"id":"42","title":"title","content":"body",
                "userId":"7","userName":"author"}} {"ignored":true}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(controller.importNovel(42L, trailing).getStatusCode().value()).isEqualTo(400);

        String imageToken = ((NovelBrowserImportController.ImportTokenResponse)
                controller.issueImportToken(localRequest()).getBody()).token();
        MockHttpServletRequest unsafeImage = localRequest();
        unsafeImage.addHeader(NovelBrowserImportController.IMPORT_TOKEN_HEADER, imageToken);
        unsafeImage.setContentType("application/json");
        unsafeImage.setContent("""
                {"error":false,"body":{"id":"42","title":"title","content":"body",
                "userId":"7","userName":"author","coverUrl":"https://evil.example/cover.jpg"}}
                """.getBytes(StandardCharsets.UTF_8));
        assertThat(controller.importNovel(42L, unsafeImage).getStatusCode().value()).isEqualTo(400);
    }

    private NovelBrowserImportController controller(
            String mode, NovelBrowserFetchTicketStore store) {
        when(messages.get(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        ApplicationModeProvider modeProvider = () -> mode;
        return new NovelBrowserImportController(objectMapper, store, modeProvider, messages);
    }

    private static MockHttpServletRequest localRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("Host", "localhost:6999");
        return request;
    }
}
