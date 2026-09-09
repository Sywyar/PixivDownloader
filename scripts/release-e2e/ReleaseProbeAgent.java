package releasee2e;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dialog;
import java.awt.EventQueue;
import java.awt.Frame;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Window;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;

/** 仅由发行物测试编译并通过 -javaagent 加载；不修改类字节，也不进入任何发行包。 */
public final class ReleaseProbeAgent {
    private static final Map<String, ClassLoader> pluginLoaders = new HashMap<>();
    private static Instrumentation instrumentation;
    private static Path directory;

    public static void premain(String argument, Instrumentation value) throws Exception {
        instrumentation = value;
        directory = Path.of(argument).toAbsolutePath();
        Files.createDirectories(directory);
        Thread observer = new Thread(ReleaseProbeAgent::observe, "release-e2e-observer");
        // 观测器不得替应用保活，否则会掩盖启动失败后的正常退出。
        observer.setDaemon(true);
        observer.start();
    }

    private static void observe() {
        try {
            while (true) {
                Path request = directory.resolve("request.txt");
                if (Files.isRegularFile(request)) {
                    List<String> lines = Files.readAllLines(request, StandardCharsets.UTF_8);
                    Files.delete(request);
                    String nonce = lines.get(0);
                    try {
                        String result = execute(lines.get(1), lines.size() > 2 ? lines.get(2) : "");
                        respond(nonce, "\"ok\":true," + result);
                    } catch (Throwable failure) {
                        respond(nonce, "\"ok\":false,\"error\":" + quote(stack(failure)));
                    }
                }
                Thread.sleep(50);
            }
        } catch (Throwable failure) {
            try { Files.writeString(directory.resolve("probe-error.txt"), stack(failure), StandardCharsets.UTF_8); }
            catch (Exception ignored) { failure.printStackTrace(); }
        }
    }

    private static String execute(String command, String target) throws Exception {
        return switch (command) {
            case "ping" -> "\"pid\":" + ProcessHandle.current().pid();
            case "desktop" -> desktop();
            case "crash" -> {
                ClassLoader loader = pluginLoader(target);
                Thread failing = new Thread(() -> {
                    throw new LinkageError("RELEASE_E2E_PLUGIN_CRASH " + target);
                }, "release-e2e-plugin-" + target);
                failing.setContextClassLoader(loader);
                failing.setDaemon(true);
                failing.start();
                yield "\"plugin\":" + quote(target);
            }
            case "logs" -> {
                System.out.println("RELEASE_E2E_STDOUT <probe>&\" \u4e2d\u6587");
                System.err.println("RELEASE_E2E_STDERR");
                java.util.logging.Logger.getLogger("release-e2e").warning("RELEASE_E2E_JUL");
                var failure = new IllegalStateException("RELEASE_E2E_STACK",
                        new IllegalArgumentException("RELEASE_E2E_CAUSE"));
                failure.addSuppressed(new IllegalStateException("RELEASE_E2E_SUPPRESSED"));
                failure.printStackTrace(System.err);
                System.out.print("RELEASE_E2E_PARTIAL");
                System.out.flush();
                yield "\"logged\":true";
            }
            case "dismiss" -> {
                onEventThread(() -> {
                    for (Window window : Window.getWindows()) {
                        if (window instanceof Dialog && window.isShowing()) {
                            window.dispatchEvent(new WindowEvent(window, WindowEvent.WINDOW_CLOSING));
                        }
                    }
                    return null;
                });
                yield "\"dismissed\":true";
            }
            case "shutdown" -> {
                // 经正常应用退出协调器关闭 UI、后端和插件，不把 taskkill 当作退出验收。
                Method exit = launcher().getDeclaredMethod("requestApplicationExit");
                exit.setAccessible(true);
                exit.invoke(null);
                yield "\"shutdownRequested\":true";
            }
            default -> throw new IllegalArgumentException("Unknown probe command: " + command);
        };
    }

    private static Class<?> launcher() {
        Class<?> launcher = findLauncher();
        if (launcher != null) return launcher;
        throw new IllegalStateException("Application entry has not loaded");
    }

