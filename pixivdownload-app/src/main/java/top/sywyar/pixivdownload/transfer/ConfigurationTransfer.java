package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.common.PlainFilePathGuard;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.config.credential.PluginCredentialStore;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.config.PropertiesConfigFileEditor;
import top.sywyar.pixivdownload.plugin.api.database.migration.MigrationFiles;
import top.sywyar.pixivdownload.plugin.api.gui.*;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/** 配置按已安装 owner 的声明交换，保留目标文件中未选字段和注释。 */
final class ConfigurationTransfer {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private final DesktopUiHost host;
    private final Supplier<List<DesktopUiPluginSnapshot>> plugins;
    private final PluginCredentialStore credentials;
    private final top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy requiredPlugins;

    record Key(String owner, String name) {}
    record Change(Key key, boolean sensitive, boolean path, String before, String after) {}
    record Preview(List<Change> changes, Set<Key> unavailable, Map<String, String> baselines, Map<String, Owner> definitions) {
        Preview { changes = List.copyOf(changes); unavailable = Set.copyOf(unavailable); baselines = Map.copyOf(baselines); definitions = Map.copyOf(definitions); }
    }
    record Rendered(Map<String, Path> targets, List<FileRestoreTransaction.Replacement> replacements) {}
    private record Owner(String id, Map<String, GuiConfigFieldContribution> fields, Object identity) {
        boolean hostOwned() { return id.equals("host"); }
        Path path(boolean secret) {
            return hostOwned() ? RuntimeFiles.peekConfigYamlPath()
                    : secret ? RuntimeFiles.peekPluginCredentialPath(id) : RuntimeFiles.peekPluginConfigPath(id);
        }
        String target(boolean secret) { return id + (secret && !hostOwned() ? "/credentials" : "/config"); }
    }

    ConfigurationTransfer(DesktopUiHost host, Supplier<List<DesktopUiPluginSnapshot>> plugins, PluginCredentialStore credentials,
                          top.sywyar.pixivdownload.plugin.runtime.status.RequiredPluginPolicy requiredPlugins) {
        this.host = host; this.plugins = plugins; this.credentials = credentials;
        this.requiredPlugins = requiredPlugins;
    }

    /** 快照来自磁盘中的已保存值，不读取界面的临时草稿。 */
    ExportSnapshot export(boolean includeCredentials) throws IOException {
        var snapshot = new ExportSnapshot();
        try { exportInto(snapshot, includeCredentials); return snapshot; }
        catch (IOException | RuntimeException failed) { snapshot.close(); throw failed; }
    }

    static final class ExportSnapshot implements AutoCloseable {
        private final List<byte[]> buffers = new ArrayList<>();
        private final List<TransferArchive.Source> sources = new ArrayList<>();
        List<TransferArchive.Source> sources() { return List.copyOf(sources); }
        @Override public void close() { buffers.forEach(bytes -> Arrays.fill(bytes, (byte) 0)); buffers.clear(); sources.clear(); }
    }

    private void exportInto(ExportSnapshot snapshot, boolean includeCredentials) throws IOException {
        long remaining = MAX_BYTES;
        for (Owner owner : catalog().values()) {
            for (boolean secret : new boolean[] {false, true}) {
                if (secret && !includeCredentials) continue;
                Set<String> keys = new LinkedHashSet<>();
                owner.fields().forEach((key, field) -> { if (field.sensitive() == secret) keys.add(key); });
                if (keys.isEmpty()) continue;
                Map<String, String> values = read(owner, keys, secret);
                if (secret) values.values().removeIf(String::isBlank);
                if (values.isEmpty()) continue;
                byte[] bytes = JSON.writeValueAsBytes(values);
                snapshot.buffers.add(bytes);
                if (bytes.length > remaining) throw new IOException("transfer.archive-limit");
                remaining -= bytes.length;
                var descriptor = new TransferArchive.Descriptor(owner.id(), secret
                        ? TransferArchive.Category.CREDENTIALS : TransferArchive.Category.CONFIG, "settings.json", 1);
                snapshot.sources.add(new TransferArchive.Source(descriptor, () -> new ByteArrayInputStream(bytes)));
            }
        }
    }

