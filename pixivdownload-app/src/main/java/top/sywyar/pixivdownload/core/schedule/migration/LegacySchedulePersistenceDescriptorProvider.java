package top.sywyar.pixivdownload.core.schedule.migration;

import java.util.List;

/**
 * 独立于迁移 adapter 的旧来源持久化元数据 bridge。
 *
 * <p>registrar 在锁外读取并由 owner bundle 校验、盖章；adapter 不能通过单条迁移结果改变该契约。
 */
@FunctionalInterface
public interface LegacySchedulePersistenceDescriptorProvider {

    List<LegacySchedulePersistenceDescriptor> legacySchedulePersistenceDescriptors();
}
