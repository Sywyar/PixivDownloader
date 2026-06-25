package top.sywyar.pixivdownload.plugin.runtime.install;

import top.sywyar.pixivdownload.plugin.api.PluginApiVersion;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginKind;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginApiRequirement;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDependencyRef;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * 外置插件包读取器：把一个 {@code .zip} / {@code .jar} 安装包检视为 {@link PluginPackageInspection}
 * （布局形态 + 包级统一描述符）。<b>纯读取、不落盘</b>，所有 zip / jar 句柄 try-with-resources 关闭
 * （Windows 下避免目录 / jar 锁）。
 *
 * <h2>受支持的包布局（互斥，由 zip 根内容判定）</h2>
 * <ul>
 *   <li><b>解压目录形态</b>（{@link PluginPackageFormat#EXPLODED_DIRECTORY}）：zip 根有
 *       {@value #PLUGIN_PROPERTIES}、且根没有插件 jar。这就是 PF4J 解压插件目录布局
 *       （{@value #PLUGIN_PROPERTIES} + {@code classes/} + {@code lib/}）。描述符从根 {@value #PLUGIN_PROPERTIES} 读。</li>
 *   <li><b>单 jar 形态</b>（{@link PluginPackageFormat#SINGLE_JAR}）：zip 根无 {@value #PLUGIN_PROPERTIES}、
 *       但恰有一个根 {@code *.jar}，描述符从该 jar 内的 {@value #PLUGIN_PROPERTIES} 读；或上传物本身就是一个
 *       含 {@value #PLUGIN_PROPERTIES} 的 {@code .jar}。</li>
 * </ul>
 *
 * <h2>判为非法包（抛 {@link PluginPackageException}）</h2>
 * <ul>
 *   <li>{@link PluginPackageException.Reason#EMPTY}：zip 无任何 entry；</li>
 *   <li>{@link PluginPackageException.Reason#NO_DESCRIPTOR}：根无 {@value #PLUGIN_PROPERTIES} 且无根插件 jar，
 *       或唯一根 jar 内无 {@value #PLUGIN_PROPERTIES}；</li>
 *   <li>{@link PluginPackageException.Reason#AMBIGUOUS}：根同时有 {@value #PLUGIN_PROPERTIES} 与插件 jar，
 *       或有多个根插件 jar 候选；</li>
 *   <li>{@link PluginPackageException.Reason#MALFORMED}：不是合法 zip / 读取失败。</li>
 * </ul>
 *
 * <p>本读取器只统一了「描述符的位置 / 形态」。描述符<b>内容</b>是否合法（id / 版本 semver / 主类等）由
 * {@link PluginDescriptor#externalValidationErrors()} 校验、核心 API 兼容由
 * {@link PluginDescriptor#isApiCompatible()} 判定，均在安装器里做；而「插件主类是否实现入口契约」需加载类、属运行期
 * 发现桥接职责，安装阶段不校验。描述符 key 与 PF4J {@code PropertiesPluginDescriptorFinder} 完全一致。
 */
public final class PluginPackageReader {

    /** PF4J 属性式描述符文件名。 */
    static final String PLUGIN_PROPERTIES = "plugin.properties";

    // PF4J plugin.properties 的 key（与 org.pf4j.PropertiesPluginDescriptorFinder 一致）
    static final String KEY_ID = "plugin.id";
    static final String KEY_VERSION = "plugin.version";
    static final String KEY_CLASS = "plugin.class";
    static final String KEY_DESCRIPTION = "plugin.description";
    static final String KEY_REQUIRES = "plugin.requires";
    static final String KEY_DEPENDENCIES = "plugin.dependencies";

    private PluginPackageReader() {
    }

    /**
     * 检视一个插件包（{@code .zip} 或 {@code .jar}），返回布局 + 包级描述符，描述符读取按
     * {@link PluginPackageLimits#defaults()} 限制字节数。包结构非法时抛 {@link PluginPackageException}
     * （携带 {@link PluginPackageException.Reason}）。
     */
    public static PluginPackageInspection inspect(Path packagePath) {
        return inspect(packagePath, PluginPackageLimits.defaults());
    }

    /**
     * 同 {@link #inspect(Path)}，但用给定 {@code limits} 的 {@link PluginPackageLimits#maxDescriptorBytes()} 限制
     * {@value #PLUGIN_PROPERTIES} 读取字节数：描述符超限抛 {@link PluginPackageException.Reason#TOO_LARGE}。
     */
    public static PluginPackageInspection inspect(Path packagePath, PluginPackageLimits limits) {
        Objects.requireNonNull(packagePath, "packagePath");
        Objects.requireNonNull(limits, "limits");
        long maxDescriptorBytes = limits.maxDescriptorBytes();
        String name = packagePath.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".jar")) {
            Properties properties = readPluginPropertiesFromArchive(packagePath, maxDescriptorBytes);
            if (properties == null) {
                throw new PluginPackageException(PluginPackageException.Reason.NO_DESCRIPTOR,
                        "jar contains no " + PLUGIN_PROPERTIES + ": " + packagePath.getFileName());
            }
            return new PluginPackageInspection(PluginPackageFormat.SINGLE_JAR, toDescriptor(properties), null);
        }
        return inspectZip(packagePath, maxDescriptorBytes);
    }

    private static PluginPackageInspection inspectZip(Path zip, long maxDescriptorBytes) {
        boolean rootProperties = false;
        List<String> rootJars = new ArrayList<>();
        int entryCount = 0;
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                entryCount++;
                if (entry.isDirectory()) {
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                if (entryName.equals(PLUGIN_PROPERTIES)) {
                    rootProperties = true;
                } else if (isRootJar(entryName)) {
                    rootJars.add(entryName);
                }
            }

            if (entryCount == 0) {
                throw new PluginPackageException(PluginPackageException.Reason.EMPTY, "package is empty (no entries)");
            }
            if (rootProperties && rootJars.isEmpty()) {
                return new PluginPackageInspection(PluginPackageFormat.EXPLODED_DIRECTORY,
                        toDescriptor(loadProperties(zipFile, PLUGIN_PROPERTIES, maxDescriptorBytes)), null);
            }
            if (rootProperties) {
                throw new PluginPackageException(PluginPackageException.Reason.AMBIGUOUS,
                        "root " + PLUGIN_PROPERTIES + " coexists with root jar(s): " + rootJars);
            }
            if (rootJars.size() == 1) {
                String jarEntry = rootJars.get(0);
                Properties properties = loadInnerJarProperties(zipFile, jarEntry, maxDescriptorBytes);
                if (properties == null) {
                    throw new PluginPackageException(PluginPackageException.Reason.NO_DESCRIPTOR,
                            "root jar " + jarEntry + " contains no " + PLUGIN_PROPERTIES);
                }
                return new PluginPackageInspection(PluginPackageFormat.SINGLE_JAR, toDescriptor(properties), jarEntry);
            }
            if (rootJars.size() > 1) {
                throw new PluginPackageException(PluginPackageException.Reason.AMBIGUOUS,
                        "multiple root plugin jar candidates: " + rootJars);
            }
            throw new PluginPackageException(PluginPackageException.Reason.NO_DESCRIPTOR,
                    "no root " + PLUGIN_PROPERTIES + " and no root plugin jar");
        } catch (ZipException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "not a valid zip package: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "failed to read package: " + e.getMessage(), e);
        }
    }

    /** 根级 jar：entry 名不含 {@code /}（位于 zip 根），且小写后以 {@code .jar} 结尾。 */
    private static boolean isRootJar(String entryName) {
        return entryName.indexOf('/') < 0 && entryName.toLowerCase(Locale.ROOT).endsWith(".jar");
    }

    /** 从一个 zip / jar 归档的根读取 {@value #PLUGIN_PROPERTIES}（不存在返回 {@code null}），读取字节受上限约束。 */
    private static Properties readPluginPropertiesFromArchive(Path archive, long maxDescriptorBytes) {
        try (ZipFile zipFile = new ZipFile(archive.toFile())) {
            return loadProperties(zipFile, PLUGIN_PROPERTIES, maxDescriptorBytes);
        } catch (ZipException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "not a valid jar package: " + e.getMessage(), e);
        } catch (IOException e) {
            throw new PluginPackageException(PluginPackageException.Reason.MALFORMED,
                    "failed to read jar package: " + e.getMessage(), e);
        }
    }

    private static Properties loadProperties(ZipFile zipFile, String entryName, long maxDescriptorBytes)
            throws IOException {
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(zipFile.getInputStream(entry))) {
            return parseDescriptor(in, entryName, maxDescriptorBytes);
        }
    }

    /** 从 zip 内某个 jar entry 的内部读取根 {@value #PLUGIN_PROPERTIES}（不存在返回 {@code null}），读取字节受上限约束。 */
    private static Properties loadInnerJarProperties(ZipFile zipFile, String jarEntryName, long maxDescriptorBytes)
            throws IOException {
        ZipEntry jarEntry = zipFile.getEntry(jarEntryName);
        if (jarEntry == null) {
            return null;
        }
        try (ZipInputStream jarStream = new ZipInputStream(
                new BufferedInputStream(zipFile.getInputStream(jarEntry)))) {
            ZipEntry inner;
            while ((inner = jarStream.getNextEntry()) != null) {
                if (inner.isDirectory()) {
                    continue;
                }
                if (inner.getName().replace('\\', '/').equals(PLUGIN_PROPERTIES)) {
                    return parseDescriptor(jarStream, jarEntryName + "!" + PLUGIN_PROPERTIES, maxDescriptorBytes);
                }
            }
        }
        return null;
    }

    /**
     * 把描述符 entry 的<b>实际读取</b>字节累计到上限内（不信任 header 声明的 size），超出
     * {@code maxDescriptorBytes} 抛 {@link PluginPackageException.Reason#TOO_LARGE}；否则按 UTF-8 解析为
     * {@link Properties}。不关闭传入流（外层 try-with-resources 负责，inner jar 流尤其不能在此关闭）。
     */
    private static Properties parseDescriptor(InputStream in, String entryName, long maxDescriptorBytes)
            throws IOException {
        byte[] data = readBounded(in, maxDescriptorBytes, entryName);
        Properties properties = new Properties();
        try (Reader reader = new InputStreamReader(new ByteArrayInputStream(data), StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return properties;
    }

    /** 按实际读取字节累计、超过 {@code limit} 立即抛 {@code TOO_LARGE}（不一次性读入未知大小的流）。 */
    private static byte[] readBounded(InputStream in, long limit, String entryName) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            total += read;
            if (total > limit) {
                throw new PluginPackageException(PluginPackageException.Reason.TOO_LARGE,
                        "descriptor " + entryName + " exceeds " + limit + " bytes");
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /**
     * 把 PF4J {@value #PLUGIN_PROPERTIES} 映射为包级 {@link PluginDescriptor}（{@code id == sourcePluginId}、
     * {@code kind = FEATURE}）。缺失 / 空字段保留为 {@code null} 交由
     * {@link PluginDescriptor#externalValidationErrors()} 判定，本方法不在此处兜底校验。安装期尚未加载功能插件实例，
     * 故简介 i18n key 与展示 token（{@code description} / {@code iconKey} / {@code colorToken}）一律为 {@code null}。
     */
    private static PluginDescriptor toDescriptor(Properties properties) {
        String id = trimToNull(properties.getProperty(KEY_ID));
        String version = trimToNull(properties.getProperty(KEY_VERSION));
        String pluginClass = trimToNull(properties.getProperty(KEY_CLASS));
        String description = trimToNull(properties.getProperty(KEY_DESCRIPTION));
        PluginApiRequirement requires = PluginApiRequirement.parse(properties.getProperty(KEY_REQUIRES));
        List<PluginDependencyRef> dependencies = parseDependencies(properties.getProperty(KEY_DEPENDENCIES));
        String displayName = (description != null) ? description : id;
        return new PluginDescriptor(id, id, version, requires, dependencies, pluginClass, null, displayName,
                null, null, null, PluginKind.FEATURE);
    }

    /**
     * 解析 PF4J {@code plugin.dependencies}（逗号分隔，每项 {@code pluginId} 或 {@code pluginId@versionSupport}，
     * pluginId 尾随 {@code ?} 表示可选），与 PF4J {@code PluginDependency} 语义一致。
     */
    private static List<PluginDependencyRef> parseDependencies(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<PluginDependencyRef> refs = new ArrayList<>();
        for (String token : raw.split(",")) {
            String dependency = token.trim();
            if (dependency.isEmpty()) {
                continue;
            }
            String pluginId;
            String versionSupport = "*";
            int at = dependency.indexOf('@');
            if (at >= 0) {
                pluginId = dependency.substring(0, at);
                if (dependency.length() > at + 1) {
                    versionSupport = dependency.substring(at + 1);
                }
            } else {
                pluginId = dependency;
            }
            boolean optional = false;
            if (pluginId.endsWith("?")) {
                optional = true;
                pluginId = pluginId.substring(0, pluginId.length() - 1);
            }
            refs.add(new PluginDependencyRef(pluginId.trim(), versionSupport.trim(), optional));
        }
        return refs;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** 当前核心 API 契约版本（供安装器在拒绝不兼容包时拼装诊断信息复用）。 */
    static String coreApiVersion() {
        return PluginApiVersion.VERSION;
    }
}
