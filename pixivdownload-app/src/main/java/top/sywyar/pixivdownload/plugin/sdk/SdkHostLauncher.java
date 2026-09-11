package top.sywyar.pixivdownload.plugin.sdk;

import top.sywyar.pixivdownload.common.Utf8ConsoleStreams;
import top.sywyar.pixivdownload.gui.GuiLauncher;
import top.sywyar.pixivdownload.i18n.MessageBundles;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;

/** SDK 启动的完整宿主在工具退出或收到项目停止标记时走正常退出协调器。 */
public final class SdkHostLauncher {
    private SdkHostLauncher() {
    }

    public static void main(String[] args) {
        Utf8ConsoleStreams.install();
        try {
            if (args.length < 3) throw new IllegalArgumentException("SDK_HOST_ARGUMENTS");
            long parentPid = Long.parseLong(args[0]);
            boolean development = Boolean.getBoolean("pixivdownload.plugin-dev.enabled")
                    && parentPid == ProcessHandle.current().pid();
            var parent = (development ? java.util.Optional.of(ProcessHandle.current())
                    : ProcessHandle.current().parent()).filter(process -> process.pid() == parentPid)
                    .filter(process -> process.info().startInstant().map(Object::toString).orElse("").equals(args[1]))
                    .orElseThrow(() -> new IllegalArgumentException("SDK_HOST_PARENT"));
            Path run = Path.of(args[2]).toRealPath();
            Path workingDirectory = development
                    ? Path.of(System.getProperty("pixivdownload.plugin-dev.root")).toRealPath().resolve(".dev") : run;
            if (!workingDirectory.equals(Path.of("").toRealPath())
                    || (development && !run.getParent().getParent().equals(workingDirectory))
                    || !run.getParent().endsWith(Path.of(".dev", "runs"))) {
                throw new IllegalArgumentException("SDK_HOST_WORKSPACE");
            }
            Thread owner = new Thread(() -> {
                try {
                    while (parent.isAlive() && !Files.exists(run.resolve("stop.request"), LinkOption.NOFOLLOW_LINKS)) {
                        Thread.sleep(250);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                GuiLauncher.requestApplicationExit();
            }, "sdk-host-owner");
            owner.setDaemon(true);
            owner.start();
            GuiLauncher.main(Arrays.copyOfRange(args, 3, args.length));
        } catch (Exception failure) {
            System.err.println(MessageBundles.get("cli.error.unexpected", failure.getMessage()));
            System.exit(1);
        }
    }
}
