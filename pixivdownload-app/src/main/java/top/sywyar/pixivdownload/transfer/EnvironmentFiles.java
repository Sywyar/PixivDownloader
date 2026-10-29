package top.sywyar.pixivdownload.transfer;

import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.BuiltInPlugins;
import top.sywyar.pixivdownload.plugin.PluginToggleProperties;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.FieldKey;
import top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution;
import top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution.*;
import top.sywyar.pixivdownload.plugin.registry.PluginRegistry;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.sdk.SdkVersion;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.regex.Pattern;

/** 归档只选择可信声明，目标由当前 owner 的运行目录重新解析。 */
final class EnvironmentFiles {
    record Binding(String owner, String version, Definition definition) {}
    record Entry(int index, Binding binding, String relative, String before) {
        FieldKey group() { return new FieldKey(binding.owner(), binding.definition().id()); }
        String targetId() { return "files/" + binding.owner() + "/" + binding.definition().id() + "/" + relative; }
    }
    record Plan(List<Entry> entries, Set<FieldKey> unavailable) {
        Plan { entries = List.copyOf(entries); unavailable = Set.copyOf(unavailable); }
    }
    private record Owned(Binding binding, EnvironmentFileContribution contribution, Pattern pattern) {}
    private final Map<FieldKey, Owned> catalog = new LinkedHashMap<>();

    EnvironmentFiles(PluginBootstrapSession session) throws IOException {
        for (var contribution : CoreEnvironmentFiles.contributions()) add("host", SdkVersion.VERSION, contribution);
        if (session == null) return;
        var inventory = session.manager().inspectPlugins();
        var discovery = session.manager().toDiscoveryResult(inventory);
        if (!discovery.failures().isEmpty()) throw new IOException("transfer.files-unavailable");
        var registry = new PluginRegistry(BuiltInPlugins.createAll(), new PluginToggleProperties(), discovery);
        for (var plugin : registry.allRegisteredPlugins()) {
            var declarations = new ArrayList<EnvironmentFileContribution>();
            registry.runBootContribution(plugin, () -> declarations.addAll(plugin.plugin().environmentFiles()));
            if (registry.lifecycleFailure(plugin).isPresent()) throw new IOException("transfer.files-unavailable");
            if (declarations.size() > 128) throw new IOException("transfer.file-limit");
            String version = inventory.installations().stream().filter(value -> value.id().equals(plugin.id()))
                    .map(value -> value.descriptor().version()).findFirst().orElse(SdkVersion.VERSION);
            for (var declaration : declarations) add(plugin.id(), version, declaration);
        }
    }

    private void add(String owner, String version, EnvironmentFileContribution contribution) throws IOException {
        try {
            var definition = Objects.requireNonNull(contribution.definition());
            if (!owner.matches("[a-z][a-z0-9-]*") || !definition.id().matches("[a-z][a-z0-9-]{0,63}")
                    || definition.root() == null || definition.kind() == null || definition.schemaVersion() < 1
                    || definition.maxFileBytes() < 1 || definition.maxFileBytes() > TransferArchive.MAX_CONTENT_BYTES
                    || definition.depth() < 1 || definition.depth() > 16
                    || definition.pattern().length() > 1024) throw new IllegalArgumentException();
            if (!definition.directory().isEmpty()) TransferArchive.requireLogicalPath(definition.directory());
            var owned = new Owned(new Binding(owner, version, definition), contribution, Pattern.compile(definition.pattern()));
            if (catalog.putIfAbsent(new FieldKey(owner, definition.id()), owned) != null) throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IOException("transfer.file-declaration-invalid", invalid); }
    }

