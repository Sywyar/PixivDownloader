package top.sywyar.pixivdownload.plugin.runtime.install.verify;

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
public final class PluginPackageFixtures {

    public static final String PLUGIN_PROPERTIES = PluginPackageReader.PLUGIN_PROPERTIES;

    private PluginPackageFixtures() {
    }

    /** 拼一份 PF4J {@code plugin.properties}（各参数为 {@code null} 时跳过该行）。 */
    public static String pluginProperties(String id, String version, String requires, String pluginClass) {
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

    public static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** 把 entryName→内容 的映射写成一个 zip 文件（key 以 {@code /} 结尾视为目录条目）。 */
    public static void writeZip(Path file, Map<String, byte[]> entries) {
        try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(file))) {
            writeEntries(zos, entries);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 写出两个同名 entry；JDK writer 会拒绝重复名，所以先写大小写占位名再修正中央目录与本地头。 */
    public static void writeDuplicateEntryZip(Path file, String entryName, byte[] first, byte[] second) {
        if (entryName == null || entryName.isEmpty()) {
            throw new IllegalArgumentException("entryName must not be empty");
        }
        char initial = entryName.charAt(0);
        char replacement = Character.isLowerCase(initial)
                ? Character.toUpperCase(initial) : Character.toLowerCase(initial);
        String placeholder = replacement + entryName.substring(1);
        if (placeholder.equals(entryName)) {
            throw new IllegalArgumentException("entryName must start with a cased character");
        }
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(entryName, first);
        entries.put(placeholder, second);
        writeZip(file, entries);

        try {
            byte[] archive = Files.readAllBytes(file);
            byte[] from = placeholder.getBytes(StandardCharsets.UTF_8);
            byte[] to = entryName.getBytes(StandardCharsets.UTF_8);
            int replacements = 0;
            for (int i = 0; i <= archive.length - from.length; i++) {
                boolean matches = true;
                for (int j = 0; j < from.length; j++) {
                    if (archive[i + j] != from[j]) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    System.arraycopy(to, 0, archive, i, to.length);
                    replacements++;
                    i += from.length - 1;
                }
            }
            if (replacements != 2) {
                throw new IllegalStateException("unexpected duplicate entry placeholder count: " + replacements);
            }
            Files.write(file, archive);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 把指定中央目录 entry 标为 Unix 符号链接，用于验证读取器不会把链接元数据当作普通文件放行。 */
    public static void markEntryAsUnixSymlink(Path file, String entryName) {
        try {
            byte[] archive = Files.readAllBytes(file);
            byte[] encodedName = entryName.getBytes(StandardCharsets.UTF_8);
            int matches = 0;
            for (int offset = 0; offset <= archive.length - 46; offset++) {
                if (archive[offset] != 'P' || archive[offset + 1] != 'K'
                        || archive[offset + 2] != 1 || archive[offset + 3] != 2) {
                    continue;
                }
                int nameLength = unsignedShort(archive, offset + 28);
                if (nameLength != encodedName.length || offset + 46 + nameLength > archive.length) {
                    continue;
                }
                boolean sameName = true;
                for (int index = 0; index < nameLength; index++) {
                    sameName &= archive[offset + 46 + index] == encodedName[index];
                }
                if (!sameName) {
                    continue;
                }
                archive[offset + 5] = 3; // version-made-by host: Unix
                archive[offset + 38] = 0;
                archive[offset + 39] = 0;
                archive[offset + 40] = 0;
                archive[offset + 41] = (byte) 0xa0; // 0120000 << 16, little endian
                matches++;
            }
            if (matches != 1) {
                throw new IllegalStateException("unexpected central directory entry count: " + matches);
            }
            Files.write(file, archive);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    /** 同 {@link #writeZip}，但返回 zip 字节（用于嵌套成内层 jar）。 */
    public static byte[] zipBytes(Map<String, byte[]> entries) {
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
    public static Path explodedZip(Path file, String id, String version, String requires, String pluginClass) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES, bytes(pluginProperties(id, version, requires, pluginClass)));
        entries.put("classes/", new byte[0]);
        entries.put("classes/Marker.class", bytes("fake-class-bytes"));
        writeZip(file, entries);
        return file;
    }

    /** 一个含 {@code plugin.properties} 的插件 jar 的字节。 */
    public static byte[] pluginJarBytes(String id, String version, String requires, String pluginClass) {
        return pluginJarBytes(id, version, requires, pluginClass, Map.of());
    }

    /** 一个含 {@code plugin.properties} 的插件 jar 的字节，可额外携带私有 {@code lib/*.jar}。 */
    public static byte[] pluginJarBytes(String id, String version, String requires, String pluginClass,
                                        Map<String, byte[]> extraEntries) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(PluginPackageReader.PLUGIN_PROPERTIES, bytes(pluginProperties(id, version, requires, pluginClass)));
        entries.put("com/example/Marker.class", bytes("fake-class-bytes"));
        entries.putAll(extraEntries);
        return zipBytes(entries);
    }

    /** 单 jar 形态包：zip 根仅一个插件 jar。 */
    public static Path singleJarZip(Path file, String jarEntryName,
                                    String id, String version, String requires, String pluginClass) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        entries.put(jarEntryName, pluginJarBytes(id, version, requires, pluginClass));
        writeZip(file, entries);
        return file;
    }

    /** 上传物本身就是一个含 {@code plugin.properties} 的插件 jar。 */
    public static Path bareJar(Path file, String id, String version, String requires, String pluginClass) {
        try {
            Files.write(file, pluginJarBytes(id, version, requires, pluginClass));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return file;
    }
}
