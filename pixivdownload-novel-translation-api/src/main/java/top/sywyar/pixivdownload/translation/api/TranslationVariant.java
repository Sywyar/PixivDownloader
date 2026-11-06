package top.sywyar.pixivdownload.translation.api;

/** 译文身份；持久化分别保存语言与引擎，内容选择和听书缓存使用完整键。 */
public record TranslationVariant(String language, String engine) {
    public static final String UNKNOWN_ENGINE = "unknown";

    public TranslationVariant {
        if (language == null || language.isBlank() || language.length() > 100
                || language.indexOf('@') >= 0 || language.chars().anyMatch(Character::isISOControl)
                || engine == null || !engine.matches("[a-z][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("TRANSLATION_VARIANT_INVALID");
    }

    /** 无引擎后缀的既有键只指向来源未知的旧译文，不回退到其它引擎。 */
    public static TranslationVariant parse(String key) {
        if (key == null || key.length() > 165) throw new IllegalArgumentException("TRANSLATION_VARIANT_INVALID");
        int separator = key.indexOf('@');
        var variant = separator < 0 ? new TranslationVariant(key, UNKNOWN_ENGINE)
                : new TranslationVariant(key.substring(0, separator), key.substring(separator + 1));
        if (!variant.key().equals(key)) throw new IllegalArgumentException("TRANSLATION_VARIANT_INVALID");
        return variant;
    }

    public String key() {
        return UNKNOWN_ENGINE.equals(engine) ? language : language + "@" + engine;
    }
}
