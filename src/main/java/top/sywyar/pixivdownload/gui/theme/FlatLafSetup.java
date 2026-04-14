package top.sywyar.pixivdownload.gui.theme;

import com.formdev.flatlaf.FlatLightLaf;
import lombok.extern.slf4j.Slf4j;

import javax.swing.*;
import java.awt.*;

/**
 * FlatLaf 主题初始化 + 跨平台中文字体回退链。
 */
@Slf4j
public final class FlatLafSetup {

    /**
     * 优先级（依次尝试，取首个可用）：
     * 1. Microsoft YaHei UI  (Windows 默认中文字体)
     * 2. PingFang SC         (macOS)
     * 3. Noto Sans CJK SC    (Linux 常见)
     * 4. Source Han Sans SC  (思源黑体备选)
     * 5. SimSun              (Windows 老版本兜底)
     * 6. Dialog              (Java 逻辑字体，最后兜底)
     */
    private static final String[] FONT_PRIORITY = {
            "Microsoft YaHei UI",
            "PingFang SC",
            "Noto Sans CJK SC",
            "Source Han Sans SC",
            "SimSun",
            "Dialog"
    };

    private FlatLafSetup() {}

    /**
     * 必须在 EDT 上调用（创建 JFrame 之前）。
     */
    public static void apply() {
        try {
            FlatLightLaf.setup();
        } catch (Exception e) {
            log.warn("FlatLaf 初始化失败，使用系统默认 L&F: {}", e.getMessage());
        }
        applyChineseFont();
    }

    private static void applyChineseFont() {
        String[] available = GraphicsEnvironment
                .getLocalGraphicsEnvironment()
                .getAvailableFontFamilyNames();
        java.util.Set<String> availableSet = new java.util.HashSet<>(java.util.Arrays.asList(available));

        for (String name : FONT_PRIORITY) {
            if (availableSet.contains(name)) {
                Font font = new Font(name, Font.PLAIN, 13);
                UIManager.put("defaultFont", font);
                log.debug("GUI 字体已设置为: {}", name);
                return;
            }
        }
        log.warn("未找到任何预设中文字体，使用系统默认字体");
    }
}
