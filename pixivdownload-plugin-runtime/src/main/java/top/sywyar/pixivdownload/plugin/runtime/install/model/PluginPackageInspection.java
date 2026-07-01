package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor;

import java.util.Objects;

/**
 * 读取一个外置插件包得到的检视结果：判定出的布局形态 + 从包描述符（PF4J {@code plugin.properties}）映射出的
 * <b>包级</b>统一描述符。
 *
 * <p>这是<b>包级</b>视图：安装阶段不加载插件类，故只能据 PF4J 描述符建模——{@link PluginDescriptor#id()} 等于
 * {@link PluginDescriptor#sourcePluginId()}（都取 {@code plugin.id}），{@code kind} 取功能插件默认值
 * {@link top.sywyar.pixivdownload.plugin.api.plugin.PluginKind#FEATURE}，{@code displayName} 取
 * {@code plugin.description}（缺省回退 {@code plugin.id}）。运行期发现桥接才会把包内具体功能插件展开为各自的描述符。
 *
 * @param format        布局形态
 * @param descriptor    包级统一描述符（身份 / 版本 / requires / 依赖 / 主类）
 * @param innerJarEntry            {@link PluginPackageFormat#SINGLE_JAR} 且来源为 zip 时，包内那个插件 jar 的 entry 名
 *                                 （安装阶段据此从 zip 内取出该 jar）；解压目录形态或上传物本身即 jar 时为 {@code null}
 * @param containsPrivateLibraries 包内是否存在 {@code lib/*.jar} 私有依赖。thin JAR 为 {@code false} 时可直接交给 PF4J；
 *                                 含私有依赖的 JAR 或 ZIP 需要先物化为 PF4J directory layout。
 */
public record PluginPackageInspection(
        PluginPackageFormat format,
        PluginDescriptor descriptor,
        String innerJarEntry,
        boolean containsPrivateLibraries) {

    public PluginPackageInspection {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(descriptor, "descriptor");
    }
}