    Preview preview(TransferArchive.Preview archive, TransferArchive.SelectedContents contents) throws IOException {
        Map<String, Owner> owners = catalog();
        List<Change> changes = new ArrayList<>();
        Set<Key> unavailable = new LinkedHashSet<>();
        Map<String, String> baselines = new LinkedHashMap<>();
        for (int index : indices(archive)) {
            var descriptor = archive.items().get(index).descriptor();
            Map<String, String> values = parse(contents.get(index));
            Owner owner = owners.get(descriptor.owner());
            boolean secret = descriptor.category() == TransferArchive.Category.CREDENTIALS;
            if (owner == null) {
                values.keySet().forEach(key -> unavailable.add(new Key(descriptor.owner(), key)));
                continue;
            }
            Map<String, String> existing = read(owner, owner.fields().keySet(), secret);
            baselines.put(owner.target(secret), digest(owner.path(secret)));
            for (var entry : values.entrySet()) {
                Key key = new Key(owner.id(), entry.getKey());
                var field = owner.fields().get(key.name());
                if (field == null) { unavailable.add(key); continue; }
                if (field.sensitive() != secret) throw new IOException("transfer.config-category-mismatch");
                if (Objects.equals(existing.get(key.name()), entry.getValue())) continue;
                changes.add(new Change(key, secret, isPath(field), secret ? "" : existing.getOrDefault(key.name(), ""),
                        secret ? "" : entry.getValue()));
            }
        }
        return new Preview(changes, unavailable, baselines, owners);
    }

