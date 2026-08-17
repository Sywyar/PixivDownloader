package top.sywyar.pixivdownload.plugin.api.schedule.work;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 单作品同步执行的安全结果；部分失败或关系未写入必须抛执行异常，不能返回已完成。机器码与属性不得包含
 * 原始凭据或可逆派生材料。
 *
 * <p>{@code liveStatusAvailable} 只声明当前结果允许宿主在展示队列时向同一作品执行器查询安全实时状态；
 * 它不携带状态内容，也不允许宿主据此解释某个插件的私有阶段或属性。
 */
public record ScheduledWorkResult(
        Outcome outcome,
        String resultCode,
        Map<String, String> attributes,
        boolean liveStatusAvailable
) {

    /** 允许携带的最大属性数量。 */
    public static final int MAX_ATTRIBUTES = 16;
    /** 单个属性值允许占用的最大 UTF-8 字节数。 */
    public static final int MAX_ATTRIBUTE_VALUE_BYTES = 4_096;
    /** 全部属性允许占用的最大 UTF-8 字节数。 */
    public static final int MAX_ATTRIBUTE_TOTAL_BYTES = 16_384;

    private static final Pattern MACHINE_CODE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");
    private static final Pattern ATTRIBUTE_KEY =
            Pattern.compile("[A-Za-z][A-Za-z0-9._-]{0,63}");

    /** 单作品同步执行结果的类别。 */
    public enum Outcome {
        /**
         * 表示 {@code COMPLETED} 状态。
         */
        COMPLETED,
        /**
         * 表示 {@code ALREADY_COMPLETED} 状态。
         */
        ALREADY_COMPLETED,
        /** 已跳过当前作品。 */
        SKIPPED
    }

    /**
     * 构造不需要实时状态叠加的结果。
     *
     * @param outcome 执行结果
     * @param resultCode 结果代码
     * @param attributes 属性
     */
    public ScheduledWorkResult(
            Outcome outcome,
            String resultCode,
            Map<String, String> attributes) {
        this(outcome, resultCode, attributes, false);
    }

    /**
     * 创建并校验单作品同步执行结果。
     *
     * @param outcome 执行结果类别
     * @param resultCode 结果机器码
     * @param attributes 安全属性
     * @param liveStatusAvailable 是否允许查询实时状态
     */
    public ScheduledWorkResult {
        if (outcome == null) {
            throw new IllegalArgumentException("work outcome must not be null");
        }
        if (resultCode == null || !MACHINE_CODE.matcher(resultCode.trim()).matches()) {
            throw new IllegalArgumentException("work result code must be a bounded machine code");
        }
        resultCode = resultCode.trim();
        attributes = validateAttributes(attributes);
    }

    /**
     * 返回已完成。
     *
     * @return 方法返回的 {@code ScheduledWorkResult} 实例
     */
    public static ScheduledWorkResult completed() {
        return new ScheduledWorkResult(
                Outcome.COMPLETED, "work.completed", Map.of(), false);
    }

    /**
     * 返回已经已完成。
     *
     * @return 方法返回的 {@code ScheduledWorkResult} 实例
     */
    public static ScheduledWorkResult alreadyCompleted() {
        return new ScheduledWorkResult(
                Outcome.ALREADY_COMPLETED, "work.already-completed", Map.of(), false);
    }

    private static Map<String, String> validateAttributes(Map<String, String> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.size() > MAX_ATTRIBUTES) {
            throw new IllegalArgumentException("work result attributes exceed count limit");
        }
        Map<String, String> copy = new LinkedHashMap<>();
        int totalBytes = 0;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()
                    || value == null || value.indexOf('\0') >= 0) {
                throw new IllegalArgumentException("work result attribute is invalid");
            }
            int keyBytes = key.getBytes(StandardCharsets.UTF_8).length;
            int valueBytes = value.getBytes(StandardCharsets.UTF_8).length;
            if (valueBytes > MAX_ATTRIBUTE_VALUE_BYTES) {
                throw new IllegalArgumentException("work result attribute exceeds size limit");
            }
            totalBytes = Math.addExact(totalBytes, Math.addExact(keyBytes, valueBytes));
            if (totalBytes > MAX_ATTRIBUTE_TOTAL_BYTES) {
                throw new IllegalArgumentException("work result attributes exceed total size limit");
            }
            copy.put(key, value);
        }
        return Map.copyOf(copy);
    }
}
