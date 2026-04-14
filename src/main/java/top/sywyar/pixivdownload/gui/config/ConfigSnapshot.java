package top.sywyar.pixivdownload.gui.config;

import java.util.Map;

/**
 * 当前配置值的只读快照，供 enabledWhen 谓词判断字段间依赖关系。
 */
public record ConfigSnapshot(Map<String, String> values) {

    public String get(String key) {
        return values.getOrDefault(key, "");
    }

    public boolean isTrue(String key) {
        return "true".equalsIgnoreCase(get(key));
    }

    public boolean equals(String key, String expected) {
        return expected.equalsIgnoreCase(get(key));
    }

    public boolean notBlank(String key) {
        return !get(key).isBlank();
    }
}
