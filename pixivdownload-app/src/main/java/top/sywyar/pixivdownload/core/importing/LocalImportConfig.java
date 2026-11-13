package top.sywyar.pixivdownload.core.importing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.Preset;
import top.sywyar.pixivdownload.plugin.api.gui.importing.LocalArtworkImports.VariableException;
import java.util.*;

@Configuration
@ConfigurationProperties(prefix = "local-import")
public class LocalImportConfig {
    private static final tools.jackson.databind.json.JsonMapper JSON = tools.jackson.databind.json.JsonMapper.builder()
            .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .enable(tools.jackson.core.StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    private volatile String presets = "[]";
    public String getPresets() { return presets; }
    public void setPresets(String value) { parse(value); presets = value; }
    public List<Preset> entries() { return parse(presets); }

    public static List<Preset> parse(String value) {
        if (value == null || value.length() > 64 * 1024) throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
        try {
            var node = JSON.readTree(value);
            if (!node.isArray() || node.size() > 32) throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
            List<Preset> result = new ArrayList<>();
            for (var item : node) {
                if (!item.isObject() || !item.path("template").isString()
                        || item.propertyNames().stream().anyMatch(key -> !Set.of("template", "name", "enabled").contains(key)))
                    throw new IllegalArgumentException("INVALID_IMPORT_PRESET");
                var preset = new Preset(item.path("template").asString());
                new LocalImportTemplate(preset.template());
                result.add(preset);
            }
            return List.copyOf(result);
        } catch (VariableException invalid) { throw invalid; }
        catch (RuntimeException invalid) { throw new IllegalArgumentException("INVALID_IMPORT_PRESET"); }
    }
}
