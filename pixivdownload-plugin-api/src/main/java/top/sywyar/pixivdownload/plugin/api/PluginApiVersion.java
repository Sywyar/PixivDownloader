package top.sywyar.pixivdownload.plugin.api;

/**
 * 插件 API 契约版本（semver）。这是跨插件边界共享契约包 {@code plugin.api} 的<b>稳定版本号</b>，
 * 供外置插件声明所需的核心 API 版本（{@code requires}）并在加载时做兼容性校验。
 *
 * <p>本版本号独立于 Maven 构件版本与软件发行版本：它描述的是 {@code plugin.api} 暴露给插件的
 * <b>契约面</b>（SPI 接口、contribution record、核心服务接口及其 DTO）的演进，按语义化版本
 * MAJOR.MINOR.PATCH 管理。
 *
 * <p>兼容策略：
 * <ul>
 *   <li><b>PATCH</b>：兼容性修复，不改变契约面，不破坏插件的二进制 / 源码兼容；不参与兼容校验。</li>
 *   <li><b>MINOR</b>：向后兼容地新增（新类型、接口默认方法、contribution 可选字段等），不破坏既有插件；
 *       核心 MINOR 更高即可满足声明了更低 MINOR 的插件。</li>
 *   <li><b>MAJOR</b>：允许破坏性变更；MAJOR 不一致即不兼容，插件须显式声明可兼容的 MAJOR。</li>
 * </ul>
 *
 * <p>纯 JDK、无状态工具类：只持有常量与无副作用的静态 helper，符合 {@code plugin.api} 的零框架依赖约束。
 */
public final class PluginApiVersion {

    /** 主版本号：破坏性变更时递增；MAJOR 不一致即视为不兼容。 */
    public static final int MAJOR = 1;

    /** 次版本号：向后兼容地新增契约时递增。 */
    public static final int MINOR = 3;

    /** 修订号：兼容性修复时递增，不改变契约面、不参与兼容校验。 */
    public static final int PATCH = 0;

    /** 规范化的 semver 字符串，恒等于 {@code MAJOR + "." + MINOR + "." + PATCH}。 */
    public static final String VERSION = MAJOR + "." + MINOR + "." + PATCH;

    private PluginApiVersion() {
    }

    /** 当前插件 API 契约版本（等于 {@link #VERSION}）。 */
    public static String current() {
        return VERSION;
    }

    /**
     * 判断当前核心 API 是否兼容一个声明了所需 API 版本 {@code requiredMajor.requiredMinor} 的插件。
     * 规则：MAJOR 必须一致，且核心 MINOR 不低于插件所需 MINOR（新 MINOR 向后兼容）。PATCH 不参与判定。
     *
     * @param requiredMajor 插件所需的主版本号
     * @param requiredMinor 插件所需的次版本号
     * @return 兼容返回 {@code true}
     */
    public static boolean isCompatibleWith(int requiredMajor, int requiredMinor) {
        return isCompatible(MAJOR, MINOR, requiredMajor, requiredMinor);
    }

    /**
     * 通用的 semver 兼容判定（同一套兼容规则的<b>唯一实现</b>）：一个提供方版本
     * {@code providedMajor.providedMinor} 是否满足一个声明了 {@code requiredMajor.requiredMinor} 的依赖方。
     * 规则与 {@link #isCompatibleWith(int, int)} 完全一致——MAJOR 必须相等，提供方 MINOR 不低于所需 MINOR；
     * PATCH 不参与判定。{@link #isCompatibleWith(int, int)} 即本方法以核心 {@link #MAJOR}/{@link #MINOR} 为提供方的特例。
     *
     * <p>抽出本方法是为了让「核心 API ↔ 插件所需版本」与「被依赖插件版本 ↔ 依赖声明的版本」共用同一条兼容规则，
     * 避免版本判断逻辑被复制到插件运行时。
     *
     * @param providedMajor 提供方主版本号
     * @param providedMinor 提供方次版本号
     * @param requiredMajor 依赖方所需主版本号
     * @param requiredMinor 依赖方所需次版本号
     * @return 兼容返回 {@code true}
     */
    public static boolean isCompatible(int providedMajor, int providedMinor, int requiredMajor, int requiredMinor) {
        return requiredMajor == providedMajor && requiredMinor <= providedMinor;
    }
}
