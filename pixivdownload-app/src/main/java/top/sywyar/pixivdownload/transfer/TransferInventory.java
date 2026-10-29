package top.sywyar.pixivdownload.transfer;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopEnvironmentTransfer.*;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost;
import top.sywyar.pixivdownload.plugin.api.gui.DesktopUiPluginSnapshot;
import top.sywyar.pixivdownload.plugin.runtime.bootstrap.PluginBootstrapSession;
import top.sywyar.pixivdownload.transfer.archive.TransferArchive;

import java.io.*;
import java.net.URI;
import java.util.*;

/** 插件版本和仓库地址只作补齐提示；归档不能携带可执行包、信任根或审批记录。 */
final class TransferInventory {
    private static final ObjectMapper JSON = new ObjectMapper().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private static final TransferArchive.Descriptor DESCRIPTOR = new TransferArchive.Descriptor("host",
            TransferArchive.Category.INVENTORY, "plugins.json", 1);
    record Package(String id, String version) {
        Package { identifier(id); if (version == null || version.length() > 128 || version.chars().anyMatch(Character::isISOControl)) throw invalid(); }
    }
    record Inventory(int version, List<Package> plugins, List<RepositoryLocation> repositories) {
        Inventory {
            if (version != 1 || plugins == null || repositories == null || plugins.size() > 1024 || repositories.size() > 256) throw invalid();
            plugins = List.copyOf(plugins); repositories = List.copyOf(repositories);
            Set<String> ids = new HashSet<>();
            for (var plugin : plugins) if (!ids.add(plugin.id())) throw invalid();
            ids.clear();
            for (var repository : repositories) {
                identifier(repository.id());
                if (!ids.add(repository.id()) || !safeUrl(repository.descriptorUrl()).equals(repository.descriptorUrl())
                        || !safeUrl(repository.manifestUrl()).equals(repository.manifestUrl())) throw invalid();
            }
        }
    }

    static TransferArchive.Source export(DesktopUiHost host, PluginBootstrapSession session,
                                         List<DesktopUiPluginSnapshot> plugins) throws IOException {
        var versions = versions(session);
        var packages = plugins.stream().filter(plugin -> !plugin.builtIn())
                .map(plugin -> new Package(plugin.id(), versions.getOrDefault(plugin.id(), ""))).toList();
        var repositories = host.applicationConfig() == null ? List.<RepositoryLocation>of()
                : host.readPluginRepositories(host.applicationConfig()).stream().map(repository -> new RepositoryLocation(repository.id(),
                        safeUrl(Objects.toString(repository.extraFields().get("descriptor-url"), "")), safeUrl(repository.manifestUrl()))).toList();
        byte[] bytes = JSON.writeValueAsBytes(new Inventory(1, packages, repositories));
        if (bytes.length > 8 * 1024 * 1024) throw new IOException("transfer.archive-limit");
        return new TransferArchive.Source(DESCRIPTOR, () -> new ByteArrayInputStream(bytes));
    }

    static Set<Integer> indices(TransferArchive.Preview archive) throws IOException {
        Set<Integer> result = new LinkedHashSet<>();
        for (int i = 0; i < archive.items().size(); i++) {
            var descriptor = archive.items().get(i).descriptor();
            if (descriptor.category() != TransferArchive.Category.INVENTORY) continue;
            if (!descriptor.equals(DESCRIPTOR)) throw new IOException("transfer.inventory-invalid");
            result.add(i);
        }
        return result;
    }

    static Inventory read(TransferArchive.Preview archive, TransferArchive.SelectedContents contents) throws IOException {
        for (int i : indices(archive)) {
            var inventory = JSON.readValue(contents.get(i), Inventory.class);
            if (inventory == null) throw new IOException("transfer.inventory-invalid");
            return inventory;
        }
        return new Inventory(1, List.of(), List.of());
    }

    static List<PluginPackage> preview(Inventory inventory, PluginBootstrapSession session, List<DesktopUiPluginSnapshot> current) {
        var versions = versions(session);
        Set<String> installed = current.stream().map(DesktopUiPluginSnapshot::id).collect(java.util.stream.Collectors.toSet());
        return inventory.plugins().stream().map(plugin -> new PluginPackage(plugin.id(), plugin.version(),
                installed.contains(plugin.id()), versions.getOrDefault(plugin.id(), ""))).toList();
    }

    private static Map<String, String> versions(PluginBootstrapSession session) {
        if (session == null) return Map.of();
        Map<String, String> result = new HashMap<>();
        for (var plugin : session.manager().inspectPlugins().installations())
            if (plugin.descriptor() != null) result.put(plugin.id(), plugin.descriptor().version());
        return result;
    }

    private static String safeUrl(String value) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > 8192) return "";
        try {
            URI uri = URI.create(value);
            return "https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null ? value : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }
    private static void identifier(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) throw invalid();
    }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("transfer.inventory-invalid"); }
}
