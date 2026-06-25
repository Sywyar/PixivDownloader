package top.sywyar.pixivdownload.plugin.runtime.install;

/**
 * 一次外置插件安装尝试的结果分类。{@link #accepted()} 为 {@code true} 的几类表示插件最终落盘存在
 * （新装 / 升级 / 降级 / 已存在），其余为各类拒绝 / 失败，安装目录不因之新增半成品。
 */
public enum PluginInstallOutcome {

    /** 首次安装该 pluginId 成功。 */
    INSTALLED(true),

    /** 已存在更低版本，升级成功（旧版本被移除）。 */
    UPGRADED(true),

    /** 已存在更高版本，但显式允许降级（force），降级成功（旧版本被移除）。 */
    DOWNGRADED(true),

    /** 同 pluginId 同版本已安装：幂等，不产生重复副本。 */
    DUPLICATE(true),

    /** 已存在更高版本且未允许降级：默认拒绝，安装目录不变。 */
    DOWNGRADE_REJECTED(false),

    /** 空包（zip 无任何 entry）。 */
    REJECTED_EMPTY(false),

    /** 包损坏 / 不是合法 zip / jar。 */
    REJECTED_MALFORMED(false),

    /** 缺描述符（根无 plugin.properties，也无含描述符的根插件 jar）。 */
    REJECTED_NO_DESCRIPTOR(false),

    /** 布局歧义（多个根插件候选 / 描述符与插件 jar 并存）。 */
    REJECTED_AMBIGUOUS(false),

    /** 描述符内容非法（id / 版本 semver / 主类等不合法）。 */
    REJECTED_INVALID(false),

    /** 声明的核心 API 版本要求（requires）不被当前核心满足，拒绝安装为可加载状态。 */
    REJECTED_INCOMPATIBLE(false),

    /** 含越界 entry（Zip Slip），拒绝整包。 */
    REJECTED_UNSAFE(false),

    /**
     * 资源规模超出安全上限（归档体积 / entry 数量 / 单 entry 或总解压字节 / 压缩比 / 描述符读取字节），
     * 防 Zip Bomb 与解压资源耗尽；零落盘。
     */
    REJECTED_TOO_LARGE(false),

    /**
     * 未通过完整性校验：来源声明的期望大小 / SHA-256 / 签名与实际不符（或声明了签名但当前无可用校验器，
     * fail-closed 拒绝）。本地上传无此类期望、永不触发；仅受信 catalog 元数据驱动的来源才带完整性期望。
     */
    REJECTED_INTEGRITY(false),

    /** 安装过程发生 IO 失败；安装器已清理暂存、不留半成品。 */
    FAILED(false);

    private final boolean accepted;

    PluginInstallOutcome(boolean accepted) {
        this.accepted = accepted;
    }

    /** 插件是否最终落盘存在（新装 / 升级 / 降级 / 已存在）。 */
    public boolean accepted() {
        return accepted;
    }

    /** 是否为拒绝 / 失败（安装目录未新增该插件）。 */
    public boolean rejected() {
        return !accepted;
    }
}
