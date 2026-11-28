package top.sywyar.pixivdownload.translation;

import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

enum CloudProvider {
    DEEPL("api.deepl.com/v2/translate", 5000, 50, "api-key"),
    GOOGLE("translation.googleapis.com/language/translate/v2", 5000, 100, "api-key"),
    AZURE("api.cognitive.microsofttranslator.com/translate", 5000, 100, "api-key", "region"),
    BAIDU("fanyi-api.baidu.com/api/trans/vip/translate", 1500, 1, "key-id", "secret"),
    TENCENT("tmt.tencentcloudapi.com/", 5000, 1, "key-id", "secret", "region"),
    VOLCENGINE("translate.volcengineapi.com/?Action=TranslateText&Version=2020-06-01", 5000, 16, "key-id", "secret"),
    CAIYUN("api.interpreter.caiyunai.com/v1/translator", 1000, 10, "api-key"),
    NIUTRANS("api.niutrans.com/NiuTransServer/translation", 5000, 1, "api-key"),
    ALIYUN("mt.aliyuncs.com/", 5000, 1, "key-id", "secret");

    final String id = name().toLowerCase(Locale.ROOT);
    final URI uri;
    final int chars;
    final int batch;
    final List<String> fields;

    CloudProvider(String endpoint, int chars, int batch, String... fields) {
        uri = URI.create("https://" + endpoint);
        this.chars = chars;
        this.batch = batch;
        this.fields = List.of(fields);
    }

    static final List<String> FORMALITY = List.of("default", "more", "less", "prefer_more", "prefer_less");
    static final Map<String, CloudProvider> BY_ID = Arrays.stream(values())
            .collect(java.util.stream.Collectors.toUnmodifiableMap(p -> p.id, p -> p));
    static final List<String> ENGINES = Stream.concat(Stream.of("ai", "youdao", "libretranslate"),
            Arrays.stream(values()).map(p -> p.id)).toList();

    URI endpoint(TranslationConfig.CloudSettings settings) {
        return this == DEEPL && settings.getApiKey().endsWith(":fx")
                ? URI.create("https://api-free.deepl.com/v2/translate") : uri;
    }

    boolean configured(TranslationConfig.CloudSettings s) {
        if (fields.contains("api-key")) return !s.getApiKey().isBlank();
        return !s.getKeyId().isBlank() && !s.getSecret().isBlank() && (this != TENCENT || !s.getRegion().isBlank());
    }

    private static final Map<String, Map<String, String>> TARGETS = loadTargets();

    private static Map<String, Map<String, String>> loadTargets() {
        var properties = new java.util.Properties();
        try (var input = CloudProvider.class.getResourceAsStream("/translation-targets.properties")) {
            properties.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(input),
                    java.nio.charset.StandardCharsets.UTF_8));
        } catch (java.io.IOException failure) { throw new ExceptionInInitializerError(failure); }
        var targets = new java.util.HashMap<String, Map<String, String>>();
        for (var provider : values()) {
            if (provider == DEEPL) continue;
            var codes = new java.util.LinkedHashMap<String, String>();
            for (String item : properties.getProperty(provider.id).split(" +")) {
                String[] pair = item.split(":", 2);
                String tag = MachineTranslationClient.language(pair[0]);
                if (!tag.isEmpty()) codes.put(tag, pair.length == 1 ? pair[0] : pair[1]);
            }
            targets.put(provider.id, java.util.Collections.unmodifiableMap(codes));
        }
        return Map.copyOf(targets);
    }

    Map<String, String> targets() { return TARGETS.get(id); }

    String target(String language) {
        String tag = MachineTranslationClient.language(language);
        if (tag.isEmpty()) throw new IllegalArgumentException("translation.language-unsupported");
        String code = this == DEEPL ? null : targets().get(tag);
        if (code != null) return code;
        Locale locale = Locale.forLanguageTag(tag);
        if (locale.getLanguage().equals("zh")) {
            var chinese = MachineTranslationClient.chineseWritingSystem(locale);
            if (this == DEEPL) return chinese.getCountry().equals("TW") ? "ZH-HANT" : "ZH-HANS";
            code = targets().get(chinese.toLanguageTag());
        } else if (this == DEEPL) {
            if (tag.equals("pt")) return "PT-PT";
            if (List.of(Locale.JAPAN, Locale.KOREA).contains(locale)) return locale.getLanguage().toUpperCase(Locale.ROOT);
            return tag.toUpperCase(Locale.ROOT);
        }
        if (code == null) throw new IllegalArgumentException("translation.language-unsupported");
        return code;
    }
}
