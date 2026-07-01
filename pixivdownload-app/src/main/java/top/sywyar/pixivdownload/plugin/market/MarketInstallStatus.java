package top.sywyar.pixivdownload.plugin.market;

import top.sywyar.pixivdownload.plugin.management.PluginStatusService;

/**
 * 市场条目的安装状态（稳定机器码，与界面语言无关；前端按机器语义分支、文案另走 i18n）。由后端把受信 catalog 条目与
 * <b>真实运行时安装状态</b>（{@link top.sywyar.pixivdownload.plugin.management.PluginStatusService} 只读投影）交叉引用推导，<b>不</b>用
 * 前端假状态掩盖后端事实。
 *
 * <ul>
 *   <li>{@link #NOT_INSTALLED} —— 本机未安装该插件，且其最新可安装版本兼容当前核心 API（可安装）。</li>
 *   <li>{@link #INSTALLED} —— 本机已安装且为最新版本（无<b>严格更高</b>的可用更新）。</li>
 *   <li>{@link #UPDATE_AVAILABLE} —— 本机已安装、市场存在<b>严格更高且兼容</b>的版本（更新走事务化替换并即时激活，
 *       <b>非</b>热升级）。版本高低按 {@link top.sywyar.pixivdownload.common.SemanticVersion} 语义比较，语义等价
 *       （如 {@code 1.2} 与 {@code 1.2.0}）不算更新，本机版本更高时保持已安装。</li>
 *   <li>{@link #INCOMPATIBLE} —— 本机未安装且最新可安装版本声明的核心 API 要求不被当前核心满足（需先升级应用）。</li>
 *   <li>{@link #UNAVAILABLE} —— 本机未安装且该条目<b>没有任何可安装版本制品</b>（清单未提供可下载的版本）：稳定降级为
 *       不可安装状态，前端不渲染可点击但无响应的安装按钮。</li>
 * </ul>
 *
 * <p>「安装中」是<b>前端本地请求态</b>（安装 POST 在途），不在本枚举——安装结果一律以后端响应为准。安装只下载 + 校验 +
 * 落盘，<b>重启后</b>才被加载，故安装成功后该条目要到下次重启才会从未安装翻转为已安装（前端展示「待重启生效」、不伪造热加载）。
 */
public enum MarketInstallStatus {

    NOT_INSTALLED,
    INSTALLED,
    UPDATE_AVAILABLE,
    INCOMPATIBLE,
    UNAVAILABLE;

    /**
     * 据「是否已安装 / 是否有可安装版本 / 是否有严格更高的兼容更新 / 最新可安装版本是否兼容」推导安装状态。已安装时
     * 有严格更高的兼容更新→有更新、否则→已安装（最新 / 本机更高 / 无更新都收敛为已安装，当前版本仍可用）；未安装时无可安装
     * 版本→不可安装，否则兼容→可安装、不兼容→需升级应用。
     */
    static MarketInstallStatus resolve(boolean installed, boolean installable, boolean updateAvailable,
                                       boolean compatible) {
        if (installed) {
            return updateAvailable ? UPDATE_AVAILABLE : INSTALLED;
        }
        if (!installable) {
            return UNAVAILABLE;
        }
        return compatible ? NOT_INSTALLED : INCOMPATIBLE;
    }
}
