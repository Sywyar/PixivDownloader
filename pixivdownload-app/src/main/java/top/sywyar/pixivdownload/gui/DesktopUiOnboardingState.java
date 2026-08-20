package top.sywyar.pixivdownload.gui;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.config.RuntimeFiles;

import java.nio.file.Files;
import java.nio.file.Path;

/** 应用拥有的桌面引导状态持久化实现。 */
@Slf4j
final class DesktopUiOnboardingState {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SEEN = "onboarding-seen";
    private static final String PROXY = "proxy-configured";
    private static final String PROGRESS = "wizard-progress";
    private static final String FINISHED = "wizard-finished";

    DesktopUiHost.OnboardingSnapshot snapshot(String rootFolder) {
        boolean seen = Files.exists(path(SEEN));
        return new DesktopUiHost.OnboardingSnapshot(seen, Files.exists(path(PROXY)), progress(),
                Files.exists(path(FINISHED)) || seen, setupComplete(rootFolder));
    }
    boolean saveProgress(int step) { return write(path(PROGRESS), Integer.toString(step)); }
    boolean markSeen() { return write(path(SEEN), "1"); }
    boolean markProxyConfigured() { return write(path(PROXY), "1"); }
    boolean markFinished() { return write(path(FINISHED), "1"); }
    boolean clear() {
        try {
            Files.deleteIfExists(path(SEEN)); Files.deleteIfExists(path(PROXY));
            Files.deleteIfExists(path(PROGRESS)); Files.deleteIfExists(path(FINISHED));
            return true;
        } catch (Exception failure) {
            log.debug("Failed to clear desktop onboarding state: {}", failure.toString());
            return false;
        }
    }
    private int progress() {
        Path file = path(PROGRESS);
        if (!Files.exists(file)) return 0;
        try { return Integer.parseInt(Files.readString(file).trim()); }
        catch (Exception failure) {
            log.debug("Failed to read desktop onboarding progress: {}", failure.toString());
            return 0;
        }
    }
    private boolean setupComplete(String rootFolder) {
        if (rootFolder == null || rootFolder.isBlank()) return false;
        Path file = RuntimeFiles.resolveSetupConfigPath(rootFolder);
        if (!Files.exists(file)) return false;
        try { return MAPPER.readTree(file.toFile()).path("setupComplete").asBoolean(false); }
        catch (Exception failure) {
            log.debug("Failed to read setup completion state: {}", failure.toString());
            return false;
        }
    }
    private static boolean write(Path file, String value) {
        if (Files.exists(file)) return true;
        try {
            Files.createDirectories(file.getParent()); Files.writeString(file, value); return true;
        } catch (Exception failure) {
            log.debug("Failed to persist desktop onboarding state: {}", failure.toString()); return false;
        }
    }
    private static Path path(String name) { return RuntimeFiles.guiStateDirectory().resolve(name); }
}