    List<TransferArchive.Source> export(Path directory, boolean credentials, boolean databases, boolean states, boolean resources) throws IOException {
        Files.createDirectory(directory);
        MigrationFiles.makePrivate(directory);
        var result = new ArrayList<TransferArchive.Source>();
        Budget budget = new Budget(TransferArchive.MAX_CONTENT_BYTES);
        int[] scanned = {0};
        for (var owned : catalog.values()) {
            var definition = owned.binding().definition();
            if (!(switch (definition.kind()) { case STATE -> states; case RESOURCE -> resources; case CREDENTIALS -> credentials; })) continue;
            if (definition.requiresDatabase() && !databases) continue;
            Path root = root(owned.binding());
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            PlainFilePathGuard.requirePlainParent(root.resolve("unused"), false);
            Files.walkFileTree(root, EnumSet.noneOf(FileVisitOption.class), definition.depth(), new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                    if (++scanned[0] > 20_000) throw new IOException("transfer.file-limit");
                    MigrationFiles.requirePlain(path, true);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                    if (++scanned[0] > 20_000) throw new IOException("transfer.file-limit");
                    String relative = root.relativize(path).toString().replace('\\', '/');
                    if (!owned.pattern().matcher(relative).matches()) return FileVisitResult.CONTINUE;
                    TransferArchive.requireLogicalPath(relative);
                    if (result.size() >= 20_000) throw new IOException("transfer.file-limit");
                    Path staged = directory.resolve(Integer.toString(result.size()));
                    copy(owned, relative, path, null, staged, credentials, budget);
                    var descriptor = new TransferArchive.Descriptor(owned.binding().owner(), category(definition.kind()),
                            definition.id() + "/" + relative, definition.schemaVersion());
                    result.add(new TransferArchive.Source(descriptor, () -> Files.newInputStream(staged, LinkOption.NOFOLLOW_LINKS)));
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return List.copyOf(result);
    }

    Plan preview(TransferArchive.Preview archive) throws IOException {
        var entries = new ArrayList<Entry>();
        var unavailable = new LinkedHashSet<FieldKey>();
        Set<Path> targets = new HashSet<>();
        for (int index = 0; index < archive.items().size(); index++) {
            var item = archive.items().get(index);
            var descriptor = item.descriptor();
            if (!Set.of(TransferArchive.Category.STATE, TransferArchive.Category.RESOURCE, TransferArchive.Category.CREDENTIALS).contains(descriptor.category())
                    || descriptor.path().equals("settings.json")) continue;
            int separator = descriptor.path().indexOf('/');
            FieldKey group = new FieldKey(descriptor.owner(), separator < 0 ? descriptor.path() : descriptor.path().substring(0, separator));
            Owned owned = catalog.get(group);
            String relative = separator < 0 ? "" : descriptor.path().substring(separator + 1);
            if (owned == null || descriptor.schemaVersion() != owned.binding().definition().schemaVersion()
                    || descriptor.category() != category(owned.binding().definition().kind()) || !owned.pattern().matcher(relative).matches()) {
                unavailable.add(group); continue;
            }
            if (item.size() > owned.binding().definition().maxFileBytes()) throw new IOException("transfer.file-limit");
            if (descriptor.category() != TransferArchive.Category.RESOURCE && !archive.encrypted()) throw new IOException("transfer.password-required");
            Path target = target(owned.binding(), relative);
            if (!targets.add(target)) throw new IOException("transfer.file-declaration-invalid");
            entries.add(new Entry(index, owned.binding(), relative, digest(target)));
        }
        return new Plan(entries, unavailable);
    }

    void render(Plan expected, Set<FieldKey> groups, boolean databases, Path extracted, Path staging,
                Map<String, Path> targets, List<FileRestoreTransaction.Replacement> replacements) throws IOException {
        if (!expected.entries().stream().map(Entry::group).collect(java.util.stream.Collectors.toSet()).containsAll(groups))
            throw new IOException("transfer.config-selection-invalid");
        Budget budget = new Budget(TransferArchive.MAX_CONTENT_BYTES);
        for (var entry : expected.entries()) {
            if (!groups.contains(entry.group())) continue;
            Owned owned = require(entry);
            if (entry.binding().definition().requiresDatabase() && !databases) throw new IOException("transfer.files-require-database");
            Path target = target(entry.binding(), entry.relative());
            if (!digest(target).equals(entry.before())) throw new IOException("transfer.target-changed");
            Path staged = staging.resolve("file-" + entry.index());
            copy(owned, entry.relative(), extracted.resolve(Integer.toString(entry.index())), target, staged, true, budget);
            targets.put(entry.targetId(), target);
            replacements.add(new FileRestoreTransaction.Replacement(entry.targetId(), staged, entry.before()));
        }
    }

    Map<String, Path> resolveTargets(Plan plan) throws IOException {
        Map<String, Path> result = new LinkedHashMap<>();
        for (var entry : plan.entries()) {
            require(entry);
            if (result.putIfAbsent(entry.targetId(), target(entry.binding(), entry.relative())) != null)
                throw new IOException("transfer.restore-invalid");
        }
        return result;
    }
    private Owned require(Entry entry) throws IOException {
        Owned owned = catalog.get(entry.group());
        if (owned == null || !owned.binding().equals(entry.binding()) || !owned.pattern().matcher(entry.relative()).matches())
            throw new IOException("transfer.preview-changed");
        return owned;
    }
    private static Path root(Binding binding) {
        Path root = binding.definition().root() == Root.STATE ? RuntimeFiles.stateDirectory() : RuntimeFiles.dataDirectory();
        if (!binding.owner().equals("host")) root = root.resolve(binding.owner());
        return root.resolve(binding.definition().directory()).toAbsolutePath().normalize();
    }
    private static Path target(Binding binding, String relative) throws IOException {
        TransferArchive.requireLogicalPath(relative);
        Path root = root(binding);
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) throw new IOException("transfer.restore-invalid");
        return target;
    }
    private static TransferArchive.Category category(Kind kind) { return TransferArchive.Category.valueOf(kind.name()); }
    private static String digest(Path path) throws IOException {
        return FileRestoreTransaction.stateDigest(path);
    }
    private static void copy(Owned owned, String relative, Path source, Path existing, Path destination,
                             boolean credentials, Budget budget) throws IOException {
        PlainFilePathGuard.requirePlainRegularFile(source);
        long maximum = owned.binding().definition().maxFileBytes();
        if (Files.size(source) > maximum) throw new IOException("transfer.file-limit");
        boolean present = existing != null && Files.exists(existing, LinkOption.NOFOLLOW_LINKS);
        if (present) {
            PlainFilePathGuard.requirePlainRegularFile(existing);
            if (Files.size(existing) > maximum) throw new IOException("transfer.file-limit");
        }
        MigrationFiles.createPrivateFile(destination);
        try (var input = new BoundedInput(Files.newInputStream(source, LinkOption.NOFOLLOW_LINKS), maximum);
             var current = present ? new BoundedInput(Files.newInputStream(existing, LinkOption.NOFOLLOW_LINKS), maximum) : null;
             var output = budget.wrap(Files.newOutputStream(destination, LinkOption.NOFOLLOW_LINKS), maximum)) {
            owned.contribution().copy(relative, input, current, output, credentials);
        }
    }
    private static final class BoundedInput extends FilterInputStream {
        private long remaining;
        BoundedInput(InputStream input, long maximum) { super(input); remaining = maximum; }
        @Override public int read() throws IOException {
            int value = in.read(); if (value >= 0 && --remaining < 0) throw new IOException("transfer.file-limit"); return value;
        }
        @Override public int read(byte[] bytes, int offset, int count) throws IOException {
            int read = in.read(bytes, offset, (int) Math.min(count, remaining + 1));
            if (read > 0 && (remaining -= read) < 0) throw new IOException("transfer.file-limit"); return read;
        }
    }
    private static final class Budget {
        private long remaining;
        Budget(long maximum) { remaining = maximum; }
        OutputStream wrap(OutputStream output, long maximum) {
            return new FilterOutputStream(output) {
                long fileRemaining = maximum;
                @Override public void write(int value) throws IOException { charge(1); out.write(value); }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException { charge(length); out.write(bytes, offset, length); }
                private void charge(int count) throws IOException {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("transfer.cancelled");
                    if (count > remaining || count > fileRemaining) throw new IOException("transfer.file-limit");
                    remaining -= count; fileRemaining -= count;
                }
            };
        }
    }
}