    /** 确认后先在私有暂存目录编辑副本，回读成功后才交给停机恢复事务。 */
    Rendered render(TransferArchive.Preview archive, TransferArchive.SelectedContents contents, Preview expected,
                    Set<Key> selected, Map<Key, String> mappedPaths, Path staging) throws IOException {
        Preview current = preview(archive, contents);
        if (!current.equals(expected)) throw new IOException("transfer.target-changed");
        Map<Key, Change> allowed = new HashMap<>();
        current.changes().forEach(change -> allowed.put(change.key(), change));
        if (!allowed.keySet().containsAll(selected) || !selected.containsAll(mappedPaths.keySet()))
            throw new IOException("transfer.config-selection-invalid");
        Map<String, Owner> owners = catalog();
        Map<String, Path> targets = new LinkedHashMap<>();
        Map<String, Path> rendered = new LinkedHashMap<>();
        Map<String, Map<String, String>> selectedValues = new LinkedHashMap<>();
        for (int index : indices(archive)) {
            String owner = archive.items().get(index).descriptor().owner();
            Map<String, String> values = parse(contents.get(index));
            values.keySet().removeIf(key -> !selected.contains(new Key(owner, key)));
            for (var mapping : mappedPaths.entrySet()) if (mapping.getKey().owner().equals(owner)
                    && values.containsKey(mapping.getKey().name())) {
                if (!allowed.get(mapping.getKey()).path()) throw new IOException("transfer.config-selection-invalid");
                values.put(mapping.getKey().name(), ConfigFileEditor.requireSafeValue(mapping.getValue()));
            }
            selectedValues.computeIfAbsent(owner, ignored -> new LinkedHashMap<>()).putAll(values);
        }
        for (var entry : selectedValues.entrySet()) if (!entry.getValue().isEmpty()) validate(owners.get(entry.getKey()), entry.getValue());
        for (int index : indices(archive)) {
            var descriptor = archive.items().get(index).descriptor();
            Owner owner = owners.get(descriptor.owner());
            if (owner == null) continue;
            boolean secret = descriptor.category() == TransferArchive.Category.CREDENTIALS;
            Map<String, String> updates = parse(contents.get(index));
            updates.keySet().removeIf(key -> !selected.contains(new Key(owner.id(), key)));
            for (var mapping : mappedPaths.entrySet()) if (mapping.getKey().owner().equals(owner.id())
                    && updates.containsKey(mapping.getKey().name())) {
                if (!allowed.get(mapping.getKey()).path()) throw new IOException("transfer.config-selection-invalid");
                updates.put(mapping.getKey().name(), ConfigFileEditor.requireSafeValue(mapping.getValue()));
            }
            if (updates.isEmpty()) continue;
            String target = owner.target(secret);
            Path destination = owner.path(secret);
            Path shadow = rendered.get(target);
            if (shadow == null) {
                shadow = staging.resolve(Integer.toString(rendered.size()));
                PlainFilePathGuard.requirePlainParent(shadow, false);
                Files.createFile(shadow);
                MigrationFiles.makePrivate(shadow);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                    requireBounded(destination);
                    try (var input = Files.newInputStream(destination, LinkOption.NOFOLLOW_LINKS);
                         var output = Files.newOutputStream(shadow, LinkOption.NOFOLLOW_LINKS)) { input.transferTo(output); }
                }
                rendered.put(target, shadow);
                targets.put(target, destination);
            }
            if (secret && !owner.hostOwned()) {
                byte[] envelope = credentials.prepareTransfer(owner.id(), updates);
                try { Files.write(shadow, envelope, LinkOption.NOFOLLOW_LINKS); }
                finally { Arrays.fill(envelope, (byte) 0); }
            } else if (owner.hostOwned()) {
                var editor = new ConfigFileEditor(shadow);
                editor.writeAll(updates);
                if (!editor.readAll(updates.keySet()).equals(updates)) throw new IOException("transfer.restore-verification-failed");
            } else {
                var editor = new PropertiesConfigFileEditor(shadow);
                editor.writeAll(updates);
                if (!editor.readAll(updates.keySet()).equals(updates)) throw new IOException("transfer.restore-verification-failed");
            }
            MigrationFiles.makePrivate(shadow);
        }
        for (var target : targets.entrySet()) {
            if (!digest(target.getValue()).equals(expected.baselines().get(target.getKey()))) throw new IOException("transfer.target-changed");
        }
        return new Rendered(Map.copyOf(targets), rendered.entrySet().stream()
                .map(entry -> new FileRestoreTransaction.Replacement(entry.getKey(), entry.getValue(),
                        expected.baselines().get(entry.getKey()))).toList());
    }

    static Set<Integer> indices(TransferArchive.Preview archive) throws IOException {
        Set<Integer> indices = new LinkedHashSet<>();
        for (int i = 0; i < archive.items().size(); i++) {
            var descriptor = archive.items().get(i).descriptor();
            if (descriptor.category() != TransferArchive.Category.CONFIG && descriptor.category() != TransferArchive.Category.CREDENTIALS) continue;
            if (descriptor.category() == TransferArchive.Category.CREDENTIALS && !descriptor.path().equals("settings.json")) continue;
            if (!descriptor.path().equals("settings.json") || descriptor.schemaVersion() != 1)
                throw new IOException("transfer.config-schema-unsupported");
            indices.add(i);
        }
        return indices;
    }

    private Map<String, Owner> catalog() throws IOException {
        Map<String, Owner> owners = new LinkedHashMap<>();
        Set<String> connectionKeys = new HashSet<>();
        connectionKeys.add("database.provider");
        host.databaseSettings().ifPresent(settings -> settings.providers().forEach(provider ->
                provider.connectionFields().forEach(field -> connectionKeys.add(field.key()))));
        Map<String, GuiConfigFieldContribution> core = fields(host.coreConfigFields());
        connectionKeys.forEach(core::remove);
        for (String key : List.of("app.language", "app.theme", "app.gui-provider", "app.config-menu-expand-all")) {
            core.put(key, new GuiConfigFieldContribution(key, "interface", "transfer.title",
                    key.equals("app.config-menu-expand-all") ? GuiConfigFieldType.BOOL : GuiConfigFieldType.STRING, "", 0));
        }
        Set<String> databaseOwners = host.databaseSettings().map(settings -> settings.providers().stream()
                .map(DesktopDatabaseSettings.ProviderChoice::owner).collect(java.util.stream.Collectors.toSet())).orElse(Set.of());
        List<DesktopUiPluginSnapshot> snapshots = plugins.get();
        for (var plugin : snapshots) if (!plugin.builtIn() && !databaseOwners.contains(plugin.id())) {
            String key = "plugins." + plugin.id() + ".enabled";
            core.put(key, new GuiConfigFieldContribution(key, "plugins", "transfer.title", GuiConfigFieldType.BOOL, "true", 0));
        }
        owners.put("host", new Owner("host", Map.copyOf(core), requiredPlugins.required()));
        Set<String> allKeys = new HashSet<>(core.keySet());
        for (var plugin : snapshots) {
            if (plugin.id().equals("host")) throw new IOException("transfer.config-owner-invalid");
            Map<String, GuiConfigFieldContribution> fields = fields(plugin.configContributions().stream()
                    .flatMap(contribution -> contribution.fields().stream()).toList());
            for (var field : fields.values()) {
                if (!allKeys.add(field.key()) || connectionKeys.contains(field.key())) throw new IOException("transfer.config-owner-invalid");
                if (field.sensitive() && !top.sywyar.pixivdownload.config.credential.migration.PluginCredentialDefinitionResolver
                        .isOwnedNamespace(plugin.id(), field.key())) throw new IOException("transfer.config-owner-invalid");
            }
            owners.put(plugin.id(), new Owner(plugin.id(), Map.copyOf(fields), plugin.fingerprint()));
        }
        return owners;
    }

    private static Map<String, GuiConfigFieldContribution> fields(List<GuiConfigFieldContribution> fields) throws IOException {
        Map<String, GuiConfigFieldContribution> result = new LinkedHashMap<>();
        for (var field : fields) {
            if (field == null || field.type() == null || result.putIfAbsent(ConfigFileEditor.requireSafeKey(field.key()), field) != null)
                throw new IOException("transfer.config-owner-invalid");
        }
        return result;
    }

    private Map<String, String> read(Owner owner, Collection<String> keys, boolean secret) throws IOException {
        Path path = owner.path(secret);
        requireBounded(path);
        Map<String, String> values = secret && !owner.hostOwned() ? new LinkedHashMap<>(credentials.readForTransfer(owner.id()))
                : owner.hostOwned() ? new ConfigFileEditor(path).readAll(keys) : new PropertiesConfigFileEditor(path).readAll(keys);
        values.keySet().retainAll(keys);
        if (owner.hostOwned() && !secret) for (String key : keys)
            if (key.startsWith("plugins.") && key.endsWith(".enabled")) {
                String id = key.substring("plugins.".length(), key.length() - ".enabled".length());
                if (requiredPlugins.requirement(id).map(requirement -> !requirement.allowDisable()).orElse(false)) values.put(key, "true");
                else values.putIfAbsent(key, "true");
            }
        return values;
    }

    private void validate(Owner owner, Map<String, String> updates) throws IOException {
        Map<String, String> merged = new LinkedHashMap<>();
        owner.fields().forEach((key, field) -> merged.put(key, field.defaultValue()));
        merged.putAll(read(owner, owner.fields().keySet(), false));
        merged.putAll(updates);
        for (var entry : updates.entrySet()) {
            var field = owner.fields().get(entry.getKey());
            String value = ConfigFileEditor.requireSafeValue(entry.getValue());
            boolean valid = switch (field.type()) {
                case BOOL -> value.equals("true") || value.equals("false");
                case INT, PORT -> validNumber(field, value);
                case ENUM -> field.enumValues().contains(value);
                case MULTI_ENUM -> GuiConfigFieldType.validMultiSelection(value, field.enumValues());
                case TIME -> host.validMaintenanceTime(value);
                default -> true;
            };
            if (!valid || field.sensitive() && value.isBlank()) throw new IOException("transfer.config-value-invalid");
            if (owner.hostOwned()) {
                validatePreference(entry.getKey(), value, merged);
                host.validateCoreConfigValue(entry.getKey(), value);
            }
        }
        Map<String, String> existingCredentials = read(owner, owner.fields().keySet(), true);
        for (var field : owner.fields().values()) {
            if (field.missingRequiredValue(merged, !existingCredentials.getOrDefault(field.key(), "").isBlank()))
                throw new IOException("transfer.config-required");
        }
    }

    private void validatePreference(String key, String value, Map<String, String> merged) throws IOException {
        boolean valid = true;
        if (key.equals("app.language") && !value.isBlank()) valid = host.matchLocale(value).isPresent();
        if (key.equals("app.theme") && !value.isBlank()) valid = Set.of("system", "light", "dark").contains(value)
                || plugins.get().stream().flatMap(plugin -> plugin.themes().stream()).anyMatch(theme -> theme.themeId().equals(value));
        if (key.equals("app.gui-provider") && !value.isBlank()) valid = plugins.get().stream()
                .anyMatch(plugin -> plugin.desktopUiProvider() && plugin.id().equals(value));
        if (key.startsWith("plugins.") && key.endsWith(".enabled") && value.equals("false")) {
            String id = key.substring("plugins.".length(), key.length() - ".enabled".length());
            valid = requiredPlugins.requirement(id).map(requirement -> requirement.allowDisable()).orElse(true);
        }
        String gui = merged.getOrDefault("app.gui-provider", "");
        if (!gui.isBlank() && (key.equals("app.gui-provider") || key.equals("plugins." + gui + ".enabled"))
                && merged.getOrDefault("plugins." + gui + ".enabled", "true").equals("false")) valid = false;
        if (!valid) throw new IOException("transfer.config-value-invalid");
    }

    private static boolean validNumber(GuiConfigFieldContribution field, String value) {
        try {
            int parsed = Integer.parseInt(value);
            return (field.minValue() == null || parsed >= field.minValue())
                    && (field.maxValue() == null || parsed <= field.maxValue())
                    && (field.type() != GuiConfigFieldType.PORT || parsed >= 1 && parsed <= 65535);
        } catch (NumberFormatException invalid) { return false; }
    }
    private static boolean isPath(GuiConfigFieldContribution field) {
        return field.type() == GuiConfigFieldType.PATH_DIR || field.type() == GuiConfigFieldType.PATH_FILE;
    }
    private static Map<String, String> parse(byte[] bytes) throws IOException {
        var node = JSON.readTree(bytes);
        if (node == null || !node.isObject() || node.size() > 10_000) throw new IOException("transfer.config-value-invalid");
        Map<String, String> values = new LinkedHashMap<>();
        for (var iterator = node.fields(); iterator.hasNext();) {
            var entry = iterator.next();
            if (!entry.getValue().isTextual()) throw new IOException("transfer.config-value-invalid");
            values.put(ConfigFileEditor.requireSafeKey(entry.getKey()), ConfigFileEditor.requireSafeValue(entry.getValue().textValue()));
        }
        return values;
    }
    private static void requireBounded(Path path) throws IOException {
        MigrationFiles.requirePlain(path, false);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) > MAX_BYTES) throw new IOException("transfer.archive-limit");
    }
    private static String digest(Path path) throws IOException {
        requireBounded(path);
        return FileRestoreTransaction.stateDigest(path);
    }
}
