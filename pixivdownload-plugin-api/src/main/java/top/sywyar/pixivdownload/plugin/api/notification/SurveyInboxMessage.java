package top.sywyar.pixivdownload.plugin.api.notification;

import top.sywyar.pixivdownload.plugin.api.web.WebUiSlotContribution;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 一封持久问卷站内信的纯值描述，并负责与现有 {@link WebUiSlotContribution} 稳定契约互转。
 * <p>
 * 标题与摘要保存 i18n key，HTML 正文由调查插件拥有的同源 {@link #contentUrl()} 提供；本契约不搬运
 * 原始 HTML、资源句柄、插件 Bean 或 ClassLoader。调查插件通过 {@code uiSlots()} 发布转换后的槽位，
 * 因此它是由发布插件负责 publication 生命周期的可选声明式展示贡献：贡献缺席、插件停用或卸载、
 * publication 换代时，消息随活动槽位快照自然撤回。宿主只复制纯值快照，quiesce 不回调插件，
 * 也没有需要等待排空的插件调用。通知能力缺席时贡献按 best-effort 忽略，不影响问卷页面与提交主流程。
 * <p>
 * 通知消费者可幂等保存消息、已读状态和不可用墓碑；撤回只隐藏活动消息并保留状态，复用同一
 * {@code instanceKey} 时恢复原状态，变更实例键后创建新的未读消息。同源地址仍需在消费边界复验，
 * 且发布插件必须为正文路由声明适当的访问级别（管理员问卷使用 {@code ADMIN}）。命中本目标和分类
 * 但字段无效的槽位会被拒绝并抛出异常，供消费者记录可诊断信息；不能因使用本便利封装而信任任意
 * metadata。
 *
 * @param messageKey    消息的稳定键；与 {@link #instanceKey()} 一起确定持久消息实例
 * @param instanceKey   本轮问卷实例键；变更后产生新的未读消息
 * @param contentUrl    调查插件自有 HTML 正文的同源绝对路径，可包含查询参数
 * @param i18nNamespace 标题与摘要所属的 i18n namespace
 * @param titleKey      站内信标题 i18n key
 * @param bodyKey       站内信列表摘要 i18n key
 * @param order         同类站内信的稳定排序值，越小越靠前
 */
public record SurveyInboxMessage(
        String messageKey,
        String instanceKey,
        String contentUrl,
        String i18nNamespace,
        String titleKey,
        String bodyKey,
        int order
) {

    private static final String TARGET = "notification.inbox";
    private static final String CATEGORY = "survey";
    private static final String CATEGORY_KEY = "notification.category";
    private static final String INSTANCE_KEY = "notification.instance-key";
    private static final String CONTENT_URL_KEY = "notification.embed-url";
    private static final String I18N_NAMESPACE_KEY = "notification.i18n-namespace";
    private static final String TITLE_KEY = "notification.title-key";
    private static final String BODY_KEY = "notification.body-key";
    private static final int MAX_CONTENT_URL_BYTES = 8 * 1_024;
    private static final Pattern MESSAGE_TOKEN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");
    private static final Pattern I18N_TOKEN = Pattern.compile("[a-z0-9][a-z0-9.-]{0,159}");

    /**
     * 创建 {@code SurveyInboxMessage} 实例。
     *
     * @param messageKey 消息键
     * @param instanceKey 实例键
     * @param contentUrl 内容地址
     * @param i18nNamespace 国际化命名空间
     * @param titleKey 标题键
     * @param bodyKey 正文键
     * @param order 排序值
     */
    public SurveyInboxMessage {
        messageKey = token(messageKey, MESSAGE_TOKEN, "survey inbox message key");
        instanceKey = token(instanceKey, MESSAGE_TOKEN, "survey inbox instance key");
        contentUrl = contentUrl(contentUrl);
        i18nNamespace = token(i18nNamespace, I18N_TOKEN, "survey inbox i18n namespace");
        titleKey = token(titleKey, I18N_TOKEN, "survey inbox title key");
        bodyKey = token(bodyKey, I18N_TOKEN, "survey inbox body key");
    }

    /**
     * 编码为现有 UI 槽位贡献，保留原有 owner 与 publication 生命周期。
     *
     * @return 方法返回的 {@code WebUiSlotContribution} 实例
     */
    public WebUiSlotContribution toUiSlotContribution() {
        return new WebUiSlotContribution(
                messageKey,
                TARGET,
                null,
                order,
                Map.of(
                        CATEGORY_KEY, CATEGORY,
                        INSTANCE_KEY, instanceKey,
                        CONTENT_URL_KEY, contentUrl,
                        I18N_NAMESPACE_KEY, i18nNamespace,
                        TITLE_KEY, titleKey,
                        BODY_KEY, bodyKey));
    }

    /**
     * 从活动 UI 槽位解码问卷站内信。非本目标或分类返回空；目标问卷字段缺失或不安全时抛出异常。
     *
     * @param slot 界面槽位
     * @return 匹配的可选值
     */
    public static Optional<SurveyInboxMessage> fromUiSlotContribution(WebUiSlotContribution slot) {
        if (slot == null || !TARGET.equals(slot.target())) {
            return Optional.empty();
        }
        Map<String, String> metadata = slot.metadata();
        if (!CATEGORY.equals(metadata.get(CATEGORY_KEY))) {
            return Optional.empty();
        }
        try {
            return Optional.of(new SurveyInboxMessage(
                    slot.slotId(),
                    metadata.get(INSTANCE_KEY),
                    metadata.get(CONTENT_URL_KEY),
                    metadata.get(I18N_NAMESPACE_KEY),
                    metadata.get(TITLE_KEY),
                    metadata.get(BODY_KEY),
                    slot.order()));
        } catch (NullPointerException exception) {
            throw new IllegalArgumentException("survey inbox contribution is incomplete", exception);
        }
    }

    private static String token(String value, Pattern pattern, String field) {
        String token = Objects.requireNonNull(value, field);
        if (!pattern.matcher(token).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
        return token;
    }

    private static String contentUrl(String value) {
        String normalized = Objects.requireNonNull(value, "survey inbox content URL").trim();
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_CONTENT_URL_BYTES
                || normalized.indexOf('\0') >= 0 || normalized.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("survey inbox content URL is invalid");
        }
        try {
            URI uri = new URI(normalized);
            if (normalized.startsWith("/") && !normalized.startsWith("//")
                    && !uri.isAbsolute() && uri.getRawAuthority() == null
                    && uri.normalize().toString().equals(normalized)) {
                return normalized;
            }
        } catch (URISyntaxException ignored) {
            // 统一在下方拒绝。
        }
        throw new IllegalArgumentException("survey inbox content URL must be a same-origin absolute path");
    }
}
