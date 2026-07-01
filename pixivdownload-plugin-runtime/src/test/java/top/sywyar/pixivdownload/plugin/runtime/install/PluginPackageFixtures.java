package top.sywyar.pixivdownload.plugin.runtime.install;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 测试夹具：用 {@code java.util.zip} 生成各类外置插件包（解压目录形态 / 单 jar 形态 / 各类非法包），
 * 不依赖 PF4J 与真实 class 加载，仅在文件系统层面验证读取 / 安装机制。
 */
final class PluginPackageFixtures {

    private PluginPackageFixtures() {
    }

    /** 拼一份 PF4J {@code plugin.properties}（各参数为 {@code null} 时跳过该行）。 */
    static String pluginProperties(String id, String version, String requires, String pluginClass) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, PluginPackageReader.KEY_ID, id);
        appendLine(sb, PluginPackageReader.KEY_VERSION, version);
        appendLine(sb, PluginPackageReader.KEY_CLASS, pluginClass);
        appendLine(sb, PluginPackageReader.KEY_REQUIRES, requires);
        return sb.toString();
    }

    private static void appendLine(StringBuilder sb, String key, String value) {
        if (value != null) {
            sb.append(key).append('=').append(value).append('\n');
        }
    }

    static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 把 entryName→内容 的映射写成一个 zip 文件（key 以 {@code /} 结尾视为目录条目）。 */
    static void writeZip(Path file, Map<String, byte[]> entries) {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            writeEntries(zos, entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 同 {@link #writeZip}，但返回 zip 字节（用于嵌套成内层 jar）。 */
    static byte[] zipBytes(Map<String, byte[]> entries) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(bos)) {
            writeEntries(zos, entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bos.toByteArray();
    }

    private static void writeEntries(ZipOutputStream zos, Map<String, byte[]> entries) throws IOException {
        for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
            zos.putNextEntry(new ZipEntry(entry.getKey()));
            byte[] content = entry.getValue();
            if (content != null && content.length > 0) {
                zos.write(content);
            }
            zos.closeEntry();
        }
    }

    /** 解压目录形态包：根 {@code plugin.properties} + {@code classes/} 负载。 */
    static Path explodedZip(Path file, String id, String version, String requires, String pluginClass) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES, bytes(pluginProperties(id, version, requires, pluginClass)));
        entries.put("classes/", new byte[0]);
        entries.put("classes/Marker.class", bytes("fake-class-bytes"));
        writeZip(file, entries);
        return file;
    }

    /** 一个含 {@code plugin.properties} 的插件 jar 的字节。 */
    static byte[] pluginJarBytes(String id, String version, String requires, String pluginClass) {
        return pluginJarBytes(id, version, requires, pluginClass, Map.of());
    }

    /** 一个含 {@code plugin.properties} 的插件 jar 的字节，可额外携带私有 {@code lib/*.jar}。 */
    static byte[] pluginJarBytes(String id, String version, String requires, String pluginClass,
                                 Map<String, byte[]> extraEntries) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES, bytes(pluginProperties(id, version, requires, pluginClass)));
        entries.put("com/example/Marker.class", bytes("fake-class-bytes"));
        entries.putAll(extraEntries);
        return zipBytes(entries);
    }

    /** 单 jar 形态包：zip 根仅一个插件 jar。 */
    static Path singleJarZip(Path file, String jarEntryName,
                             String id, String version, String requires, String pluginClass) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(jarEntryName, pluginJarBytes(id, version, requires, pluginClass));
        writeZip(file, entries);
        return file;
    }

    /** 上传物本身就是一个含 {@code plugin.properties} 的插件 jar。 */
    static Path bareJar(Path file, String id, String version, String requires, String pluginClass) {
        try {
            Files.write(file, pluginJarBytes(id, version, requires, pluginClass));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
