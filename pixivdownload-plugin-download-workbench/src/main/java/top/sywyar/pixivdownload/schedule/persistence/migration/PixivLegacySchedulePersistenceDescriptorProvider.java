package top.sywyar.pixivdownload.schedule.persistence.migration;

import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptor;
import top.sywyar.pixivdownload.core.schedule.migration.LegacySchedulePersistenceDescriptorProvider;
import top.sywyar.pixivdownload.plugin.api.plugin.PluginManagedBean;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 独立声明七类旧 Pixiv 来源可安全迁移到的定义与作品持久化契约。 */
@PluginManagedBean
public final class PixivLegacySchedulePersistenceDescriptorProvider
        implements LegacySchedulePersistenceDescriptorProvider {

    private static final Set<String> ILLUST_ONLY = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST);
    private static final Set<String> ILLUST_OR_NOVEL = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
            PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL);

    @Override
    public List<LegacySchedulePersistenceDescriptor> legacySchedulePersistenceDescriptors() {
        List<LegacySchedulePersistenceDescriptor> descriptors = new ArrayList<>();
        for (Map.Entry<String, String> route
                : PixivSchedulePersistenceCodec.legacySourceAliases().entrySet()) {
            Set<String> possibleWorkTypes = "USER_REQUEST".equals(route.getKey())
                    || "FOLLOW_LATEST".equals(route.getKey())
                    ? ILLUST_ONLY : ILLUST_OR_NOVEL;
            descriptors.add(new LegacySchedulePersistenceDescriptor(
                    route.getValue(),
                    PixivSchedulePersistenceCodec.DEFINITION_SCHEMA,
                    PixivSchedulePersistenceCodec.DEFINITION_VERSION,
                    possibleWorkTypes,
                    Set.of(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID)));
        }
        return List.copyOf(descriptors);
    }
}
