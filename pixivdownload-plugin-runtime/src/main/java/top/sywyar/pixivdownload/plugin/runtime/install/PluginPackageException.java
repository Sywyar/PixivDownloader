package top.sywyar.pixivdownload.plugin.runtime.install;

import java.util.Objects;

/**
 * 读取 / 解析外置插件包时发现包结构非法（无法读出描述符、布局歧义、含越界 entry 等）抛出的异常。
 * 携带一个 {@link Reason} 让安装器把「包本身的问题」映射为明确的安装结果（{@link PluginInstallOutcome}），
 * 区别于安装器自身的 IO 失败。
 *
 * <p>本异常只在「连描述符都读不出 / 包结构非法」时抛出；描述符已读出但内容不合法（缺字段、版本非 semver 等）或
 * 核心 API 不兼容由安装器据 {@link top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginDescriptor} 校验，
 * 不走本异常。
 */
public class PluginPackageException extends RuntimeException {

    /** 非法包的原因分类，安装器据此选择对应的拒绝结果。 */
    public enum Reason {
        /** 空包：zip 没有任何 entry。 */
        EMPTY,
        /** 包损坏 / 不是合法 zip / 声明的描述符 entry 读取失败。 */
        MALFORMED,
        /** 缺描述符：根既无 {@code plugin.properties}、也无含描述符的根插件 jar。 */
        NO_DESCRIPTOR,
        /** 布局歧义：根同时存在描述符与插件 jar，或存在多个根插件 jar 候选，无法确定唯一插件。 */
        AMBIGUOUS,
        /** 越界 entry：解压后会逃逸出安装目录（Zip Slip）。 */
        UNSAFE,
        /**
         * 资源规模超限：归档文件体积、entry 数量、单 entry / 总解压字节、压缩比或描述符读取字节超出
         * {@link PluginPackageLimits} 安全上限（防 Zip Bomb / 解压资源耗尽）。
         */
        TOO_LARGE
    }

    private final Reason reason;

    public PluginPackageException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public PluginPackageException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    public Reason reason() {
        return reason;
    }
}
