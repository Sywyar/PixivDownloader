package top.sywyar.pixivdownload.download.response;

/**
 * 当前 cookie 主人的 Pixiv userId（从 PHPSESSID 前缀解析）。
 * 供前端「快捷获取账户相关作品」面板首次进入时绑定到查询参数。
 */
public record MeUidResponse(String uid) {
}
