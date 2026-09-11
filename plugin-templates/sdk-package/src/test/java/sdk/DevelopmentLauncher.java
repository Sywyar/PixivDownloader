package sdk;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.Arrays;

/** IDE entry point; test sources keep the launcher out of the plugin artifact. */
public final class DevelopmentLauncher {
    private DevelopmentLauncher() {
    }

    public static void main(String[] args) throws Exception {
        try (var loader = new URLClassLoader(new URL[]{Path.of(args[0]).toUri().toURL()},
                ClassLoader.getPlatformClassLoader())) {
            Thread.currentThread().setContextClassLoader(loader);
            loader.loadClass("org.springframework.boot.loader.launch.JarLauncher")
                    .getMethod("main", String[].class)
                    .invoke(null, (Object) Arrays.copyOfRange(args, 1, args.length));
        }
    }
}
