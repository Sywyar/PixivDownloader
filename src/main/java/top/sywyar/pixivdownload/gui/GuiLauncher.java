package top.sywyar.pixivdownload.gui;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.PixivDownloadApplication;
import top.sywyar.pixivdownload.gui.config.ConfigFileEditor;
import top.sywyar.pixivdownload.gui.theme.FlatLafSetup;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * GUI 模式入口点。
 * <ul>
 *   <li>默认启动 GUI + 后台 Spring Boot</li>
 *   <li>{@code --no-gui}：纯命令行模式（服务器/Docker 场景）</li>
 *   <li>无显示设备（{@code GraphicsEnvironment.isHeadless()}）：自动降级为命令行</li>
 * </ul>
 * Spring Boot fat-jar 的 {@code Start-Class} 指向此类（pom.xml 已配置）。
 */
@Slf4j
public class GuiLauncher {

    private static final String CONFIG_FILE = "config.yaml";
    private static final int DEFAULT_PORT = 6999;
    private static final String DEFAULT_ROOT = "pixiv-download";

    public static void main(String[] args) throws Exception {
        // ── 1. 判断是否需要 GUI ─────────────────────────────────────────────────
        boolean noGui = Arrays.asList(args).contains("--no-gui")
                || GraphicsEnvironment.isHeadless();

        if (noGui) {
            log.info("无头/命令行模式启动（GUI 已禁用）");
            PixivDownloadApplication.main(args);
            return;
        }

        // ── 2. 启动前读取配置（Spring 尚未就绪，直接读文件）────────────────────────
        int serverPort = DEFAULT_PORT;
        String rootFolder = DEFAULT_ROOT;
        Path configPath = Path.of(CONFIG_FILE);

        if (configPath.toFile().exists()) {
            try {
                ConfigFileEditor editor = new ConfigFileEditor(configPath);
                String portStr = editor.read("server.port");
                String rootStr = editor.read("download.root-folder");
                if (portStr != null && !portStr.isBlank()) {
                    serverPort = Integer.parseInt(portStr.trim());
                }
                if (rootStr != null && !rootStr.isBlank()) {
                    rootFolder = rootStr.trim();
                }
            } catch (Exception e) {
                log.warn("读取配置文件失败，使用默认值: {}", e.getMessage());
            }
        }

        final int port = serverPort;
        final String root = rootFolder;

        // ── 3. 初始化 Swing + FlatLaf，展示主窗口 ────────────────────────────────
        SwingUtilities.invokeLater(() -> {
            FlatLafSetup.apply();
            MainFrame frame = new MainFrame(port, root, configPath);
            SystemTrayManager.install(frame, port, root);
            frame.setVisible(true);
        });

        // ── 4. 在后台线程启动 Spring Boot ─────────────────────────────────────────
        Thread springThread = new Thread(() -> {
            try {
                PixivDownloadApplication.main(filterArgs(args));
            } catch (Exception e) {
                log.error("Spring Boot 启动失败: {}", e.getMessage(), e);
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(null,
                                "后端服务启动失败：\n" + e.getMessage(),
                                "启动错误", JOptionPane.ERROR_MESSAGE));
            }
        }, "spring-main");
        springThread.setDaemon(false);
        springThread.start();
    }

    /** 从参数列表中过滤掉 GUI 专用参数，避免传入 Spring。 */
    private static String[] filterArgs(String[] args) {
        return Arrays.stream(args)
                .filter(a -> !a.equals("--no-gui"))
                .toArray(String[]::new);
    }
}
