package top.sywyar.pixivdownload.gui;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.Graphics;
import java.awt.Label;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 用文件握手控制入口类加载，让真实观测器稳定遇到启动中的应用。 */
public final class DelayedLauncher {
    private static Dialog dialog;

    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        while (!Files.exists(directory.resolve("load"))) Thread.sleep(20);
        Class.forName("top.sywyar.pixivdownload.gui.GuiLauncher");
        while (!Files.exists(directory.resolve("exit"))) {
            if (Files.deleteIfExists(directory.resolve("block"))) {
                EventQueue.invokeLater(() -> blockEventThread(directory));
            }
            if (Files.isRegularFile(directory.resolve("edt-timeout.txt"))
                    && Files.deleteIfExists(directory.resolve("release-after-timeout"))) {
                Files.writeString(directory.resolve("release"), "");
            }
            Thread.sleep(20);
        }
        System.exit(0);
    }

    private static void blockEventThread(Path directory) {
        try {
            if (dialog == null) {
                dialog = new Dialog((Frame) null, "Queued dismissal sentinel");
                dialog.addWindowListener(new WindowAdapter() {
                    @Override public void windowClosing(WindowEvent event) { dialog.dispose(); }
                });
                dialog.setLayout(new FlowLayout());
                dialog.setFont(new Font(Font.DIALOG, Font.PLAIN, 14));
                for (String text : new String[]{"Release observer fixture", "Ready"}) {
                    dialog.add(new Label(text) {
                        // 原生 Label 的离屏 paint 不绘制文字；夹具显式绘制以接受同一可读性检查。
                        @Override public void paint(Graphics graphics) {
                            graphics.drawString(getText(), 0, getFontMetrics(getFont()).getAscent());
                        }
                        @Override public javax.accessibility.AccessibleContext getAccessibleContext() {
                            try {
                                if (Files.deleteIfExists(directory.resolve("block-inspection"))) {
                                    Files.createFile(directory.resolve("inspection-started"));
                                    awaitRelease(directory);
                                }
                            } catch (Exception failure) {
                                throw new IllegalStateException(failure);
                            }
                            return super.getAccessibleContext();
                        }
                    });
                }
                dialog.setSize(300, 160);
                dialog.setVisible(true);
            }
            Files.createFile(directory.resolve("blocked"));
            awaitRelease(directory);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitRelease(Path directory) throws Exception {
        while (!Files.exists(directory.resolve("release"))) Thread.sleep(20);
    }
}

/** 仅在隔离测试 JVM 中提供观测器实际读取的入口状态。 */
final class GuiLauncher {
    private static final AtomicReference<Object> ACTIVE_UI = new AtomicReference<>();
}
