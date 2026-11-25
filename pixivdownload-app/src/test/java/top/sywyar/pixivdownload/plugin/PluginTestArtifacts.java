package top.sywyar.pixivdownload.plugin;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

final class PluginTestArtifacts {

    private PluginTestArtifacts() {
    }

    static void zipDirectoryAsJar(Path sourceDir, Path jarPath) throws IOException {
        zipDirectoryAsJar(sourceDir, jarPath, false);
    }

    static void zipDirectoryAsHotReloadJar(Path sourceDir, Path jarPath) throws IOException {
        zipDirectoryAsJar(sourceDir, jarPath, true);
    }

    private static void zipDirectoryAsJar(Path sourceDir, Path jarPath, boolean hotReload) throws IOException {
        try (OutputStream out = Files.newOutputStream(jarPath);
             ZipOutputStream zos = new ZipOutputStream(out)) {
            List<Path> files;
            try (var walk = Files.walk(sourceDir)) {
                files = walk.filter(Files::isRegularFile).sorted().toList();
            }
            for (Path file : files) {
                String entryName = sourceDir.relativize(file).toString().replace('\\', '/');
                zos.putNextEntry(new ZipEntry(entryName));
                if (hotReload && entryName.equals("plugin.properties")) {
                    Properties descriptor = new Properties();
                    try (var reader = Files.newBufferedReader(file)) {
                        descriptor.load(reader);
                    }
                    descriptor.setProperty("pixiv.lifecycle-policy", "hot-reload");
                    descriptor.store(zos, null);
                } else {
                    Files.copy(file, zos);
                }
                zos.closeEntry();
            }
        }
    }
}
