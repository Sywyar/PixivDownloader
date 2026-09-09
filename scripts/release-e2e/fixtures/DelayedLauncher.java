package top.sywyar.pixivdownload.gui;

import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 用文件握手控制入口类加载，让真实观测器稳定遇到启动中的应用。 */
public final class DelayedLauncher {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        while (!Files.exists(directory.resolve("load"))) Thread.sleep(20);
        Class.forName("top.sywyar.pixivdownload.gui.GuiLauncher");
        while (!Files.exists(directory.resolve("exit"))) {
            if (Files.deleteIfExists(directory.resolve("block"))) {
                EventQueue.invokeLater(() -> blockEventThread(directory));
            }
            Thread.sleep(20);
        }
        System.exit(0);
    }

    private static void blockEventThread(Path directory) {
        try {
            Dialog dialog = new Dialog((Frame) null, "Queued dismissal sentinel");
            dialog.addWindowListener(new WindowAdapter() {
                @Override public void windowClosing(WindowEvent event) { dialog.dispose(); }
            });
            dialog.setSize(240, 120);
            dialog.setVisible(true);
            Files.createFile(directory.resolve("blocked"));
            while (!Files.exists(directory.resolve("release"))) Thread.sleep(20);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}

/** 仅在隔离测试 JVM 中提供观测器实际读取的入口状态。 */
final class GuiLauncher {
    private static final AtomicReference<Object> ACTIVE_UI = new AtomicReference<>();
}
