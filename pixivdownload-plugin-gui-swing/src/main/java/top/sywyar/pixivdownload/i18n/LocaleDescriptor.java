package top.sywyar.pixivdownload.i18n;

import java.util.List;
import java.util.Locale;

/**
 * 语言清单中的单条语言描述，全部信息来自唯一的机器可读清单 {@code i18n/locales.json}。
 *
 * @param tag            规范化后的 BCP 47 语言 tag（如 {@code zh-CN}）
 * @param nativeName     原生语言名称（如 {@code 简体中文}），非空
 * @param resourceSuffix properties 资源文件后缀（如 {@code en} → {@code *_en.properties}；源语言为空串）
 * @param status         {@link LocaleStatus}
 * @param direction      文本方向：仅 {@code ltr} / {@code rtl}
 * @param aliases        语言别名（如 {@code zh}、{@code zh-Hans}），参与匹配但不进入正式菜单
 */
public record LocaleDescriptor(
        String tag,
        String nativeName,
        String resourceSuffix,
        LocaleStatus status,
        String direction,
        List<String> aliases) {

    public LocaleDescriptor {
        tag = LocaleCatalog.normalizeTag(tag);
        if (nativeName == null || nativeName.isBlank()) {
            throw new IllegalArgumentException("locale " + tag + " has empty nativeName");
        }
        if (status == null) {
            throw new IllegalArgumentException("locale " + tag + " has no status");
        }
        if (direction == null || !(direction.equals("ltr") || direction.equals("rtl"))) {
            throw new IllegalArgumentException("locale " + tag + " has invalid direction: " + direction);
        }
        if (resourceSuffix == null) {
            throw new IllegalArgumentException("locale " + tag + " has no resourceSuffix");
        }
        aliases = aliases == null ? List.of() : List.copyOf(aliases);
    }

    public Locale toLocale() {
        return Locale.forLanguageTag(tag);
    }

    /** 是否进入普通用户的正式语言菜单（source 或 supported）。 */
    public boolean visible() {
        return status.visible();
    }

    public boolean isSource() {
        return status == LocaleStatus.SOURCE;
    }

    @Override
    public String toString() {
        return tag;
    }
}
