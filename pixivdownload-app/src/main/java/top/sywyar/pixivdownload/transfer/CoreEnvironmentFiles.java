package top.sywyar.pixivdownload.transfer;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.plugin.api.storage.EnvironmentFileContribution;

import java.io.*;
import java.util.List;

/** 核心只声明自己拥有的管理员、桌面偏好与收藏夹资源。 */
final class CoreEnvironmentFiles implements EnvironmentFileContribution {
    private static final ObjectMapper JSON = JsonMapper.builderWithJackson2Defaults()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();
    private final Definition definition;

    private CoreEnvironmentFiles(Definition definition) { this.definition = definition; }

    static List<EnvironmentFileContribution> contributions() {
        return List.of(new CoreEnvironmentFiles(new Definition("administrator", Root.STATE, "", "setup_config\\.json", 1,
                        Kind.CREDENTIALS, 1, 1024 * 1024, false)),
                new CoreEnvironmentFiles(new Definition("window", Root.STATE, RuntimeFiles.GUI_STATE_DIR, "window-state\\.json", 1,
                        Kind.STATE, 1, 4096, false)),
                new CoreEnvironmentFiles(new Definition("collection-icons", Root.DATA, RuntimeFiles.COLLECTION_ICONS_DIR,
                        "[1-9][0-9]*\\.(png|jpg|jpeg|webp)", 1, Kind.RESOURCE, 1, 1024 * 1024, true)));
    }
    @Override public Definition definition() { return definition; }

    @Override public void copy(String name, InputStream source, InputStream existing, OutputStream output,
                               boolean includeCredentials) throws IOException {
        if (definition.kind() == Kind.RESOURCE) { source.transferTo(output); return; }
        ObjectNode saved = read(source);
        ObjectNode result = JSON.createObjectNode();
        if (definition.id().equals("administrator")) {
            if (!includeCredentials || !saved.path("setupComplete").isBoolean()) throw new IOException("transfer.file-invalid");
            for (String key : List.of("username", "displayName", "passwordHash", "salt")) {
                var value = saved.get(key);
                if (value != null && !value.isNull() && (!value.isString() || value.stringValue().length() > 1024))
                    throw new IOException("transfer.file-invalid");
                if (value != null) result.set(key, value);
            }
            if (saved.path("setupComplete").booleanValue() && (saved.path("username").asString().isBlank()
                    || saved.path("passwordHash").asString().isBlank())) throw new IOException("transfer.file-invalid");
            result.set("setupComplete", saved.get("setupComplete"));
            String mode = existing == null ? "solo" : read(existing).path("mode").asString("solo");
            if (!List.of("solo", "multi").contains(mode)) throw new IOException("transfer.file-invalid");
            result.put("mode", mode);
            result.putObject("sessions");
        } else {
            if (saved.size() != 3 || !saved.path("width").isIntegralNumber() || !saved.path("height").isIntegralNumber()
                    || !saved.path("width").canConvertToInt() || !saved.path("height").canConvertToInt()
                    || !saved.path("maximized").isBoolean()) throw new IOException("transfer.file-invalid");
            try {
                new top.sywyar.pixivdownload.plugin.api.gui.DesktopUiHost.WindowStateSnapshot(
                        saved.path("width").intValue(), saved.path("height").intValue(), saved.path("maximized").booleanValue());
            } catch (IllegalArgumentException invalid) { throw new IOException("transfer.file-invalid"); }
            result = saved;
        }
        output.write(JSON.writeValueAsBytes(result));
    }
    private ObjectNode read(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes((int) definition.maxFileBytes() + 1);
        try {
            if (bytes.length > definition.maxFileBytes() || !(JSON.readTree(bytes) instanceof ObjectNode object))
                throw new IOException("transfer.file-invalid");
            return object;
        } finally { java.util.Arrays.fill(bytes, (byte) 0); }
    }
}
