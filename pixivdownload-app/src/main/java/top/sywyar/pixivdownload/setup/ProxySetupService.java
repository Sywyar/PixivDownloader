package top.sywyar.pixivdownload.setup;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.config.ProxyConfig;
import top.sywyar.pixivdownload.config.RuntimeConfigReloadService;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 首次安装时把代理配置写入 config.yaml 并热重载。
 * <p>proxy.* 本身是 hot-reloadable 的，写盘后立即调用
 * {@link RuntimeConfigReloadService#reloadHotConfig()} 即可生效，无需重启。
 * 热重载失败不影响安装本身（值已落盘，下次启动会读取）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ProxySetupService {

    private final RuntimeConfigReloadService runtimeConfigReloadService;
    private final AppMessages messages;

    /**
     * 把代理配置写入 config.yaml 并尝试热重载。
     *
     * @param enabled 是否启用代理
     * @param host    代理主机；{@code enabled} 为 false 时仍会落盘以便下次开启时复用
     * @param port    代理端口
     */
    public void applyAndReload(boolean enabled, String host, int port) throws IOException {
        ConfigFileEditor editor = new ConfigFileEditor(RuntimeFiles.resolveConfigYamlPath());
        Map<String, String> values = new LinkedHashMap<>();
        values.put(ProxyConfig.KEY_ENABLED, Boolean.toString(enabled));
        values.put(ProxyConfig.KEY_HOST, host);
        values.put(ProxyConfig.KEY_PORT, Integer.toString(port));
        editor.writeAll(values);

        try {
            runtimeConfigReloadService.reloadHotConfig();
        } catch (IOException | RuntimeException e) {
            // 落盘已成功，仅热重载失败：记录后让其在下次启动生效，不让安装流程失败。
            log.warn(messages.getForLog("setup.proxy.log.hot-reload-failed", e.getMessage()));
        }
    }
}
