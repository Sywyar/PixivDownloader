package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.runtime.install.verify.PluginPackageIntegrity;

/**
 * 一个待安装插件包的来源类别。安装管线据此区分「包从哪来、要不要按来源声明做完整性校验」。
 *
 * <ul>
 *   <li>{@link #LOCAL_UPLOAD}：管理员从本地选择并上传的 {@code .jar} / {@code .zip} 包。<b>当前唯一接入的来源。</b>
 *       本地包没有可信清单背书，故不带期望大小 / 哈希 / 签名，安装器只做结构与资源安全校验。</li>
 *   <li>{@link #MARKET_CATALOG}：由<b>受信插件目录元数据</b>驱动的来源（产品形态：用户在管理界面从官方插件目录按需
 *       获取插件）。其期望大小 / 哈希 / 签名一律来自受信目录清单，<b>绝不</b>接受用户输入的任意下载地址；下载、校验、
 *       落盘的本地框架边界见 {@link PluginPackageOrigin} 与 {@link PluginPackageIntegrity}。<b>当前未接入</b>——保留为
 *       后续受信目录获取流程的来源建模，本模块不发起任何网络访问。</li>
 * </ul>
 */
public enum PluginPackageSource {

    /** 管理员本地上传的插件包（当前唯一接入的来源）。 */
    LOCAL_UPLOAD,

    /** 受信插件目录元数据驱动的来源（保留建模，当前未接入；不接受任意 URL）。 */
    MARKET_CATALOG
}
