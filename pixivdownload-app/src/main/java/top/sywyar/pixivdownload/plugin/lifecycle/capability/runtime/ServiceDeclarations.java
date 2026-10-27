package top.sywyar.pixivdownload.plugin.lifecycle.capability.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** 在复制插件提供的声明前，先限制单 owner 的累计条目。 */
public final class ServiceDeclarations {
    private ServiceDeclarations() { }

    public static <T> List<T> copy(List<T> values) {
        Objects.requireNonNull(values, "service declarations");
        if (values.size() > 128) throw new IllegalArgumentException("too many service declarations");
        List<T> result = new ArrayList<>();
        for (T value : values) {
            if (result.size() == 128) throw new IllegalArgumentException("too many service declarations");
            result.add(Objects.requireNonNull(value, "service declaration"));
        }
        return List.copyOf(result);
    }
}
