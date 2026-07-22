package top.sywyar.pixivdownload.download.schedule.source.descriptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.download.type.DownloadAcquisitionMode;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.schedule.persistence.PixivSchedulePersistenceCodec;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Pixiv 计划任务来源描述符")
class PixivScheduledSourceDescriptorsTest {

    private static final Set<String> ILLUST_ONLY = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST);
    private static final Set<String> ILLUST_OR_NOVEL = Set.of(
            PixivSchedulePersistenceCodec.WORK_TYPE_ILLUST,
            PixivSchedulePersistenceCodec.WORK_TYPE_NOVEL);

    @Test
    @DisplayName("七类来源按固定顺序声明完整 canonical 与 legacy 身份")
    void declaresCanonicalTypesAndLegacyAliases() {
        List<ScheduledSourceDescriptor> descriptors = PixivScheduledSourceDescriptors.createAll();

        assertThat(descriptors)
                .extracting(ScheduledSourceDescriptor::sourceType)
                .containsExactly(
                        "user-new", "user-request", "search", "series",
                        "my-bookmarks", "follow-latest", "collection");
        assertThat(descriptors)
                .extracting(ScheduledSourceDescriptor::legacyAliases)
                .containsExactly(
                        Set.of("USER_NEW"), Set.of("USER_REQUEST"), Set.of("SEARCH"),
                        Set.of("SERIES"), Set.of("MY_BOOKMARKS"), Set.of("FOLLOW_LATEST"),
                        Set.of("COLLECTION"));
    }

    @Test
    @DisplayName("每类来源声明准确作品类型与取得模式")
    void declaresWorkTypesAndAcquisitionModes() {
        Map<String, Expected> expected = new LinkedHashMap<>();
        expected.put("user-new", new Expected(
                ILLUST_OR_NOVEL,
                Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code())));
        expected.put("user-request", new Expected(
                ILLUST_ONLY,
                Set.of(DownloadAcquisitionMode.USER_PROFILE.code(), DownloadAcquisitionMode.QUICK.code())));
        expected.put("search", new Expected(
                ILLUST_OR_NOVEL,
                Set.of(DownloadAcquisitionMode.SEARCH.code())));
        expected.put("series", new Expected(
                ILLUST_OR_NOVEL,
                Set.of(DownloadAcquisitionMode.SERIES_COLLECTION.code())));
        expected.put("my-bookmarks", new Expected(
                ILLUST_OR_NOVEL,
                Set.of(DownloadAcquisitionMode.QUICK.code())));
        expected.put("follow-latest", new Expected(
                ILLUST_ONLY,
                Set.of(DownloadAcquisitionMode.QUICK.code())));
        expected.put("collection", new Expected(
                ILLUST_OR_NOVEL,
                Set.of(DownloadAcquisitionMode.QUICK.code())));

        assertThat(PixivScheduledSourceDescriptors.createAll()).allSatisfy(descriptor -> {
            Expected source = expected.get(descriptor.sourceType());
            assertThat(source).isNotNull();
            assertThat(descriptor.possibleWorkTypes()).isEqualTo(source.workTypes());
            assertThat(descriptor.acquisitionModes()).isEqualTo(source.acquisitionModes());
        });
    }

    @Test
    @DisplayName("全部来源共享正式定义、Pixiv Cookie 与过度访问 Guard 契约")
    void declaresDefinitionCredentialAndGuardContracts() {
        assertThat(PixivScheduledSourceDescriptors.createAll()).allSatisfy(descriptor -> {
            assertThat(descriptor.definitionSchema())
                    .isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_SCHEMA);
            assertThat(descriptor.definitionVersion())
                    .isEqualTo(PixivSchedulePersistenceCodec.DEFINITION_VERSION);
            assertThat(descriptor.credentialPolicyIds())
                    .containsExactly(PixivSchedulePersistenceCodec.CREDENTIAL_POLICY_ID);
            assertThat(descriptor.guardIds())
                    .containsExactly(PixivScheduledSourceDescriptors.OVERUSE_GUARD_ID);
            assertThat(descriptor.presentation().displayNamespace()).isEqualTo("batch");
            assertThat(descriptor.presentation().displayNameKey())
                    .isEqualTo("schedule.type." + descriptor.sourceType());
            assertThat(descriptor.presentation().descriptionKey())
                    .isEqualTo(descriptor.presentation().displayNameKey());
            assertThat(descriptor.presentation().iconKey()).isEqualTo("schedule");
            assertThat(descriptor.presentation().colorToken()).isEqualTo("pixiv");
            assertThat(descriptor.frontend()).isEqualTo(
                    new ScheduledSourceFrontendContribution(
                            ScheduledSourceFrontendContribution.CURRENT_CONTRACT_VERSION,
                            PixivScheduledSourceDescriptors.FRONTEND_MODULE_URL));
        });
    }

    private record Expected(Set<String> workTypes, Set<String> acquisitionModes) {
    }
}
