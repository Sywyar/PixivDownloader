package top.sywyar.pixivdownload.schedule.source;

import top.sywyar.pixivdownload.plugin.api.schedule.ScheduledSourceProvider;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 把现有 {@link ScheduledTaskType} 枚举值适配为 {@link ScheduledSourceProvider}。
 *
 * <p>仅承载来源的「身份 + legacy 映射」：
 * <ul>
 *   <li>规范 {@link #type()} 由枚举名机械派生为小写短横线形式（{@code USER_NEW} → {@code user-new}）；</li>
 *   <li>{@link #legacyTypeNames()} 即枚举名本身（{@code scheduled_tasks.type} 列现存值，
 *       经 MyBatis 默认 EnumTypeHandler 落库），供调度器兼容映射存量任务。</li>
 * </ul>
 * 来源的发现 / 派发运行语义仍由 {@code ScheduleExecutor} 的枚举分支承载、不在本类表达，
 * 故本类不持有任何执行依赖。
 */
public final class EnumScheduledSourceProvider implements ScheduledSourceProvider {

    private final ScheduledTaskType legacyType;
    private final String type;

    public EnumScheduledSourceProvider(ScheduledTaskType legacyType) {
        this.legacyType = legacyType;
        this.type = legacyType.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    /**
     * 现有每个 {@link ScheduledTaskType} 枚举值各包装为一个 provider。
     * 覆盖 {@code values()} 全集（含未来新增枚举值），保证不漏类型。
     */
    public static List<ScheduledSourceProvider> builtIn() {
        return Arrays.stream(ScheduledTaskType.values())
                .map(EnumScheduledSourceProvider::new)
                .map(ScheduledSourceProvider.class::cast)
                .toList();
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Set<String> legacyTypeNames() {
        return Set.of(legacyType.name());
    }

    /** 本 provider 包装的枚举值（schedule 包内部用；不经 plugin.api 契约暴露）。 */
    public ScheduledTaskType legacyType() {
        return legacyType;
    }
}
