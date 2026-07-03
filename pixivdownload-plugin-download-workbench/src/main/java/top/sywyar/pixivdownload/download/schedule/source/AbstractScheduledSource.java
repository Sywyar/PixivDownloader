package top.sywyar.pixivdownload.download.schedule.source;

import top.sywyar.pixivdownload.core.schedule.ScheduledTaskType;

import java.util.Locale;
import java.util.Set;

/**
 * 内置来源的身份样板：规范 {@code type} 由 {@link ScheduledTaskType} 枚举名机械派生为小写短横线
 * （{@code USER_NEW} → {@code user-new}）；{@code legacyTypeNames} 即枚举名本身
 * （{@code scheduled_tasks.type} 列经 MyBatis 默认 EnumTypeHandler 落库的现存值），供调度器把存量任务的
 * 存量 type 兼容映射回本来源。覆盖 7 个枚举值全集，旧任务数据零迁移即可解析。
 */
public abstract class AbstractScheduledSource implements ScheduledSource {

    private final ScheduledTaskType legacyType;
    private final String type;

    protected AbstractScheduledSource(ScheduledTaskType legacyType) {
        this.legacyType = legacyType;
        this.type = legacyType.name().toLowerCase(Locale.ROOT).replace('_', '-');
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Set<String> legacyTypeNames() {
        return Set.of(legacyType.name());
    }
}
