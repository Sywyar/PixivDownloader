package top.sywyar.pixivdownload.core.importing;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.core.asset.ExternalWorkFiles;
import top.sywyar.pixivdownload.core.db.PixivDatabase;
import top.sywyar.pixivdownload.core.work.importing.LocalWorkFileImportRequest;
import top.sywyar.pixivdownload.core.work.importing.WorkFileImporter;
import top.sywyar.pixivdownload.core.work.model.WorkType;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

/** GUI 与 HTTP 共用单个有界工作者；取消后在当前作品事务退出时停止。 */
@Slf4j
@Service
public final class LocalImportService implements LocalArtworkImports, AutoCloseable {
    private static final Set<String> EXTENSIONS = Set.of("jpg", "jpeg", "png", "apng", "gif", "webp", "webm", "mp4", "zip");
    private final LocalImportConfig config;
    private final WorkFileImporter importer;
    private final PixivDatabase database;
    private final ExternalWorkFiles external;
    private final ExecutorService worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), Thread.ofVirtual().name("local-artwork-import").factory());
    private Task task;
    private Index index;
    private boolean closed;

    public LocalImportService(LocalImportConfig config, WorkFileImporter importer, PixivDatabase database, ExternalWorkFiles external) {
        this.config = config; this.importer = importer; this.database = database; this.external = external;
    }

    @Override public List<Preset> presets() { return config.entries(); }

    @Override public synchronized Snapshot scan(Request request) {
        requireOpen();
        if (task != null && busy(task.phase)) throw new IllegalStateException("IMPORT_BUSY");
        List<String> templates = new ArrayList<>(request.templates());
        if (templates.isEmpty()) {
            var presets = presets();
            Set<String> selected = Set.copyOf(request.presetNames());
            if (!presets.stream().filter(Preset::enabled).map(Preset::name).collect(java.util.stream.Collectors.toSet()).containsAll(selected))
                throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
            presets.stream().filter(Preset::enabled).filter(p -> selected.isEmpty() || selected.contains(p.name()))
                    .map(Preset::template).forEach(templates::add);
        }
        if (templates.isEmpty()) throw new IllegalArgumentException("IMPORT_NO_TEMPLATES");
        List<LocalImportTemplate> parsed = templates.stream().distinct().map(LocalImportTemplate::new).toList();
        Task next = new Task(request, parsed);
        task = next;
        log.info("Local import scan accepted: execution={}, templates={}, requestedIds={}", next.execution, parsed.size(), request.artworkIds().size());
        worker.execute(() -> scanFiles(next));
        return snapshot(next);
    }

    @Override public synchronized Snapshot status(UUID id) { return snapshot(requireTask(id)); }

    @Override public synchronized List<Item> items(UUID id, int offset, int limit) {
        Task current = requireTask(id);
        if (offset < 0 || limit < 1 || limit > 100) throw new IllegalArgumentException("INVALID_IMPORT_PAGE");
        int start = Math.min(offset, current.items.size());
        return List.copyOf(current.items.subList(start, (int) Math.min((long) start + limit, current.items.size())));
    }

    @Override public synchronized Snapshot start(UUID id) {
        requireOpen();
        Task current = requireTask(id);
        if (current.phase == Phase.IMPORTING || current.phase == Phase.CANCELLING || current.phase == Phase.COMPLETED)
            return snapshot(current);
        if (current.phase != Phase.READY) throw new IllegalStateException("IMPORT_PREVIEW_NOT_READY");
        current.phase = Phase.IMPORTING;
        worker.execute(() -> importFiles(current));
        return snapshot(current);
    }

    @Override public synchronized Snapshot cancel(UUID id) {
        Task current = requireTask(id);
        if (busy(current.phase)) { current.cancelled = true; current.phase = Phase.CANCELLING; }
        else if (current.phase == Phase.READY) { current.cancelled = true; current.phase = Phase.CANCELLED; current.requests = Map.of(); }
        return snapshot(current);
    }

    private void scanFiles(Task current) {
        try {
            log.info("Local import scan started: execution={}", current.execution);
            Set<Long> ids = Set.copyOf(current.request.artworkIds());
            List<Path> roots = current.templates.stream().flatMap(t -> t.searchRoots(ids).stream()).distinct().sorted().toList();
            IndexKey key = new IndexKey(current.templates.stream().map(LocalImportTemplate::template).toList(), roots, current.request.selectedFile());
            Index existing = index;
            if (current.request.refreshIndex() || existing == null || !existing.key.equals(key)
                    || System.nanoTime() - existing.created > TimeUnit.MINUTES.toNanos(5)) {
                index = null;
                var found = new LinkedHashMap<Long, List<Found>>();
                var paths = new HashMap<Path, Long>();
                long[] budget = {0, 0};
                for (var template : current.templates) {
                    checkCancelled(current);
                    if (!PlainFilePathGuard.isPlainDirectory(template.root())) throw new IOException("INVALID_SOURCE_ROOT");
                    if (!current.request.selectedFile().isBlank()) {
                        Path selected = Path.of(current.request.selectedFile());
                        if (!selected.isAbsolute()) throw new IllegalArgumentException("INVALID_SOURCE_PATH");
                        PlainFilePathGuard.requirePlainRegularFile(selected);
                        match(current, template, selected, found, paths, budget);
                    } else {
                        for (Path root : template.searchRoots(ids)) {
                            if (Files.notExists(root, LinkOption.NOFOLLOW_LINKS)) continue;
                            Files.walkFileTree(root, Set.of(), 64, new SimpleFileVisitor<>() {
                                @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                    checkCancelled(current);
                                    if (++budget[0] > 250000) throw new IOException("IMPORT_SCAN_LIMIT");
                                    if (!template.acceptsDirectory(dir)) return FileVisitResult.SKIP_SUBTREE;
                                    if (!PlainFilePathGuard.isPlainDirectory(dir)) throw new IOException("INVALID_SOURCE_PATH");
                                    return FileVisitResult.CONTINUE;
                                }
                                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    checkCancelled(current);
                                    if (++budget[0] > 250000 || attrs.isDirectory()) throw new IOException("IMPORT_SCAN_LIMIT");
                                    if (attrs.isRegularFile()) match(current, template, file, found, paths, budget);
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                        }
                    }
                }
                var frozen = new LinkedHashMap<Long, List<Found>>();
                found.forEach((id, files) -> frozen.put(id, List.copyOf(files)));
                existing = new Index(key, Map.copyOf(frozen), System.nanoTime());
                index = existing;
            } else current.cached = true;
            List<Item> items = new ArrayList<>();
            Map<Long, LocalWorkFileImportRequest> requests = new LinkedHashMap<>();
            Set<Long> wanted = ids.isEmpty() ? existing.works.keySet() : ids;
            for (long id : new TreeSet<>(wanted)) {
                checkCancelled(current);
                List<Found> files = existing.works.getOrDefault(id, List.of());
                Item item = inspect(id, files);
                items.add(item);
                if (item.disposition() == Disposition.NEW || item.disposition() == Disposition.SUPPLEMENT) {
                    requests.put(id, new LocalWorkFileImportRequest(WorkType.ARTWORK, id, files.getFirst().root,
                            files.stream().map(Found::file).toList(), files.stream()
                            .map(f -> new LocalWorkFileImportRequest.Source(f.root, f.match.page(), f.match.hints())).toList()));
                }
            }
            synchronized (this) {
                checkCancelled(current);
                current.items = List.copyOf(items); current.requests = Map.copyOf(requests);
                current.fileCount = items.stream().mapToInt(i -> i.files().size()).sum(); current.phase = Phase.READY;
            }
            log.info("Local import preview ready: execution={}, artworks={}, files={}, cached={}",
                    current.execution, items.size(), current.fileCount, current.cached);
        } catch (Exception failure) { finishFailure(current, failure); }
    }

    private void match(Task current, LocalImportTemplate template, Path path, Map<Long, List<Found>> found,
                       Map<Path, Long> paths, long[] budget) throws IOException {
        checkCancelled(current);
        String name = path.getFileName().toString();
        if (!EXTENSIONS.contains(name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT))) return;
        var match = template.match(path);
        if (match == null) return;
        PlainFilePathGuard.requirePlainRegularFile(path);
        Path file = path.toAbsolutePath().normalize();
        Long previous = paths.putIfAbsent(file, match.artworkId());
        if (previous != null && previous != match.artworkId()) throw new IllegalArgumentException("IMPORT_ID_CONFLICT");
        var files = found.computeIfAbsent(match.artworkId(), ignored -> new ArrayList<>());
        if (previous != null) {
            var other = files.stream().filter(f -> f.file.equals(file)).findFirst().orElseThrow();
            if (!Objects.equals(other.match.page(), match.page()) || !other.match.hints().equals(match.hints()))
                throw new IllegalArgumentException("AMBIGUOUS_IMPORT_MATCH");
            return;
        }
        budget[1] += file.toString().length() + match.hints().values().stream().mapToInt(String::length).sum();
        if (paths.size() > 100000 || found.size() > 10000 || files.size() >= 1000 || budget[1] > 16 * 1024 * 1024)
            throw new IOException("IMPORT_SCAN_LIMIT");
        files.add(new Found(template.root(), file, match));
    }

    private Item inspect(long id, List<Found> files) {
        var projected = files.stream().map(f -> new File(f.file.toString(), f.match.page(), f.match.hints())).toList();
        if (files.isEmpty()) return new Item(id, projected, Disposition.MISSING, "IMPORT_NOT_FOUND");
        var record = database.getArtwork(id);
        var old = external.references(WorkType.ARTWORK, id);
        if (record != null && (record.deleted() || old.isEmpty())) return new Item(id, projected, Disposition.CONFLICT, "IMPORT_EXISTING_MANAGED_WORK");
        Map<Integer, Path> pages = new HashMap<>();
        Map<Path, Integer> paths = new HashMap<>();
        for (var ref : old) {
            paths.put(ref.file(), ref.sourcePage());
            if (ref.sourcePage() != null) pages.put(ref.sourcePage(), ref.file());
        }
        boolean added = false;
        for (Found file : files) {
            Integer page = file.match.page();
            if (page != null && (pages.containsKey(page) && !pages.get(page).equals(file.file)
                    || paths.get(file.file) != null && !page.equals(paths.get(file.file))))
                return new Item(id, projected, Disposition.CONFLICT, "IMPORT_PAGE_CONFLICT");
            added |= !paths.containsKey(file.file);
            paths.put(file.file, page);
            if (page != null) pages.put(page, file.file);
        }
        if (paths.size() > 1000) return new Item(id, projected, Disposition.CONFLICT, "IMPORT_FILE_LIMIT");
        return new Item(id, projected, record == null ? Disposition.NEW : added ? Disposition.SUPPLEMENT : Disposition.DUPLICATE, "");
    }

    private void importFiles(Task current) {
        log.info("Local import started: execution={}, artworks={}", current.execution, current.items.size());
        try {
            for (Item item : current.items) {
                checkCancelled(current);
                try {
                    var request = current.requests.get(item.artworkId());
                    if (request == null) {
                        synchronized (this) {
                            if (item.disposition() == Disposition.DUPLICATE) current.skipped++;
                            else { current.failed++; problem(current, item.artworkId(), item.reason()); }
                        }
                    } else {
                        boolean written = importer.importLocalFiles(request);
                        synchronized (this) { if (written) current.imported++; else current.skipped++; }
                    }
                } catch (IOException | RuntimeException failure) {
                    synchronized (this) { current.failed++; problem(current, item.artworkId(), reason(failure)); }
                }
                synchronized (this) { current.processed++; }
            }
            synchronized (this) { current.phase = current.cancelled ? Phase.CANCELLED : Phase.COMPLETED; }
        } catch (CancellationException cancelled) {
            synchronized (this) { current.phase = Phase.CANCELLED; }
        } finally {
            synchronized (this) { current.requests = Map.of(); }
            log.info("Local import finished: execution={}, phase={}, processed={}, imported={}, skipped={}, failed={}, elapsedMs={}",
                    current.execution, current.phase, current.processed, current.imported, current.skipped, current.failed,
                    TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - current.started));
        }
    }

    private synchronized void finishFailure(Task current, Exception failure) {
        current.requests = Map.of(); index = null;
        current.phase = current.cancelled || failure instanceof CancellationException ? Phase.CANCELLED : Phase.FAILED;
        if (current.phase == Phase.FAILED) problem(current, null, reason(failure));
        log.atLevel(current.phase == Phase.CANCELLED ? org.slf4j.event.Level.INFO : org.slf4j.event.Level.WARN)
                .log("Local import scan ended: execution={}, phase={}, reason={}", current.execution, current.phase,
                current.phase == Phase.CANCELLED ? "CANCELLED" : reason(failure));
    }
    private static String reason(Exception failure) {
        String code = failure.getMessage();
        return code != null && code.matches("(?:IMPORT_|INVALID_|UNSUPPORTED_|SOURCE_|AMBIGUOUS_|UNKNOWN_)[A-Z_]{1,64}")
                ? code : "IMPORT_IO_FAILED";
    }
    private static void problem(Task current, Long id, String code) {
        if (current.problems.size() < 100) current.problems.add(new Problem(id, code));
    }
    private static void checkCancelled(Task current) {
        if (current.cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
    }
    private static boolean busy(Phase phase) { return phase == Phase.SCANNING || phase == Phase.IMPORTING || phase == Phase.CANCELLING; }
    private void requireOpen() { if (closed) throw new IllegalStateException("IMPORT_UNAVAILABLE"); }
    private Task requireTask(UUID id) {
        if (task == null || !task.id.equals(id)) throw new IllegalArgumentException("IMPORT_TASK_EXPIRED");
        return task;
    }
    private static Snapshot snapshot(Task t) {
        return new Snapshot(t.id, t.phase, t.items.size(), t.fileCount, t.processed, t.imported, t.skipped, t.failed, t.cached, t.problems);
    }
    @PreDestroy @Override public void close() {
        synchronized (this) { closed = true; if (task != null) task.cancelled = true; }
        worker.close();
        synchronized (this) { task = null; index = null; }
    }
    private record Found(Path root, Path file, LocalImportTemplate.Match match) {}
    private record IndexKey(List<String> templates, List<Path> roots, String selectedFile) {}
    private record Index(IndexKey key, Map<Long, List<Found>> works, long created) {}
    private static final class Task {
        final UUID id = UUID.randomUUID();
        final UUID execution = UUID.randomUUID();
        final long started = System.nanoTime();
        final Request request;
        final List<LocalImportTemplate> templates;
        final List<Problem> problems = new ArrayList<>();
        volatile boolean cancelled;
        Phase phase = Phase.SCANNING;
        boolean cached;
        List<Item> items = List.of();
        Map<Long, LocalWorkFileImportRequest> requests = Map.of();
        int fileCount, processed, imported, skipped, failed;
        Task(Request request, List<LocalImportTemplate> templates) { this.request = request; this.templates = templates; }
    }
}
