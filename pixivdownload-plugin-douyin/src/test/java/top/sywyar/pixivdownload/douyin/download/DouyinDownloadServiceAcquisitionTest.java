package top.sywyar.pixivdownload.douyin.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.core.download.InteractiveDownloadExecutionLane;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueGenerationDrain;
import top.sywyar.pixivdownload.plugin.api.download.queue.QueueNotAcceptingException;
import top.sywyar.pixivdownload.douyin.client.DouyinClient;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryRepository;
import top.sywyar.pixivdownload.douyin.db.history.DouyinHistoryService;
import top.sywyar.pixivdownload.douyin.db.history.DouyinSourceRelation;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkFileRecord;
import top.sywyar.pixivdownload.douyin.db.history.DouyinWorkRecord;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalDownload;
import top.sywyar.pixivdownload.douyin.model.input.DouyinCanonicalKind;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccount;
import top.sywyar.pixivdownload.douyin.model.account.DouyinAccountSource;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadPhase;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.download.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.listing.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMedia;
import top.sywyar.pixivdownload.douyin.model.work.DouyinMediaType;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedInput;
import top.sywyar.pixivdownload.douyin.model.input.DouyinParsedKind;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWork;
import top.sywyar.pixivdownload.douyin.model.work.DouyinWorkKind;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderListing;
import top.sywyar.pixivdownload.douyin.model.favorite.DouyinFavoriteFolderSummary;
import top.sywyar.pixivdownload.douyin.parse.DouyinUrlParser;
import top.sywyar.pixivdownload.douyin.settings.DouyinPluginSettingsService;
import top.sywyar.pixivdownload.douyin.settings.DouyinProxyMode;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceRequest;
import top.sywyar.pixivdownload.douyin.source.DouyinSourceTypes;

import java.lang.reflect.RecordComponent;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
@DisplayName("Douyin 下载服务来源适配与运行时设置")
class DouyinDownloadServiceAcquisitionTest extends DouyinDownloadServiceTestSupport {

