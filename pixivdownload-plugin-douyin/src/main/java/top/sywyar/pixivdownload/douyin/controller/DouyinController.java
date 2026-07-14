package top.sywyar.pixivdownload.douyin.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import top.sywyar.pixivdownload.common.UuidUtils;
import top.sywyar.pixivdownload.common.NetworkUtils;
import top.sywyar.pixivdownload.core.web.AcquisitionCredentialResolver;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;
import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.download.DouyinDownloadService;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadRequest;
import top.sywyar.pixivdownload.douyin.model.DouyinDownloadSnapshot;
import top.sywyar.pixivdownload.douyin.model.DouyinListing;
import top.sywyar.pixivdownload.douyin.model.DouyinParsedView;
import top.sywyar.pixivdownload.douyin.model.DouyinStartResponse;
import top.sywyar.pixivdownload.douyin.model.DouyinWork;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.setup.SetupService;

import java.util.List;

@RestController
@RequestMapping("/api/douyin")
@PluginManagedBean
public class DouyinController {

    private final DouyinDownloadService downloadService;
    private final SetupService setupService;

    public DouyinController(DouyinDownloadService downloadService,
                            SetupService setupService) {
        this.downloadService = downloadService;
        this.setupService = setupService;
    }

    @GetMapping("/resolve")
    public ResponseEntity<DouyinParsedView> resolve(@RequestParam("input") String input) {
        return downloadService.parse(input)
                .map(parsed -> ResponseEntity.ok(DouyinParsedView.from(parsed)))
                .orElseGet(() -> ResponseEntity.badRequest()
                        .body(DouyinParsedView.unsupported("douyin.error.invalid-url")));
    }

    @PostMapping("/download")
    public ResponseEntity<?> download(@RequestBody DouyinDownloadRequest request,
                                      HttpServletRequest httpRequest) {
        try {
            requireSecureCredentialTransport(httpRequest, request == null ? null : request.cookie());
            String ownerUuid = setupService.hasAdminScope(httpRequest)
                    ? null : UuidUtils.extractOrGenerateUuid(httpRequest);
            DouyinStartResponse response = downloadService.start(request, ownerUuid);
            return ResponseEntity.accepted().body(response);
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<DouyinDownloadSnapshot> status(@PathVariable String id,
                                                         HttpServletRequest request) {
        boolean admin = setupService.hasAdminScope(request);
        String ownerUuid = admin ? null : UuidUtils.extractOrGenerateUuid(request);
        return downloadService.status(id, ownerUuid, admin)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).build());
    }

    @GetMapping("/download/active")
    public ResponseEntity<List<DouyinDownloadSnapshot>> active(HttpServletRequest httpRequest) {
        boolean admin = setupService.hasAdminScope(httpRequest);
        String ownerUuid = admin ? null : UuidUtils.extractOrGenerateUuid(httpRequest);
        return ResponseEntity.ok(downloadService.active(ownerUuid, admin));
    }