    private static Class<?> findLauncher() {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) {
            if (type.getName().equals("top.sywyar.pixivdownload.gui.GuiLauncher")) return type;
        }
        return null;
    }

    private static ClassLoader pluginLoader(String id) throws Exception {
        if (pluginLoaders.containsKey(id)) return pluginLoaders.get(id);
        Object runtime = field(launcher(), null, "processPluginRuntime");
        if (runtime == null) throw new IllegalStateException("Desktop plugin runtime is unavailable");
        Object discovery = runtime.getClass().getMethod("discoverFeaturePlugins").invoke(runtime);
        for (Object plugin : (List<?>) discovery.getClass().getMethod("discovered").invoke(discovery)) {
            String pluginId = (String) plugin.getClass().getMethod("sourcePluginId").invoke(plugin);
            ClassLoader loader = (ClassLoader) plugin.getClass().getMethod("classLoader").invoke(plugin);
            pluginLoaders.put(pluginId, loader);
        }
        if (!pluginLoaders.containsKey(id)) throw new IllegalStateException("Plugin is not loaded: " + id);
        return pluginLoaders.get(id);
    }

    private static String desktop() throws Exception {
        Class<?> launcher = findLauncher();
        // premain 观测器可先于应用入口响应；未加载只表示桌面仍需等待。
        if (launcher == null) return "\"applicationLoaded\":false";
        Object ui = ((AtomicReference<?>) field(launcher, null, "ACTIVE_UI")).get();
        String provider = "";
        if (ui != null) {
            Object source = field(ui.getClass(), ui, "activeSource");
            if (source != null) {
                Method id = source.getClass().getDeclaredMethod("id");
                id.setAccessible(true);
                provider = (String) id.invoke(source);
            }
        }
        Desktop desktop = onEventThread(() -> {
            List<String> windows = new ArrayList<>();
            Rectangle largest = null;
            int bootstrapPrompts = 0;
            int[] bootstrapText = new int[2];
            for (Window window : Window.getWindows()) {
                if (!window.isShowing() || !window.isDisplayable()) continue;
                if (window.getClass() == Dialog.class) {
                    bootstrapPrompts++;
                    bootstrapText(window, bootstrapText);
                }
                String title = window instanceof Frame frame ? frame.getTitle()
                        : window instanceof Dialog dialog ? dialog.getTitle() : "";
                StringBuilder labels = new StringBuilder();
                labels(window, labels);
                accessibleLabels(window, labels, 0, new int[]{2048});
                windows.add("{\"type\":" + quote(window.getClass().getName()) + ",\"title\":" + quote(title)
                        + ",\"text\":" + quote(labels.toString()) + "}");
                if ((window instanceof Frame || window.getClass() == Dialog.class)
                        && window.getWidth() >= 200 && window.getHeight() >= 80
                        && (largest == null || window.getWidth() * window.getHeight() > largest.width * largest.height)) {
                    largest = window.getBounds();
                }
            }
            return new Desktop(largest, bootstrapPrompts, "[" + String.join(",", windows) + "]",
                    bootstrapText[0] >= 2 && bootstrapText[0] == bootstrapText[1]);
        });
        int colors = 0;
        if (desktop.bounds() != null) {
            BufferedImage screenshot = new Robot().createScreenCapture(desktop.bounds());
            ImageIO.write(screenshot, "png", directory.resolve("window.png").toFile());
            // 排除窗口边框；只拒绝空白/纯色画布，不冻结平台像素、字体或布局。
            HashSet<Integer> content = new HashSet<>();
            for (int y = 35; y < screenshot.getHeight() - 15; y += 4) {
                for (int x = 20; x < screenshot.getWidth() - 20; x += 4) {
                    content.add(screenshot.getRGB(x, y));
                }
            }
            colors = content.size();
        }
        return "\"applicationLoaded\":true,\"pid\":" + ProcessHandle.current().pid() + ",\"provider\":" + quote(provider)
                + ",\"bootstrapPrompts\":" + desktop.bootstrapPrompts() + ",\"contentColors\":" + colors
                + ",\"bootstrapTextRendered\":" + desktop.bootstrapTextRendered()
                + ",\"windows\":" + desktop.windows();
    }

    private static void bootstrapText(Component component, int[] counts) {
        if (component instanceof java.awt.Label || component instanceof java.awt.Button) {
            counts[0]++;
            String text = component.getAccessibleContext().getAccessibleName();
            if (text != null && !text.isBlank() && component.getFont().canDisplayUpTo(text) < 0
                    && component.getWidth() > 0 && component.getHeight() > 0) {
                // UTF-8 下原生 AWT 的 canDisplay 可能成功而实际画方框，同时验证 Java2D 文本绘制。
                BufferedImage painted = new BufferedImage(component.getWidth(), component.getHeight(),
                        BufferedImage.TYPE_INT_ARGB);
                var graphics = painted.createGraphics();
                try { component.paint(graphics); } finally { graphics.dispose(); }
                boolean ink = false;
                for (int y = 0; y < painted.getHeight() && !ink; y++) {
                    for (int x = 0; x < painted.getWidth(); x++) {
                        if ((painted.getRGB(x, y) >>> 24) != 0) { ink = true; break; }
                    }
                }
                if (ink) counts[1]++;
            }
        }
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) bootstrapText(child, counts);
        }
    }

    private static void labels(Component component, StringBuilder output) {
        if (component instanceof java.awt.Label label) output.append(label.getText()).append('\n');
        if (component instanceof javax.swing.JLabel label) output.append(label.getText()).append('\n');
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) labels(child, output);
        }
    }

    private static void accessibleLabels(javax.accessibility.Accessible component, StringBuilder output,
                                         int depth, int[] remaining) {
        if (component == null || depth > 32 || remaining[0]-- <= 0) return;
        var context = component.getAccessibleContext();
        if (context == null) return;
        String name = context.getAccessibleName();
        if (name != null) output.append(name).append('\n');
        for (int i = 0; i < context.getAccessibleChildrenCount() && remaining[0] > 0; i++) {
            accessibleLabels(context.getAccessibleChild(i), output, depth + 1, remaining);
        }
    }

    private static <T> T onEventThread(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        EventQueue.invokeLater(task);
        return task.get(5, TimeUnit.SECONDS);
    }

    private static Object field(Class<?> type, Object object, String name) throws Exception {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(object);
    }

    private static void respond(String nonce, String result) throws Exception {
        Path temporary = directory.resolve("response.tmp");
        Files.writeString(temporary, "{\"nonce\":" + quote(nonce) + "," + result + "}", StandardCharsets.UTF_8);
        Files.move(temporary, directory.resolve("response.json"), StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE);
    }

    private static String quote(String value) {
        StringBuilder result = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            if (c == '\\' || c == '"') result.append('\\').append(c);
            else if (c < 32) result.append(String.format("\\u%04x", (int) c));
            else result.append(c);
        }
        return result.append('"').toString();
    }

    private static String stack(Throwable failure) {
        StringWriter output = new StringWriter();
        failure.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private record Desktop(Rectangle bounds, int bootstrapPrompts, String windows, boolean bootstrapTextRendered) {}
}
