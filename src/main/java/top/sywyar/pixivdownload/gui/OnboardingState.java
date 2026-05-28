package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.setup.SetupConfig;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 记录用户是否已经完成 GUI 引导首页。
 *
 * <p>“看过/完成引导” 标记文件落在 {@code state/gui/} 文件夹，
 * 遵循 “辅助数据不写入下载根目录” 的约定，仅在整套引导真正完成时写入。</p>
 *
 * <p>注意：该标记与 “首次安装是否完成”（{@code setup_config.json} 中的
 * {@code setupComplete}）是两套独立存储。引导能完成的前提是 setup 已完成，
 * 故判断 “是否还要停留在首页” 必须同时看 setup 状态——否则一个残留的旧标记
 * 会让未配置的用户被错误地带到「状态」页。</p>
 */
@Slf4j
public final class OnboardingState {

    private static final String FLAG_FILE_NAME = "onboarding-seen";
    private static final String PROXY_CONFIGURED_FILE_NAME = "proxy-configured";
    private static final String PROGRESS_FILE_NAME = "wizard-progress";
    private static final String FINISHED_FILE_NAME = "wizard-finished";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OnboardingState() {
    }

    public static boolean isSeen() {
        return Files.exists(flagFile());
    }

    public static boolean isProxyConfigured() {
        return Files.exists(proxyConfiguredFile());
    }

    /**
     * 引导是否真正全部完成：首次安装已完成（setup_config.json.setupComplete）
     * 且向导已走到最后一页。两者缺一不可。
     */
    public static boolean isComplete(String rootFolder) {
        return isFinished() && isSetupComplete(rootFolder);
    }

    /**
     * 引导是否已经走到最后一页（“完成”页）。检查显式的 finished 标记，
     * 同时兼容旧版本仅写入 {@code onboarding-seen}（在画廊页完成时落盘）的情况。
     */
    public static boolean isFinished() {
        return Files.exists(finishedFile()) || Files.exists(flagFile());
    }

    /**
     * 读取上次保存的向导页码（1..7）。文件缺失或解析失败返回 0，
     * 调用方据此回退到默认起始页。
     */
    public static int loadProgress() {
        Path p = progressFile();
        if (!Files.exists(p)) {
            return 0;
        }
        try {
            return Integer.parseInt(Files.readString(p).trim());
        } catch (Exception e) {
            log.debug("Failed to read wizard progress: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * 保存向导当前页码（1..7）。写失败仅记日志，最差只是下次启动回退到首步。
     */
    public static void saveProgress(int step) {
        Path p = progressFile();
        try {
            Files.createDirectories(p.getParent());
            Files.writeString(p, Integer.toString(step));
        } catch (Exception e) {
            log.debug("Failed to persist wizard progress: {}", e.getMessage());
        }
    }

    public static void markFinished() {
        mark(finishedFile(), "wizard finished flag");
    }

    /**
     * 读取 {@code setup_config.json} 判断首次安装是否完成；读不到一律视为未完成。
     */
    public static boolean isSetupComplete(String rootFolder) {
        Path path = RuntimeFiles.resolveSetupConfigPath(rootFolder);
        if (!Files.exists(path)) {
            return false;
        }
        try {
            SetupConfig config = MAPPER.readValue(path.toFile(), SetupConfig.class);
            return config.isSetupComplete();
        } catch (Exception e) {
            log.debug("Failed to read setup_config.json: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 清除引导标记（用于发现残留的无效旧标记时复位）。
     */
    public static void clear() {
        try {
            Files.deleteIfExists(flagFile());
            Files.deleteIfExists(proxyConfiguredFile());
            Files.deleteIfExists(progressFile());
            Files.deleteIfExists(finishedFile());
        } catch (Exception e) {
            log.debug("Failed to clear onboarding flag: {}", e.getMessage());
        }
    }

    public static void markSeen() {
        mark(flagFile(), "onboarding flag");
    }

    public static void markProxyConfigured() {
        mark(proxyConfiguredFile(), "proxy configured flag");
    }

    private static void mark(Path flagFile, String label) {
        if (Files.exists(flagFile)) {
            return;
        }
        try {
            Files.createDirectories(flagFile.getParent());
            Files.writeString(flagFile, "1");
        } catch (Exception e) {
            // 写失败仅意味着下次仍会自动展示引导，不影响功能
            log.debug("Failed to persist {}: {}", label, e.getMessage());
        }
    }

    private static Path flagFile() {
        return RuntimeFiles.guiStateDirectory().resolve(FLAG_FILE_NAME);
    }

    private static Path proxyConfiguredFile() {
        return RuntimeFiles.guiStateDirectory().resolve(PROXY_CONFIGURED_FILE_NAME);
    }

    private static Path progressFile() {
        return RuntimeFiles.guiStateDirectory().resolve(PROGRESS_FILE_NAME);
    }

    private static Path finishedFile() {
        return RuntimeFiles.guiStateDirectory().resolve(FINISHED_FILE_NAME);
    }
}
