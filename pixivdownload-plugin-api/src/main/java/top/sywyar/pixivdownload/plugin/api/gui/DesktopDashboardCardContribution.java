package top.sywyar.pixivdownload.plugin.api.gui;

import java.time.Instant;
import java.util.Objects;

/**
 * 可选插件 publication 发布的一张独立控制中心指标卡纯值。
 *
 * <p>可信 owner 由宿主盖章；能力缺席、quiesce、替换或撤回时该卡片自然缺席。卡片只参与
 * best-effort 展示，不携带持久化行为、安全判断或跨 owner 聚合能力。
 */
public record DesktopDashboardCardContribution(
        String cardId,
        int order,
        DesktopUiText title,
        DesktopUiText primaryValue,
        DesktopUiText supportingText,
        DesktopUiTone tone,
        DesktopUiIcon icon,
        DesktopControlCenterAvailability availability,
        Instant observedAt
) {
    /**
     * 校验并规范化一张指标卡纯值。
     *
     * @param cardId owner 内稳定卡片 id
     * @param order owner 内排序值
     * @param title 卡片标题
     * @param primaryValue 主值
     * @param supportingText 辅助说明
     * @param tone 语义色调
     * @param icon 受控图标
     * @param availability 可用性
     * @param observedAt 事实观测时间
     */
    public DesktopDashboardCardContribution {
        cardId = requireId(cardId, "cardId");
        title = Objects.requireNonNull(title, "title");
        primaryValue = Objects.requireNonNull(primaryValue, "primaryValue");
        supportingText = Objects.requireNonNull(supportingText, "supportingText");
        tone = Objects.requireNonNull(tone, "tone");
        icon = Objects.requireNonNull(icon, "icon");
        availability = Objects.requireNonNull(availability, "availability");
        observedAt = Objects.requireNonNull(observedAt, "observedAt");
    }

    private static String requireId(String value, String name) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
            throw new IllegalArgumentException(name + " must be a stable id");
        }
        return value;
    }
}
