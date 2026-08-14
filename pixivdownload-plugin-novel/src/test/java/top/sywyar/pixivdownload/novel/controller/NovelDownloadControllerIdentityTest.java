package top.sywyar.pixivdownload.novel.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import top.sywyar.pixivdownload.config.MultiModeSettings;
import top.sywyar.pixivdownload.core.pixiv.PixivAjaxClient;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessDecision;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessOutcome;
import top.sywyar.pixivdownload.core.pixiv.PixivProxyAccessPolicy;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaReservation;
import top.sywyar.pixivdownload.core.quota.VisitorDownloadQuotaService;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.novel.db.NovelDatabase;
import top.sywyar.pixivdownload.novel.download.NovelDownloadService;
import top.sywyar.pixivdownload.novel.export.NovelMergeService;
import top.sywyar.pixivdownload.novel.request.NovelDownloadCommand;
import top.sywyar.pixivdownload.novel.request.NovelDownloadRequest;
import top.sywyar.pixivdownload.novel.response.NovelQuotaExceededResponse;
import top.sywyar.pixivdownload.novel.translation.NovelAutoTranslateService;
import top.sywyar.pixivdownload.novel.translation.NovelTranslationService;
import top.sywyar.pixivdownload.novelgallery.NovelGalleryService;
import top.sywyar.pixivdownload.core.work.service.WorkVisibilityService;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentity;
import top.sywyar.pixivdownload.plugin.api.web.RequestOwnerIdentityResolver;
import top.sywyar.pixivdownload.setup.ApplicationModeProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("小说下载控制器宿主身份作用域")
class NovelDownloadControllerIdentityTest {

    @Mock private NovelDownloadService novelDownloadService;
    @Mock private NovelAutoTranslateService novelAutoTranslateService;
    @Mock private NovelDatabase novelDatabase;
    @Mock private NovelGalleryService novelGalleryService;
    @Mock private NovelMergeService novelMergeService;
    @Mock private NovelTranslationService novelTranslationService;
    @Mock private ApplicationModeProvider applicationModeProvider;
    @Mock private RequestOwnerIdentityResolver requestOwnerIdentityResolver;
    @Mock private WorkVisibilityService workVisibilityService;
    @Mock private VisitorDownloadQuotaService visitorDownloadQuotaService;
    @Mock private MultiModeSettings multiModeSettings;
    @Mock private PixivAjaxClient pixivAjaxClient;
    @Mock private PixivProxyAccessPolicy pixivProxyAccessPolicy;
    @Mock private MessageResolver messages;
    @Mock private HttpServletRequest httpRequest;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void allowAuthoritativePixivFetch() {
        lenient().when(pixivProxyAccessPolicy.evaluate(any(), anyBoolean()))
                .thenReturn(new PixivProxyAccessDecision(PixivProxyAccessOutcome.ALLOWED, null, 0, 0));
        lenient().when(pixivAjaxClient.get(any(), nullable(String.class))).thenReturn("""
                {"error":false,"body":{"title":"server title","content":"server content",
                 "userId":"42","userName":"server author"}}
                """);
    }

