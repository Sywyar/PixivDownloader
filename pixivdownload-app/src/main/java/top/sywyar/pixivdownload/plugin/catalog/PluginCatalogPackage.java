package top.sywyar.pixivdownload.plugin.catalog;

import java.util.List;

/**
 * 受信 catalog 中某个插件的一个可安装版本包（市场元数据，<b>纯 JDK record、不入 {@code plugin-api}</b>）。所有字段都来自
 * 服务端配置的受信目录清单，<b>绝不</b>来自请求参数。
 *
 * @param version           插件版本（semver；安装端点 {@code /{pluginId}/{version}/install} 按它选包）
 * @param packageUrl        插件包下载地址（必须 https；下载器只接受 catalog 给出的此 URL，<b>不</b>接受任意 URL）
 * @param expectedSizeBytes 期望文件字节数（下载流式上限 + 落盘前完整性校验，必填且 &gt; 0）
 * @param sha256            期望 SHA-256 十六进制（必填；落盘前比对，不符即拒绝）
 * @param signature         期望签名（预留位，可空；一旦非空、当前无签名校验器即 <b>fail-closed</b> 拒绝安装）
 * @param requiredCoreApi   声明的核心 API 版本要求（展示 / 兼容标记用；安装时由<b>下载包描述符</b>权威裁定，不在此另立权威）
 * @param dependencies      声明的插件间依赖（展示用；安装时由下载包描述符权威解析）
 */
public record PluginCatalogPackage(
        String version,
        String packageUrl,
        Long expectedSizeBytes,
        String sha256,
        String signature,
        String requiredCoreApi,
        List<String> dependencies) {

    public PluginCatalogPackage {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
    }

    /** 是否声明了非空签名（非空 + 无校验器 → 完整性校验 fail-closed 拒绝）。 */
    public boolean hasSignature() {
        return signature != null && !signature.isBlank();
    }
}
