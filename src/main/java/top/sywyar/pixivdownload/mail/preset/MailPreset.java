package top.sywyar.pixivdownload.mail.preset;

import top.sywyar.pixivdownload.mail.MailSecurity;

/**
 * 单个 SMTP 服务商预设。
 *
 * @param id                预设标识（小写连字符），如 {@code netease-163}、{@code gmail}、{@code custom}
 * @param displayNameKey    显示名 i18n key（如 {@code mail.preset.name.netease-163}）
 * @param host              SMTP 主机；{@code custom} 哨兵下为空
 * @param port              SMTP 端口；{@code custom} 哨兵下为 0
 * @param security          加密方式；{@code custom} 哨兵下任意取值（GUI 会解锁）
 * @param credentialHelpKey 凭证提示 i18n key（说明使用授权码 / 应用专用密码 / 等）
 * @param oauthWarning      是否带 OAuth 强制警告（Microsoft 365 / Google Workspace）
 */
public record MailPreset(
        String id,
        String displayNameKey,
        String host,
        int port,
        MailSecurity security,
        String credentialHelpKey,
        boolean oauthWarning
) {

    /** {@code custom} 哨兵 id。GUI 选中它时解锁 host / port / security 三项。 */
    public static final String CUSTOM_ID = "custom";

    /** 是否是"自定义"哨兵（host/port/security 不锁定）。 */
    public boolean isCustom() {
        return CUSTOM_ID.equals(id);
    }
}
