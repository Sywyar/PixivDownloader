package top.sywyar.pixivdownload.gui;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

/** 用文件握手控制入口类加载，让真实观测器稳定遇到启动中的应用。 */
public final class DelayedLauncher {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        while (!Files.exists(directory.resolve("load"))) Thread.sleep(20);
        Class.forName("top.sywyar.pixivdownload.gui.GuiLauncher");
        while (!Files.exists(directory.resolve("exit"))) Thread.sleep(20);
    }
}

/** 仅在隔离测试 JVM 中提供观测器实际读取的入口状态。 */
final class GuiLauncher {
    private static final AtomicReference<Object> ACTIVE_UI = new AtomicReference<>();
}
