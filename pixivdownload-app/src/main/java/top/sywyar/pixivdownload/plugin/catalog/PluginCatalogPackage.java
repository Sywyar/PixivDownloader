package top.sywyar.pixivdownload.plugin.catalog;

import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;

import java.util.List;

/**
 * 受信 catalog 中某个插件的一个可安装版本制品（市场元数据，<b>纯 JDK record、不入 {@code plugin-api}</b>）。所有字段都来自
 * 服务端配置的受信目录清单，<b>绝不</b>来自请求参数。
 *
 * @param version           插件版本（semver；安装端点 {@code /{pluginId}/{version}/install} 按它选包）
 * @param packageUrl        插件包下载地址（必须 https；下载器只接受 catalog 给出的此 URL，<b>不</b>接受任意 URL）
 * @param expectedSizeBytes 期望文件字节数（下载流式上限 + 落盘前完整性校验，必填且 &gt; 0）
 * @param sha256            期望 SHA-256 十六进制（必填；落盘前比对，不符即拒绝）
 * @param signature         结构化签名元数据（可空；官方包缺失时 fail-closed）
 * @param signatureUrl      可选 detached artifact signature URL（展示 / 诊断；安装使用已验证 manifest 中的结构化签名）
 * @param requiredCoreApi   声明的核心 API 版本要求（展示 / 兼容标记用；安装时由<b>下载包描述符</b>权威裁定，不在此另立权威）
 * @param dependencies      声明的插件间依赖（展示用；安装时由下载包描述符权威解析）
 * @param releasedTime      该版本发布时间（ISO-8601 字符串，可空；仅展示——版本历史 / 更新日志）
 * @param changeNotes       该版本更新说明条目（可空；仅展示——详情弹窗的更新日志）
 * @param channel           发布通道（如 {@code stable} / {@code beta}；可空，{@code null} 视为 {@code stable}；仅展示 / 过滤）
 * @param deprecated        该版本是否已下架 / 不建议安装（仅展示，页面可置灰；不阻断安装）
 */
public record PluginCatalogPackage(
        String version,
        String packageUrl,
        Long expectedSizeBytes,
        String sha256,
        SignatureMetadata signature,
        String signatureUrl,
        String requiredCoreApi,
        List<String> dependencies,
        String releasedTime,
        List<String> changeNotes,
        String channel,
        boolean deprecated) {

    public PluginCatalogPackage {
        dependencies = dependencies != null ? List.copyOf(dependencies) : List.of();
        changeNotes = changeNotes != null ? List.copyOf(changeNotes) : List.of();
    }

    /** 是否声明了结构化签名元数据。 */
    public boolean hasSignature() {
        return signature != null;
    }
}