    @Test
    @DisplayName("用户、合集与搜索入口通过可 mock client 表达且不伪造空搜索")
    void acquisitionAdaptersDelegateToClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listUserWorks("u1", -10, 0, VALID_COOKIE).items()).hasSize(1);
        assertThat(service.listSeriesWorks("s1", 0, 500, VALID_COOKIE).pageSize()).isEqualTo(100);
        assertThat(service.searchPublic("word", 0, 1, VALID_COOKIE).ownerName()).isEqualTo("search:word");
    }

    @Test
    @DisplayName("目标用户喜欢作品薄透传用户、游标与 Cookie 并限制窗口")
    void delegatesBoundedTargetUserLikedWorksToClient() throws Exception {
        FakeClient client = new FakeClient();
        DouyinDownloadService service = service(client, Runnable::run);

        DouyinListing logical = service.listUserLikedWorks("sec-target", -10, 0, VALID_COOKIE);
        DouyinListing cursor = service.listUserLikedWorksPage(
                "sec-target", "liked-current", 500, VALID_COOKIE);

        assertThat(logical.items()).extracting("id").containsExactly("liked-logical");
        assertThat(cursor.items()).extracting("id").containsExactly("liked-cursor");
        assertThat(client.lastLikedUserId).isEqualTo("sec-target");
        assertThat(client.lastLikedOffset).isZero();
        assertThat(client.lastLikedLogicalLimit).isEqualTo(24);
        assertThat(client.lastLikedCursor).isEqualTo("liked-current");
        assertThat(client.lastLikedCursorLimit).isEqualTo(100);
        assertThat(client.lastLikedCookie).isEqualTo(VALID_COOKIE);
    }

    @Test
    @DisplayName("合集作品游标页薄透传 Cookie 并限制页大小")
    void delegatesBoundedSeriesCursorPageToClient() throws Exception {
        FakeClient client = new FakeClient();
        client.seriesPageListing = new DouyinListing(List.of(FakeClient.work("s-page")),
                201, 1, 100, false, "合集", "series-1", "作者", "opaque-next", true);
        DouyinDownloadService service = service(client, Runnable::run);

        DouyinListing listing = service.listSeriesWorksPage("series-1", "opaque-current", 500, VALID_COOKIE);

        assertThat(listing.items()).extracting("id").containsExactly("s-page");
        assertThat(client.lastSeriesPageCursor).isEqualTo("opaque-current");
        assertThat(client.lastSeriesPageSize).isEqualTo(100);
        assertThat(client.lastSeriesPageCookie).isEqualTo(VALID_COOKIE);
    }

    @Test
    @DisplayName("自建收藏夹两层游标 API 薄透传 Cookie 并限制页大小")
    void delegatesBoundedFavoriteFolderCursorApisToClient() throws Exception {
        FakeClient client = new FakeClient();
        client.favoriteFolderListing = new DouyinFavoriteFolderListing(
                List.of(new DouyinFavoriteFolderSummary("folder-a", "收藏夹 A")),
                1, "folder-next", true);
        client.favoriteFolderWorksListing = new DouyinListing(List.of(FakeClient.work("folder-work")),
                3, 1, 100, false, "收藏夹 A", "folder-a", null, "works-next", true);
        DouyinDownloadService service = service(client, Runnable::run);

        var folders = service.listFavoriteFolders("folder-current", 500, VALID_COOKIE);
        var works = service.listFavoriteFolderWorksPage(
                "folder-a", "works-current", 500, VALID_COOKIE);

        assertThat(folders.items()).extracting("id").containsExactly("folder-a");
        assertThat(works.items()).extracting("id").containsExactly("folder-work");
        assertThat(client.lastFavoriteFolderCursor).isEqualTo("folder-current");
        assertThat(client.lastFavoriteFolderPageSize).isEqualTo(100);
        assertThat(client.lastFavoriteFolderCookie).isEqualTo(VALID_COOKIE);
        assertThat(client.lastFavoriteFolderWorksId).isEqualTo("folder-a");
        assertThat(client.lastFavoriteFolderWorksCursor).isEqualTo("works-current");
        assertThat(client.lastFavoriteFolderWorksPageSize).isEqualTo(100);
        assertThat(client.lastFavoriteFolderWorksCookie).isEqualTo(VALID_COOKIE);
    }

    @Test
    @DisplayName("账号全部作品越过游标前进的空页继续收集作品 ID")
    void allAccountWorkIdsContinuePastAdvancingEmptyPage() throws Exception {
        FakeClient client = new FakeClient();
        client.accountPages = List.of(
                new DouyinListing(List.of(), 2, 1, 50, false,
                        null, "account", "账号", "account-next", true),
                new DouyinListing(List.of(FakeClient.work("account-work")), 2, 2, 50, true,
                        null, "account", "账号", "", false));
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listAllAccountWorkIds(DouyinAccountSource.OWN_WORKS, VALID_COOKIE))
                .containsExactly("account-work");
        assertThat(client.accountResolveCalls).isEqualTo(1);
        assertThat(client.accountPageCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("收藏作品跨收藏夹分页时按稳定作品 ID 精确去重")
    void favoriteWorkIdsDeduplicateAcrossFolders() throws Exception {
        FakeClient client = new FakeClient();
        client.accountPages = List.of(
                new DouyinListing(List.of(FakeClient.work("favorite-a"), FakeClient.work("favorite-b")),
                        4, 1, 50, false, null, "account", "账号", "fw1.next", true),
                new DouyinListing(List.of(FakeClient.work("favorite-a"), FakeClient.work("favorite-c")),
                        4, 2, 50, true, null, "account", "账号", "", false));
        DouyinDownloadService service = service(client, Runnable::run);

        assertThat(service.listAllAccountWorkIds(
                DouyinAccountSource.FAVORITE_WORKS, VALID_COOKIE))
                .containsExactly("favorite-a", "favorite-b", "favorite-c");
        assertThat(client.accountResolveCalls).isEqualTo(1);
        assertThat(client.accountPageCalls).isEqualTo(2);
    }

    @Test
    @DisplayName("下载按插件设置选择保存目录与代理运行时")
    void usesPluginRuntimeSettingsForDownloads() throws Exception {
        FakeClient inheritClient = new FakeClient();
        FakeClient proxyClient = new FakeClient();
        FakeClient directClient = new FakeClient();
        Path customDirectory = tempDir.resolve("custom-output");
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                inheritClient, proxyClient, directClient,
                inheritClient.downloader, proxyClient.downloader, directClient.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(customDirectory, DouyinProxyMode.DIRECT));

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(directClient.downloader.lastTarget).isNotNull();
        assertThat(inheritClient.downloader.lastTarget).isNull();
        assertThat(proxyClient.downloader.lastTarget).isNull();
        assertThat(directClient.downloader.lastDirectory.normalize()
                .startsWith(customDirectory.resolve("owner-a").normalize())).isTrue();
    }

    @Test
    @DisplayName("自定义代理模式选择自定义运行时")
    void usesCustomProxyRuntimeForDownloads() throws Exception {
        FakeClient inheritClient = new FakeClient();
        FakeClient proxyClient = new FakeClient();
        FakeClient customClient = new FakeClient();
        FakeClient directClient = new FakeClient();
        Path customDirectory = tempDir.resolve("custom-proxy-output");
        DouyinDownloadService service = new DouyinDownloadService(new DouyinUrlParser(),
                inheritClient, proxyClient, customClient, directClient,
                inheritClient.downloader, proxyClient.downloader, customClient.downloader, directClient.downloader,
                Runnable::run,
                DouyinPluginSettingsService.fixed(customDirectory, DouyinProxyMode.CUSTOM, "127.0.0.1", 10809),
                null);

        service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", VALID_COOKIE), "owner-a");

        assertThat(customClient.downloader.lastTarget).isNotNull();
        assertThat(inheritClient.downloader.lastTarget).isNull();
        assertThat(proxyClient.downloader.lastTarget).isNull();
        assertThat(directClient.downloader.lastTarget).isNull();
        assertThat(customClient.downloader.lastDirectory.normalize()
                .startsWith(customDirectory.resolve("owner-a").normalize())).isTrue();
    }

    @Test
    @DisplayName("下载启动前校验抖音 Cookie 必需字段")
    void validatesRequiredCookieFieldsBeforeStart() {
        DouyinDownloadService service = service(new FakeClient(), Runnable::run);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", null), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_REQUIRED);

        assertThatThrownBy(() -> service.start(new DouyinDownloadRequest(
                "https://www.douyin.com/video/7351234567890123456", "", "ttwid=tt; passport_csrf_token=csrf"), "owner-a"))
                .isInstanceOf(DouyinClientException.class)
                .extracting(error -> ((DouyinClientException) error).code())
                .isEqualTo(DouyinClientErrorCode.COOKIE_MISSING_FIELDS);
    }
}
