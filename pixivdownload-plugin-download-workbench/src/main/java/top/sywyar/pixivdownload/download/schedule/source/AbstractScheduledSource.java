package top.sywyar.pixivdownload.download.schedule.source;

import java.util.Set;

/**
 * 内置来源的身份样板：规范 {@code type} 使用插件拥有的 canonical 字符串；{@code legacyTypeNames}
 * 保留旧枚举落库名
 * （{@code scheduled_tasks.type} 列经 MyBatis 默认 EnumTypeHandler 落库的现存值），供调度器把存量任务的
 * 存量 type 兼容映射回本来源。覆盖 7 个枚举值全集，旧任务数据零迁移即可解析。
 */
public abstract class AbstractScheduledSource implements ScheduledSource {

    private final String legacyType;
    private final String type;

    protected AbstractScheduledSource(String type, String legacyType) {
        this.legacyType = legacyType;
        this.type = type;
    }

    @Override
    public String type() {
        return type;
    }

    @Override
    public Set<String> legacyTypeNames() {
        return Set.of(legacyType);
    }
}