    @Test
    @DisplayName("multi 游客使用宿主 owner、执行配额并移除管理员专属选项")
    void multiVisitorUsesOwnerQuotaAndRestrictedOptions() {
        when(applicationModeProvider.getMode()).thenReturn("multi");
        when(requestOwnerIdentityResolver.resolve(httpRequest))
                .thenReturn(RequestOwnerIdentity.owner(" visitor-1 "));
        when(multiModeSettings.isQuotaEnabled()).thenReturn(true);
        when(visitorDownloadQuotaService.checkAndReserve("visitor-1", 1))
                .thenReturn(new VisitorDownloadQuotaReservation(true, 1, 10, 60));
        NovelDownloadCommand request = requestWithAdminOptions();

        var response = controller().downloadNovel(request, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(request.getOther().getCollectionId()).isNull();
        assertThat(request.getOther().isAutoTranslate()).isFalse();
        verify(visitorDownloadQuotaService).checkAndReserve("visitor-1", 1);
        verifyDownload("visitor-1", null, false);
    }

    @Test
    @DisplayName("multi 游客配额拒绝时应创建归档并保留原有响应字段")
    void multiVisitorQuotaRejectionCreatesArchive() {
        when(applicationModeProvider.getMode()).thenReturn("multi");
        when(requestOwnerIdentityResolver.resolve(httpRequest))
                .thenReturn(RequestOwnerIdentity.owner("visitor-1"));
        when(multiModeSettings.isQuotaEnabled()).thenReturn(true);
        when(multiModeSettings.getArchiveExpireMinutes()).thenReturn(15);
        when(visitorDownloadQuotaService.checkAndReserve("visitor-1", 1))
                .thenReturn(new VisitorDownloadQuotaReservation(false, 3, 10, 60));
        when(visitorDownloadQuotaService.createArchive("visitor-1")).thenReturn("archive-token");
        when(messages.get("download.quota.exceeded")).thenReturn("quota exceeded");

        var response = controller().downloadNovel(requestWithAdminOptions(), httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(429);
        assertThat(response.getBody()).isEqualTo(new NovelQuotaExceededResponse(
                true, "quota exceeded", "archive-token", 900, 3, 10, 60));
        verify(visitorDownloadQuotaService).createArchive("visitor-1");
        verify(novelDownloadService, never()).download(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    @DisplayName("multi 管理员保留管理员选项且不进入游客配额")
    void multiAdminKeepsAdminOptionsWithoutQuota() {
        when(applicationModeProvider.getMode()).thenReturn("multi");
        when(requestOwnerIdentityResolver.resolve(httpRequest)).thenReturn(RequestOwnerIdentity.adminScope());
        when(requestOwnerIdentityResolver.isAdminAuthenticated(httpRequest)).thenReturn(true);
        NovelDownloadCommand request = requestWithAdminOptions();

        var response = controller().downloadNovel(request, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(request.getOther().getCollectionId()).isEqualTo(7L);
        assertThat(request.getOther().isAutoTranslate()).isTrue();
        verifyNoInteractions(visitorDownloadQuotaService);
        verifyDownload(null, 7L, true);
    }

    @Test
    @DisplayName("solo 管理员不进入 multi 配额并保持原请求语义")
    void soloAdminKeepsRequestWithoutMultiQuota() {
        when(applicationModeProvider.getMode()).thenReturn("solo");
        when(requestOwnerIdentityResolver.resolve(httpRequest)).thenReturn(RequestOwnerIdentity.adminScope());
        when(requestOwnerIdentityResolver.isAdminAuthenticated(httpRequest)).thenReturn(true);
        NovelDownloadCommand request = requestWithAdminOptions();

        controller().downloadNovel(request, httpRequest);

        assertThat(request.getOther().getCollectionId()).isEqualTo(7L);
        assertThat(request.getOther().isAutoTranslate()).isTrue();
        verify(multiModeSettings, never()).isQuotaEnabled();
        verifyNoInteractions(visitorDownloadQuotaService);
        verifyDownload(null, 7L, true);
    }

    @Test
    @DisplayName("solo 未认证作用域仍保持旧请求语义且单独查询真实管理员认证")
    void soloAdminScopeKeepsLegacyRequestWhileCheckingAuthentication() {
        when(applicationModeProvider.getMode()).thenReturn("solo");
        when(requestOwnerIdentityResolver.resolve(httpRequest)).thenReturn(RequestOwnerIdentity.adminScope());
        when(requestOwnerIdentityResolver.isAdminAuthenticated(httpRequest)).thenReturn(false);
        NovelDownloadCommand request = requestWithAdminOptions();

        controller().downloadNovel(request, httpRequest);

        assertThat(request.getOther().getCollectionId()).isEqualTo(7L);
        assertThat(request.getOther().isAutoTranslate()).isTrue();
        verify(requestOwnerIdentityResolver).isAdminAuthenticated(httpRequest);
        verify(multiModeSettings, never()).isQuotaEnabled();
        verifyNoInteractions(visitorDownloadQuotaService);
        verifyDownload(null, 7L, true);
    }

    @Test
    @DisplayName("客户端伪造的小说正文与元数据不进入下载服务")
    void forgedNovelDataIsIgnored() throws Exception {
        when(applicationModeProvider.getMode()).thenReturn("solo");
        NovelDownloadCommand command = objectMapper.readValue("""
                {"novelId":123,"title":"forged title","content":"forged content",
                 "other":{"authorName":"forged author","username":"../escape","format":"txt"}}
                """, NovelDownloadCommand.class);

        var response = controller().downloadNovel(command, httpRequest);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        verify(novelDownloadService).download(argThat(request ->
                "server title".equals(request.getTitle())
                        && "server content".equals(request.getContent())
                        && "server author".equals(request.getOther().getAuthorName())
                        && request.getOther().getUsername() == null
                        && request.getOther().getRawMetaJson().contains("server title")), isNull());
    }

    private NovelDownloadController controller() {
        return new NovelDownloadController(
                novelDownloadService,
                novelAutoTranslateService,
                novelDatabase,
                novelGalleryService,
                novelMergeService,
                novelTranslationService,
                applicationModeProvider,
                requestOwnerIdentityResolver,
                workVisibilityService,
                visitorDownloadQuotaService,
                multiModeSettings,
                objectMapper,
                pixivAjaxClient,
                pixivProxyAccessPolicy,
                messages);
    }

    private static NovelDownloadCommand requestWithAdminOptions() {
        NovelDownloadCommand request = new NovelDownloadCommand();
        request.setNovelId(123L);
        NovelDownloadCommand.Other other = new NovelDownloadCommand.Other();
        other.setCollectionId(7L);
        other.setAutoTranslate(true);
        request.setOther(other);
        return request;
    }

    private void verifyDownload(String ownerUuid, Long collectionId, boolean autoTranslate) {
        if (ownerUuid == null) {
            verify(novelDownloadService).download(argThat(request -> matchesDownload(
                    request, collectionId, autoTranslate)), isNull());
        } else {
            verify(novelDownloadService).download(argThat(request -> matchesDownload(
                    request, collectionId, autoTranslate)), eq(ownerUuid));
        }
    }

    private static boolean matchesDownload(
            NovelDownloadRequest request, Long collectionId, boolean autoTranslate) {
        return "server title".equals(request.getTitle())
                && "server content".equals(request.getContent())
                && java.util.Objects.equals(collectionId, request.getOther().getCollectionId())
                && request.getOther().isAutoTranslate() == autoTranslate;
    }
}
