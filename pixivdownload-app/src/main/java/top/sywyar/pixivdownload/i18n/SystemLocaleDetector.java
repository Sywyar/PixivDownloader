package top.sywyar.pixivdownload.i18n;

import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * 在 {@code main()} 第一行调用，确定本次运行的全局 locale，并通过
 * {@link Locale#setDefault} 设回 JVM 默认 locale，让所有依赖 {@code Locale.getDefault()}
 * 的代码（{@link AppMessages#getForLog} / {@code GuiMessages} / {@code HtmlLogLayout} 等）
 * 都从同一份信号读取。
 *
 * <h3>检测优先级（高 → 低）</h3>
 * <ol>
 *   <li>{@code config.yaml} 中的 {@code app.language} —— 用户在 GUI 显式选过</li>
 *   <li>{@link #OS_LOCALE_AT_STARTUP}：本类加载时（早于任何 {@code Locale.setDefault} 调用）
 *       通过 OS 原生 API 取得的 {@link Locale#getDefault()} 快照：
 *     <ul>
 *       <li>Windows：{@code GetUserDefaultLocaleName} via {@code kernel32.dll}</li>
 *       <li>macOS：{@code NSLocale} via Cocoa</li>
 *       <li>Linux：JVM 解析 {@code LC_ALL} / {@code LC_MESSAGES} / {@code LANG}</li>
 *     </ul>
 *     <p>注意此处使用<strong>启动快照</strong>而非实时 {@code Locale.getDefault()}：
 *     GUI 显式切换语言会调用 {@code setDefault} 污染默认值，再切回"跟随系统"时
 *     实时读取会回到上次显式选择，导致跟随系统失效。</p></li>
 *   <li>POSIX env 兜底（仅 Linux/macOS）：再读一遍 {@code LC_ALL} / {@code LC_MESSAGES} / {@code LANG}，
 *     处理 JDK 因为 {@code -Dfile.encoding} 等参数被改写的极端情况</li>
 *   <li>仍找不到匹配 → {@link AppLocale#DEFAULT_LOCALE}（en-US）</li>
 * </ol>
 *
 * <p><strong>实现约束：本类禁止使用 SLF4J 或任何 {@code @Slf4j} 标注的类。</strong>
 * 本类必须能在 logback 初始化之前运行，否则 {@code HtmlLogLayout} 的 HTML 头部会用错 locale。
 * 早期诊断输出走 {@link System#err}。
 */
public final class SystemLocaleDetector {

    /**
     * config.yaml 的两个候选位置（与 {@code RuntimeFiles.resolveConfigYamlPath} 对齐）。
     * 这里不能引用 {@code RuntimeFiles}，因为它是 {@code @Slf4j} 类，会触发 logback 初始化。
     */
    private static final Path[] CONFIG_CANDIDATES = {
            Path.of("config", "config.yaml"),
            Path.of("config.yaml")
    };

    /**
     * JVM 启动时 OS 原生 API 写入的 {@link Locale#getDefault()} 快照。
     * <p>本类是 {@code GuiLauncher.main()} 的第一个调用入口，类加载发生在所有
     * {@code Locale.setDefault()} 之前——此处的静态初始化即捕获 OS 原始值，
     * 不会被后续 GUI 显式切换语言时的 {@code setDefault} 污染。</p>
     * <p>用途：当用户在 GUI 切换到"跟随系统"时，需要回退到这个 OS 真实值，
     * 而不是被前一次显式选择覆盖过的 {@code Locale.getDefault()}。</p>
     */
    private static final Locale OS_LOCALE_AT_STARTUP = Locale.getDefault();

    private SystemLocaleDetector() {}

    /**
     * 检测并应用全局 locale。返回最终生效的 locale。
     * 调用此方法后，{@link Locale#getDefault()} 即为返回值。
     */
    public static Locale detectAndApply() {
        DetectionResult result = detect();
        Locale.setDefault(result.locale());
        System.err.println("[i18n] System locale resolved: " + result.summary());
        return result.locale();
    }

    private static DetectionResult detect() {
        Locale fromConfig = readConfigPreference();
        if (fromConfig != null) {
            Locale matched = AppLocale.matchSupported(fromConfig);
            if (matched != null) {
                return new DetectionResult(matched,
                        "from config.yaml app.language=" + fromConfig.toLanguageTag());
            }
        }

        // 必须使用启动时缓存的 OS 原始值；Locale.getDefault() 可能已被 GUI 显式切换污染
        Locale jvm = OS_LOCALE_AT_STARTUP;
        Locale matchedJvm = AppLocale.matchSupported(jvm);
        if (matchedJvm != null) {
            return new DetectionResult(matchedJvm,
                    "from JVM default " + jvm.toLanguageTag() + " (native OS API at startup)");
        }

        Locale fromEnv = readPosixEnv();
        if (fromEnv != null) {
            Locale matchedEnv = AppLocale.matchSupported(fromEnv);
            if (matchedEnv != null) {
                return new DetectionResult(matchedEnv,
                        "from POSIX env " + fromEnv.toLanguageTag());
            }
        }

        return new DetectionResult(AppLocale.DEFAULT_LOCALE,
                "no supported locale detected (JVM at startup=" + jvm.toLanguageTag()
                        + "), falling back to " + AppLocale.DEFAULT_LOCALE.toLanguageTag());
    }

    private static Locale readConfigPreference() {
        for (Path candidate : CONFIG_CANDIDATES) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                String value = new ConfigFileEditor(candidate).read("app.language");
                Locale parsed = AppLocale.parse(value);
                if (parsed != null) {
                    return parsed;
                }
            } catch (Exception e) {
                System.err.println("[i18n] Failed reading " + candidate + ": " + e.getMessage());
            }
        }
        return null;
    }

    private static Locale readPosixEnv() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return null;
        }
        for (String key : new String[]{"LC_ALL", "LC_MESSAGES", "LANG"}) {
            String value = System.getenv(key);
            if (value == null || value.isBlank() || "C".equalsIgnoreCase(value) || "POSIX".equalsIgnoreCase(value)) {
                continue;
            }
            String tag = stripCharsetAndModifier(value).replace('_', '-');
            try {
                Locale parsed = Locale.forLanguageTag(tag);
                if (!parsed.getLanguage().isBlank()) {
                    return parsed;
                }
            } catch (Exception ignored) {
                // try next env var
            }
        }
        return null;
    }

    private static String stripCharsetAndModifier(String value) {
        // "en_US.UTF-8" → "en_US" ; "zh_CN.UTF-8@latin" → "zh_CN"
        int dot = value.indexOf('.');
        String stripped = dot < 0 ? value : value.substring(0, dot);
        int at = stripped.indexOf('@');
        return at < 0 ? stripped : stripped.substring(0, at);
    }

    private record DetectionResult(Locale locale, String reason) {
        String summary() {
            return locale.toLanguageTag() + " (" + reason + ")";
        }
    }
}
