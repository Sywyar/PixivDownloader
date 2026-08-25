package top.sywyar.pixivdownload.douyin.download;

import org.junit.jupiter.api.io.TempDir;
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
abstract class DouyinDownloadServiceTestSupport {

    static final String VALID_COOKIE =
            "ttwid=tt; passport_csrf_token=csrf; sessionid=sid; sid_tt=sid; sid_guard=guard";
    static final byte[] DOWNLOADED_VIDEO_BYTES = {
            0, 0, 0, 0x18, 'f', 't', 'y', 'p', 'i', 's', 'o'
    };
    static final byte[] EXISTING_VIDEO_BYTES = {0, 0, 0, 0x18, 'f', 't', 'y', 'p'};
    static final byte[] EXISTING_IMAGE_BYTES = {
            (byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0, 0, 0
    };

    @TempDir
    Path tempDir;
    DouyinDownloadService service(FakeClient client, InteractiveDownloadExecutionLane executor) {
        return new DouyinDownloadService(new DouyinUrlParser(), client, client.downloader, executor, tempDir);
    }

    static RecordingHistoryService recordingHistoryService() {
        DouyinHistoryRepository repository = mock(DouyinHistoryRepository.class);
        when(repository.findMaxTime()).thenReturn(null);
        return new RecordingHistoryService(repository);
    }

    static List<DouyinWork> works(int first, int count, boolean twoMedia) {
        List<DouyinWork> works = new ArrayList<>();
        for (int offset = 0; offset < count; offset++) {
            String id = Integer.toString(first + offset);
            works.add(twoMedia ? FakeClient.workWithTwoMedia(id) : FakeClient.work(id));
        }
        return List.copyOf(works);
    }

    static List<DouyinWork> concat(List<DouyinWork> first, List<DouyinWork> second) {
        List<DouyinWork> combined = new ArrayList<>(first);
        combined.addAll(second);
        return List.copyOf(combined);
    }

    static final class CapturingExecutor implements InteractiveDownloadExecutionLane {
        final java.util.ArrayList<Runnable> tasks = new java.util.ArrayList<>();

        @Override
        public void execute(Runnable task) {
            tasks.add(task);
        }

        void runAll() {
            List.copyOf(tasks).forEach(Runnable::run);
            tasks.clear();
        }
    }

    static final class FakeClient implements DouyinClient {
        final FakeMediaDownloader downloader = new FakeMediaDownloader();
        final Map<String, String> singleStableIds = new LinkedHashMap<>();
        final Map<String, String> collectionStableIds = new LinkedHashMap<>();
        DouyinClientErrorCode resolveFailure;
        List<DouyinWork> seriesWorks = List.of(work("s-default"));
        List<List<DouyinWork>> seriesPages;
        int seriesListCalls;
        String lastSeriesId;
        DouyinListing seriesPageListing;
        String lastSeriesPageCursor;
        int lastSeriesPageSize;
        String lastSeriesPageCookie;
        String lastLikedUserId;
        int lastLikedOffset;
        int lastLikedLogicalLimit;
        String lastLikedCursor;
        int lastLikedCursorLimit;
        String lastLikedCookie;
        DouyinFavoriteFolderListing favoriteFolderListing;
        String lastFavoriteFolderCursor;
        int lastFavoriteFolderPageSize;
        String lastFavoriteFolderCookie;
        DouyinListing favoriteFolderWorksListing;
        String lastFavoriteFolderWorksId;
        String lastFavoriteFolderWorksCursor;
        int lastFavoriteFolderWorksPageSize;
        String lastFavoriteFolderWorksCookie;
        List<DouyinListing> accountPages;
        int accountResolveCalls;
        int accountPageCalls;
        CountDownLatch resolveEntered;
        CountDownLatch releaseResolve;
        String thumbnailUrl = "";
        boolean livePhoto;

        void blockResolve(CountDownLatch entered, CountDownLatch release) {
            this.resolveEntered = entered;
            this.releaseResolve = release;
        }

        void mapSingle(String token, String stableId) {
            singleStableIds.put(token, stableId);
        }

        void mapCollection(String token, String stableId) {
            collectionStableIds.put(token, stableId);
        }

        @Override
        public DouyinCanonicalDownload resolveDownload(String input, String cookie) throws DouyinClientException {
            if (resolveEntered != null) {
                resolveEntered.countDown();
                try {
                    releaseResolve.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new DouyinClientException(DouyinClientErrorCode.NETWORK_ERROR, "interrupted");
                }
            }
            if (resolveFailure != null) {
                throw new DouyinClientException(resolveFailure, resolveFailure.name());
            }
            DouyinParsedInput parsed = resolveInput(input, cookie);
            String collectionId = mapped(collectionStableIds, input);
            if (collectionId != null || parsed.kind() == DouyinParsedKind.COLLECTION) {
                String stableId = collectionId == null ? parsed.id() : collectionId;
                return new DouyinCanonicalDownload(DouyinCanonicalKind.COLLECTION, stableId,
                        "https://www.douyin.com/mix/" + stableId, null, input);
            }
            DouyinWork work = resolvedWork(stableWorkId(input, parsed));
            return new DouyinCanonicalDownload(DouyinCanonicalKind.SINGLE_WORK, work.id(),
                    "https://www.douyin.com/video/" + work.id(), work, input);
        }

        @Override
        public DouyinParsedInput resolveInput(String input, String cookie) throws DouyinClientException {
            return new DouyinUrlParser().parse(input)
                    .orElseThrow(() -> new DouyinClientException(DouyinClientErrorCode.INVALID_URL, "invalid"));
        }

        @Override
        public DouyinWork resolvePublicWork(String input, String cookie) throws DouyinClientException {
            if (resolveFailure != null) {
                throw new DouyinClientException(resolveFailure, resolveFailure.name());
            }
            return resolvedWork(stableWorkId(input, resolveInput(input, cookie)));
        }

        @Override
        public DouyinListing listUserWorks(String userId, int offset, int limit, String cookie) {
            return new DouyinListing(List.of(work("u-" + userId)), 1, 1, limit, true, null, userId, "user:" + userId);
        }

        @Override
        public DouyinListing listUserLikedWorks(String userId, int offset, int limit, String cookie) {
            lastLikedUserId = userId;
            lastLikedOffset = offset;
            lastLikedLogicalLimit = limit;
            lastLikedCookie = cookie;
            return new DouyinListing(List.of(work("liked-logical")), 1, 1, limit,
                    true, null, userId, "user:" + userId);
        }

        @Override
        public DouyinListing listUserLikedWorksPage(String userId,
                                                    String cursor,
                                                    int limit,
                                                    String cookie) {
            lastLikedUserId = userId;
            lastLikedCursor = cursor;
            lastLikedCursorLimit = limit;
            lastLikedCookie = cookie;
            return new DouyinListing(List.of(work("liked-cursor")), 1, 1, limit,
                    true, null, userId, "user:" + userId, "liked-next", false);
        }

        @Override
        public DouyinListing listSeriesWorks(String seriesId, int page, int pageSize, String cookie) {
            lastSeriesId = seriesId;
            seriesListCalls++;
            if (seriesPages != null) {
                List<DouyinWork> items = page > 0 && page <= seriesPages.size()
                        ? seriesPages.get(page - 1)
                        : List.of();
                boolean lastPage = page >= seriesPages.size();
                int total = lastPage ? seriesPages.stream().mapToInt(List::size).sum() : 0;
                return new DouyinListing(items, total, page, pageSize, lastPage,
                        "series:" + seriesId, seriesId, "owner");
            }
            return new DouyinListing(seriesWorks, seriesWorks.size(), page, pageSize, true,
                    "series:" + seriesId, seriesId, "owner");
        }

        @Override
        public DouyinListing listSeriesWorksPage(String seriesId, String cursor, int limit, String cookie)
                throws DouyinClientException {
            lastSeriesPageCursor = cursor;
            lastSeriesPageSize = limit;
            lastSeriesPageCookie = cookie;
            return seriesPageListing != null
                    ? seriesPageListing
                    : DouyinClient.super.listSeriesWorksPage(seriesId, cursor, limit, cookie);
        }

        @Override
        public DouyinFavoriteFolderListing listFavoriteFolders(String cursor,
                                                                int limit,
                                                                String cookie) {
            lastFavoriteFolderCursor = cursor;
            lastFavoriteFolderPageSize = limit;
            lastFavoriteFolderCookie = cookie;
            return favoriteFolderListing;
        }

        @Override
        public DouyinListing listFavoriteFolderWorksPage(String folderId,
                                                          String cursor,
                                                          int limit,
                                                          String cookie) {
            lastFavoriteFolderWorksId = folderId;
            lastFavoriteFolderWorksCursor = cursor;
            lastFavoriteFolderWorksPageSize = limit;
            lastFavoriteFolderWorksCookie = cookie;
            return favoriteFolderWorksListing;
        }

        @Override
        public DouyinListing searchPublic(String word, int page, int pageSize, String cookie) {
            return new DouyinListing(List.of(work("q-" + word)), 1, page, pageSize, true,
                    null, null, "search:" + word);
        }

        @Override
        public DouyinAccount resolveAccount(String cookie) {
            accountResolveCalls++;
            return new DouyinAccount("account", "sec-account", "账号", "account");
        }

        @Override
        public DouyinListing listAccountWorksPage(DouyinAccountSource source,
                                                  String cursor,
                                                  int limit,
                                                  String cookie) throws DouyinClientException {
            if (accountPages == null || accountPageCalls >= accountPages.size()) {
                return DouyinClient.super.listAccountWorksPage(source, cursor, limit, cookie);
            }
            return accountPages.get(accountPageCalls++);
        }

        String stableWorkId(String input, DouyinParsedInput parsed) {
            String mapped = mapped(singleStableIds, input);
            if (mapped != null) {
                return mapped;
            }
            return parsed.kind().singleWork() ? parsed.id() : "7351234567890123456";
        }

        DouyinWork resolvedWork(String id) {
            DouyinWork base = livePhoto ? workWithTwoMedia(id) : work(id);
            if (thumbnailUrl == null || thumbnailUrl.isBlank()) {
                return base;
            }
            return new DouyinWork(base.id(), base.title(), base.description(), base.itemTitle(), base.caption(),
                    base.authorId(), base.authorName(), base.pageUrl(), thumbnailUrl, base.mediaUrl(),
                    base.media(), base.kind(), base.publishTimeEpochSeconds(), base.collectionId(),
                    base.collectionTitle());
        }

        static String mapped(Map<String, String> mappings, String input) {
            String value = input == null ? "" : input;
            return mappings.entrySet().stream()
                    .filter(entry -> value.contains(entry.getKey()))
                    .map(Map.Entry::getValue)
                    .findFirst()
                    .orElse(null);
        }

        static DouyinWork work(String id) {
            return new DouyinWork(id, "Title " + id, "author", "Author", "https://www.douyin.com/video/" + id,
                    "", URI.create("https://media.example/" + id + ".mp4"));
        }

        static DouyinWork workWithTwoMedia(String id) {
            return new DouyinWork(id, "Title " + id, "author", "Author", "https://www.douyin.com/video/" + id,
                    "", null,
                    List.of(
                            new DouyinMedia(id + "-image", DouyinMediaType.IMAGE,
                                    URI.create("https://media.example/" + id + ".jpg"), id + "-image", "jpg", null, null),
                            new DouyinMedia(id + "-video", DouyinMediaType.LIVE_PHOTO_VIDEO,
                                    URI.create("https://media.example/" + id + ".mp4"), id + "-video", "mp4", null, null)),
                    DouyinWorkKind.LIVE_PHOTO,
                    null, null, null);
        }
    }

    static final class FakeMediaDownloader extends DouyinMediaDownloader {
        Path lastDirectory;
        Path lastTarget;
        int calls;
        int downloadedFiles;
        String lastCredential;
        DouyinClientErrorCode failure;

        FakeMediaDownloader() {
            super(null, host -> true);
        }

        @Override
        public List<DouyinDownloadedFile> download(List<DouyinMedia> media,
                                                   Path directory,
                                                   BooleanSupplier cancellationRequested,
                                                   String credential)
                throws java.io.IOException, DouyinClientException {
            if (cancellationRequested != null && cancellationRequested.getAsBoolean()) {
                throw new DouyinClientException(DouyinClientErrorCode.CANCELLED, "cancelled");
            }
            calls++;
            lastCredential = credential;
            if (failure != null) {
                throw new DouyinClientException(failure, failure.name());
            }
            lastDirectory = directory;
            Files.createDirectories(directory);
            List<DouyinMedia> candidates = media == null || media.isEmpty()
                    ? List.of(new DouyinMedia("fallback", DouyinMediaType.VIDEO,
                    URI.create("https://media.example/fallback.mp4"), "fallback", "mp4", null, null))
                    : media;
            List<DouyinDownloadedFile> downloaded = new ArrayList<>();
            for (DouyinMedia candidate : candidates) {
                lastTarget = directory.resolve(candidate.fileNameStem() + "." + candidate.extension());
                Files.write(lastTarget, DOWNLOADED_VIDEO_BYTES);
                String contentType = switch (candidate.type()) {
                    case IMAGE, COVER -> "image/" + candidate.extension();
                    default -> "video/mp4";
                };
                downloaded.add(new DouyinDownloadedFile(lastTarget, 11, contentType));
            }
            downloadedFiles += downloaded.size();
            return List.copyOf(downloaded);
        }
    }

    static final class RecordingHistoryService extends DouyinHistoryService {
        int calls;
        DouyinWork work;
        Path folder;
        List<DouyinDownloadedFile> files = List.of();
        String sourceUrl;
        String collectionId;
        boolean throwOnRecord;
        CountDownLatch recordEntered;
        CountDownLatch releaseRecord;
        DouyinWorkRecord existingRecord;
        List<DouyinWorkFileRecord> existingFiles = List.of();
        final List<DouyinSourceRelation> relations = new ArrayList<>();

        RecordingHistoryService(DouyinHistoryRepository repository) {
            super(repository);
        }

        void blockRecord(CountDownLatch entered, CountDownLatch release) {
            this.recordEntered = entered;
            this.releaseRecord = release;
        }

        @Override
        public boolean recordCompleted(DouyinWork work,
                                       Path folder,
                                       List<DouyinDownloadedFile> files,
                                       String sourceUrl,
                                       String collectionId,
                                       String collectionTitle,
                                       Integer collectionOrder,
                                       DouyinSourceRelation relation) {
            return captureCompleted(work, folder, files, sourceUrl, collectionId,
                    relation == null ? List.of() : List.of(relation));
        }

        @Override
        public boolean recordCompleted(DouyinWork work,
                                       Path folder,
                                       List<DouyinDownloadedFile> files,
                                       String sourceUrl,
                                       String collectionId,
                                       String collectionTitle,
                                       Integer collectionOrder,
                                       List<DouyinSourceRelation> sourceRelations) {
            return captureCompleted(work, folder, files, sourceUrl, collectionId, sourceRelations);
        }

        boolean captureCompleted(DouyinWork work,
                                         Path folder,
                                         List<DouyinDownloadedFile> files,
                                         String sourceUrl,
                                         String collectionId,
                                         List<DouyinSourceRelation> sourceRelations) {
            this.calls++;
            if (throwOnRecord) {
                throw new IllegalStateException("history unavailable");
            }
            if (recordEntered != null) {
                recordEntered.countDown();
                try {
                    releaseRecord.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("history recording interrupted", interrupted);
                }
            }
            this.work = work;
            this.folder = folder;
            this.files = List.copyOf(files);
            this.sourceUrl = sourceUrl;
            this.collectionId = collectionId;
            addRelations(sourceRelations);
            return true;
        }

        @Override
        public Optional<DouyinWorkRecord> findById(String workId) {
            return Optional.ofNullable(existingRecord);
        }

        @Override
        public List<DouyinWorkFileRecord> findFilesByWorkId(String workId) {
            return existingFiles;
        }

        @Override
        public boolean recordRelation(DouyinSourceRelation relation) {
            addRelations(List.of(relation));
            return true;
        }

        @Override
        public boolean recordRelations(String workId, List<DouyinSourceRelation> sourceRelations) {
            addRelations(sourceRelations);
            return true;
        }

        void addRelations(List<DouyinSourceRelation> sourceRelations) {
            for (DouyinSourceRelation relation : sourceRelations) {
                relations.removeIf(existing -> existing.workId().equals(relation.workId())
                        && existing.sourceType().equals(relation.sourceType())
                        && existing.sourceId().equals(relation.sourceId()));
                relations.add(relation);
            }
        }
    }
}