    @GetMapping("/user/{userId}/works/ids")
    public ResponseEntity<?> userIds(@PathVariable String userId,
                                     @RequestParam(defaultValue = "0") int offset,
                                     @RequestParam(defaultValue = "24") int limit,
                                     @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                     HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            DouyinListing listing = downloadService.listUserWorks(userId, offset, limit, cookie);
            return ResponseEntity.ok(new IdsView(listing.items().stream().map(DouyinWork::id).toList()));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/user/{userId}/works/cards")
    public ResponseEntity<?> userCards(@PathVariable String userId,
                                       @RequestParam(name = "ids", required = false) List<String> ids,
                                       @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                       HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            DouyinListing listing = downloadService.listUserWorks(userId, 0, 100, cookie);
            List<DouyinWorkView> items = listing.items().stream()
                    .filter(work -> ids == null || ids.isEmpty() || ids.contains(work.id()))
                    .map(DouyinWorkView::from)
                    .toList();
            return ResponseEntity.ok(new ItemsView(items, items.size()));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/series/{seriesId}")
    public ResponseEntity<?> series(@PathVariable String seriesId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "24") int pageSize,
                                    @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                    HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            DouyinListing listing = downloadService.listSeriesWorks(seriesId, page, pageSize, cookie);
            return ResponseEntity.ok(new SeriesPageView(
                    new SeriesMetaView(
                            listing.title() == null ? seriesId : listing.title(),
                            listing.total(),
                            listing.ownerId(),
                            listing.ownerName()),
                    listing.items().stream().map(DouyinWorkView::from).toList(),
                    listing.lastPage(),
                    listing.page()));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/search")
    public ResponseEntity<?> search(@RequestParam("word") String word,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "24") int pageSize,
                                    @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                    HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            DouyinListing listing = downloadService.searchPublic(word, page, pageSize, cookie);
            return ResponseEntity.ok(new ItemsView(
                    listing.items().stream().map(DouyinWorkView::from).toList(),
                    listing.total()));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/search/range")
    public ResponseEntity<?> searchRange(@RequestParam("word") String word,
                                         @RequestParam(defaultValue = "1") int startPage,
                                         @RequestParam(defaultValue = "1") int endPage,
                                         @RequestParam(defaultValue = "24") int pageSize,
                                         @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                         HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            int safeStart = Math.max(1, startPage);
            int safeEnd = Math.max(safeStart, endPage);
            List<DouyinWorkView> items = new java.util.ArrayList<>();
            int total = 0;
            for (int page = safeStart; page <= safeEnd; page++) {
                DouyinListing listing = downloadService.searchPublic(word, page, pageSize, cookie);
                if (total <= 0) {
                    total = listing.total();
                }
                items.addAll(listing.items().stream().map(DouyinWorkView::from).toList());
            }
            return ResponseEntity.ok(new RangeView(items, total, safeStart, safeEnd,
                    safeEnd - safeStart + 1, safeEnd - safeStart + 1, safeEnd - safeStart + 1, 0));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    @GetMapping("/quick/public")
    public ResponseEntity<?> quickPublic(@RequestParam(defaultValue = "1") int page,
                                         @RequestParam(defaultValue = "24") int pageSize,
                                         @RequestHeader(name = "X-Douyin-Cookie", required = false) String cookie,
                                         HttpServletRequest request) {
        cookie = acquisitionCredential(request, cookie);
        try {
            requireSecureCredentialTransport(request, cookie);
            DouyinListing listing = downloadService.quickPublic(page, pageSize, cookie);
            return ResponseEntity.ok(new ItemsView(
                    listing.items().stream().map(DouyinWorkView::from).toList(),
                    listing.total()));
        } catch (DouyinClientException e) {
            return clientError(e);
        }
    }

    private static ResponseEntity<ErrorView> clientError(DouyinClientException e) {
        HttpStatus status = switch (e.code()) {
            case INVALID_URL, INVALID_SHORT_URL, UNSUPPORTED_CONTENT, SHORT_LINK_UNRESOLVED,
                 UNSUPPORTED_FINAL_URL, MEDIA_URL_MISSING, SIGNATURE_REQUIRED -> HttpStatus.BAD_REQUEST;
            case COOKIE_REQUIRED, COOKIE_MISSING_FIELDS, COOKIE_EXPIRED, LOGIN_OR_VERIFY_PAGE -> HttpStatus.UNAUTHORIZED;
            case RATE_LIMITED, HTTP_RATE_LIMITED -> HttpStatus.TOO_MANY_REQUESTS;
            case HTTP_FORBIDDEN -> HttpStatus.FORBIDDEN;
            case REDIRECT_LOOP, NON_DOUYIN_TARGET, DOWNLOAD_SIZE_MISMATCH -> HttpStatus.BAD_GATEWAY;
            case NETWORK_TIMEOUT, NETWORK_ERROR -> HttpStatus.BAD_GATEWAY;
            case CANCELLED -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ErrorView(false, e.code().name(), errorKey(e), e.getMessage()));
    }

    private static String acquisitionCredential(HttpServletRequest request, String legacyCredential) {
        return AcquisitionCredentialResolver.resolve(
                request == null ? null : request.getHeader(AcquisitionCredentialResolver.HEADER_NAME),
                legacyCredential);
    }

    private static void requireSecureCredentialTransport(HttpServletRequest request, String cookie)
            throws DouyinClientException {
        if (cookie != null && !cookie.isBlank() && request != null && !request.isSecure()
                && !NetworkUtils.isLocalRequest(request)) {
            throw new DouyinClientException(DouyinClientErrorCode.HTTP_FORBIDDEN,
                    "Douyin credentials require HTTPS for non-loopback clients");
        }
    }

    private static String errorKey(DouyinClientException e) {
        return "douyin.error." + e.code().name().toLowerCase(java.util.Locale.ROOT).replace('_', '-');
    }

    public record IdsView(List<String> ids) {
    }

    public record ItemsView(List<DouyinWorkView> items, int total) {
    }

    public record DouyinWorkView(String id,
                                 String title,
                                 String userId,
                                 String userName,
                                 String thumbnailUrl,
                                 String url,
                                 String kind) {
        static DouyinWorkView from(DouyinWork work) {
            return new DouyinWorkView(
                    work.id(),
                    work.title(),
                    work.authorId(),
                    work.authorName(),
                    work.thumbnailUrl(),
                    work.pageUrl(),
                    "douyin");
        }
    }

    public record SeriesPageView(SeriesMetaView series,
                                 List<DouyinWorkView> items,
                                 boolean isLastPage,
                                 int page) {
    }

    public record SeriesMetaView(String title, int total, String authorId, String authorName) {
    }

    public record RangeView(List<DouyinWorkView> items,
                            int total,
                            int startPage,
                            int endPage,
                            int requestedPages,
                            int acceptedPages,
                            int fetchedPages,
                            int limitPage) {
    }

    public record ErrorView(boolean success, String code, String messageKey, String message) {
    }
}
