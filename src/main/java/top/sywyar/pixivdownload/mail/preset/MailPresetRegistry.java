package top.sywyar.pixivdownload.mail.preset;

import org.springframework.stereotype.Component;
import top.sywyar.pixivdownload.mail.MailSecurity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * SMTP 服务商预设的唯一事实源。
 * <p>
 * 列表顺序即 GUI 下拉框顺序：常见个人邮箱 → 企业邮箱 → 自定义哨兵。
 * <p>
 * GUI 选中某预设时锁定 host / port / security 三项；选中 {@link MailPreset#CUSTOM_ID} 解锁。
 * 预设本身不进 {@code config.yaml}（配置语义仍是 host/port/security），加载时按已存 host 反查锁定。
 */
@Component
public class MailPresetRegistry {

    private final List<MailPreset> presets;
    private final Map<String, MailPreset> byId;

    public MailPresetRegistry() {
        this.presets = List.of(
                preset("netease-163", "smtp.163.com", 465, MailSecurity.SSL,
                        "mail.preset.help.authcode", false),
                preset("netease-126", "smtp.126.com", 465, MailSecurity.SSL,
                        "mail.preset.help.authcode", false),
                preset("netease-yeah", "smtp.yeah.net", 465, MailSecurity.SSL,
                        "mail.preset.help.authcode", false),
                preset("qq", "smtp.qq.com", 465, MailSecurity.SSL,
                        "mail.preset.help.authcode", false),
                preset("sina", "smtp.sina.com", 465, MailSecurity.SSL,
                        "mail.preset.help.authcode", false),
                preset("gmail", "smtp.gmail.com", 587, MailSecurity.STARTTLS,
                        "mail.preset.help.app-password", false),
                preset("outlook", "smtp-mail.outlook.com", 587, MailSecurity.STARTTLS,
                        "mail.preset.help.app-password", false),
                preset("icloud", "smtp.mail.me.com", 587, MailSecurity.STARTTLS,
                        "mail.preset.help.app-password", false),
                preset("yahoo", "smtp.mail.yahoo.com", 465, MailSecurity.SSL,
                        "mail.preset.help.app-password", false),
                preset("netease-qiye", "smtp.qiye.163.com", 465, MailSecurity.SSL,
                        "mail.preset.help.netease-qiye", false),
                preset("tencent-exmail", "smtp.exmail.qq.com", 465, MailSecurity.SSL,
                        "mail.preset.help.tencent-exmail", false),
                preset("aliyun-qiye", "smtp.qiye.aliyun.com", 465, MailSecurity.SSL,
                        "mail.preset.help.aliyun-qiye", false),
                preset("ms365", "smtp.office365.com", 587, MailSecurity.STARTTLS,
                        "mail.preset.help.ms365", true),
                preset("google-workspace", "smtp.gmail.com", 587, MailSecurity.STARTTLS,
                        "mail.preset.help.google-workspace", true),
                new MailPreset(MailPreset.CUSTOM_ID,
                        "mail.preset.name." + MailPreset.CUSTOM_ID,
                        "", 0, MailSecurity.STARTTLS,
                        "mail.preset.help.custom", false)
        );

        Map<String, MailPreset> map = new LinkedHashMap<>();
        for (MailPreset preset : presets) {
            if (map.put(preset.id(), preset) != null) {
                throw new IllegalStateException("duplicate mail preset id: " + preset.id());
            }
        }
        this.byId = Map.copyOf(map);
    }

    /** 全部预设（包含末尾 {@code custom} 哨兵），按 GUI 下拉顺序。 */
    public List<MailPreset> all() {
        return presets;
    }

    public Optional<MailPreset> findById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(byId.get(id.trim().toLowerCase(Locale.ROOT)));
    }

    /**
     * 按 host 反查预设。匹配规则：第一个 host 与之相等（忽略大小写）的非 custom 预设。
     * <p>
     * GUI 加载已有 config.yaml 时用此方法推断当前是哪一个预设；未命中时 GUI 应落到 {@code custom}。
     */
    public Optional<MailPreset> findByHost(String host) {
        if (host == null || host.isBlank()) {
            return Optional.empty();
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        return presets.stream()
                .filter(p -> !p.isCustom())
                .filter(p -> p.host().equalsIgnoreCase(normalized))
                .findFirst();
    }

    /** {@code custom} 哨兵；列表末尾。 */
    public MailPreset custom() {
        return byId.get(MailPreset.CUSTOM_ID);
    }

    private static MailPreset preset(String id, String host, int port, MailSecurity security,
                                     String credentialHelpKey, boolean oauthWarning) {
        return new MailPreset(id, "mail.preset.name." + id, host, port, security,
                credentialHelpKey, oauthWarning);
    }
}
