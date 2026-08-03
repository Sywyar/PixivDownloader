package top.sywyar.pixivdownload.download.state;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 调查去重决策状态。优先级固定为 {@code submitted > never > snoozed > null}，
 * 状态转移必须单调（见 {@link LayoutFeedbackStateStore}）。
 *
 * <p>线格式固定为小写 {@code submitted} / {@code never} / {@code snoozed}：
 * 序列化经 {@link #wireName()} 上的 {@link JsonValue} 输出小写，绝不依赖 Jackson
 * 默认大写枚举名；反序列化经 {@link #fromJson(String)}（{@link JsonCreator}）
 * 同时接受小写线格式与 f4d587b6 及更早版本写出的旧大写值，未知值直接抛
 * {@link IllegalArgumentException}。业务代码的 {@link #fromWire(String)} 保持
 * 「未知返回 null」语义不变。
 */
public enum LayoutFeedbackDecision {

    SUBMITTED("submitted"),
    NEVER("never"),
    SNOOZED("snoozed");

    private final String wireName;

    LayoutFeedbackDecision(String wireName) {
        this.wireName = wireName;
    }

    /** HTTP JSON 与持久化状态文件的线格式（小写 wire value）。 */
    @JsonValue
    public String wireName() {
        return wireName;
    }

    /** 按线格式解析；未知值返回 null（由调用方按 400 / 损坏处理）。 */
    public static LayoutFeedbackDecision fromWire(String wireName) {
        for (LayoutFeedbackDecision decision : values()) {
            if (decision.wireName.equals(wireName)) {
                return decision;
            }
        }
        return null;
    }

    /**
     * Jackson 专用反序列化入口：接受小写线格式与旧版大写值（大小写不敏感），
     * 未知值抛 {@link IllegalArgumentException}（由严格 ObjectReader 映射为 400 / 损坏）。
     */
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static LayoutFeedbackDecision fromJson(String value) {
        if (value == null) {
            throw new IllegalArgumentException("decision is required");
        }
        for (LayoutFeedbackDecision decision : values()) {
            if (decision.wireName.equalsIgnoreCase(value)) {
                return decision;
            }
        }
        throw new IllegalArgumentException("unknown decision: " + value);
    }
}
