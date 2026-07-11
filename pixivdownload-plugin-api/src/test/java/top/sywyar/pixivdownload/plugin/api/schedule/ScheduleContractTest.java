package top.sywyar.pixivdownload.plugin.api.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通用计划任务纯 JDK 契约")
class ScheduleContractTest {

    @Test
    @DisplayName("作品身份保真保存前导零、长数字、UUID 与作品类型")
    void workIdentityIsOpaqueAndTypeScoped() {
        ScheduledWorkKey leadingZero = new ScheduledWorkKey("douyin:video", "001");
        ScheduledWorkKey whitespaceId = new ScheduledWorkKey(" douyin:video ", " 001 ");
        ScheduledWorkKey plain = new ScheduledWorkKey("douyin:video", "1");
        ScheduledWorkKey sameIdOtherType = new ScheduledWorkKey("douyin:gallery", "001");
        ScheduledWorkKey longNumeric = new ScheduledWorkKey("douyin:video", "1234567890123456789012345");
        ScheduledWorkKey uuid = new ScheduledWorkKey("douyin:video", "5ed5bbcf-7b5e-4a37-9925-f23e4b6de621");

        assertThat(leadingZero.id()).isEqualTo("001");
        assertThat(whitespaceId.workType()).isEqualTo("douyin:video");
        assertThat(whitespaceId.id()).isEqualTo(" 001 ");
        assertThat(leadingZero).isNotEqualTo(plain).isNotEqualTo(sameIdOtherType);
        assertThat(longNumeric.id()).hasSize(25);
        assertThat(uuid.id()).contains("-");
    }

    @Test
    @DisplayName("作品信封复制展示、关系与载荷并保持不可变")
    void workEnvelopeIsPersistentAndImmutable() {
        Map<String, String> attributes = new LinkedHashMap<>();
        attributes.put("kind", "live-photo");
        List<ScheduledWorkRelation> relations = new ArrayList<>();
        relations.add(new ScheduledWorkRelation(
                "douyin:collection", "mix-9", "douyin:relation", 1, "{\"order\":7}"));

        ScheduledWork work = new ScheduledWork(
                new ScheduledWorkKey("douyin:work", "001"),
                "douyin:work",
                2,
                "{\"awemeId\":\"001\"}",
                new ScheduledWorkPresentation("标题", "作者", "/thumb/001", attributes),
                relations);

        attributes.put("kind", "changed");
        relations.clear();

        assertThat(work.key().id()).isEqualTo("001");
        assertThat(work.presentation().attributes()).containsEntry("kind", "live-photo");
        assertThat(work.relations()).hasSize(1);
        assertThatThrownBy(() -> work.relations().add(null))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("来源 descriptor 只含纯元数据且复制所有集合")
    void sourceDescriptorIsPureMetadata() {
        Set<String> aliases = new LinkedHashSet<>(Set.of("USER_NEW"));
        ScheduledSourceDescriptor descriptor = new ScheduledSourceDescriptor(
                "pixiv:user-new",
                aliases,
                "pixiv:user-source",
                1,
                new ScheduledSourcePresentation("batch", "schedule.user", "schedule.user.help", "user", "blue"),
                Set.of("user"),
                Set.of("pixiv:illust", "pixiv:novel"),
                Set.of("pixiv:cookie"),
                Set.of("pixiv:overuse"),
                new ScheduledSourceFrontendContribution(1, "/pixiv-batch/schedule/pixiv-user.js"));

        aliases.clear();

        assertThat(descriptor.sourceType()).isEqualTo("pixiv:user-new");
        assertThat(descriptor.legacyAliases()).containsExactly("USER_NEW");
        assertThat(ScheduledSourceDescriptor.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("pluginId", "packageId", "generation");
        assertThatThrownBy(() -> new ScheduledSourceFrontendContribution(
                1, "/\\evil.example/external.js"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledSourceFrontendContribution(
                1, "/plugin-assets/../external.js"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("执行计划声明作品、凭证、Guard、检查点与有界并发")
    void executionPlanCarriesHostControlledPolicy() {
        ScheduledGuardBinding guard = new ScheduledGuardBinding(
                "pixiv:overuse",
                Set.of(ScheduledGuardPoint.RUN_START, ScheduledGuardPoint.WORK_BATCH, ScheduledGuardPoint.RUN_END),
                500);
        ScheduledExecutionPlan plan = new ScheduledExecutionPlan(
                Set.of("pixiv:illust"),
                "pixiv:cookie",
                ScheduledCredentialRequirement.OPTIONAL,
                true,
                List.of(guard),
                "pixiv:watermark",
                1,
                4,
                1_000L);

        assertThat(plan.guards()).containsExactly(guard);
        assertThat(plan.maxInFlight()).isEqualTo(4);
        assertThatThrownBy(() -> new ScheduledGuardBinding(
                "guard", Set.of(ScheduledGuardPoint.WORK_BATCH), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("网络路由不把代理细节混入直连或继承模式")
    void networkRouteKeepsModesExplicit() {
        ScheduledNetworkRoute proxy = ScheduledNetworkRoute.proxy("127.0.0.1", 7890, "proxy-main");
        ScheduledNetworkRoute inherited = ScheduledNetworkRoute.inherit();

        assertThat(proxy.mode()).isEqualTo(ScheduledNetworkRoute.Mode.PROXY);
        assertThat(proxy.proxyCredentialReference()).isEqualTo("proxy-main");
        assertThat(ScheduledNetworkRoute.direct().proxyHost()).isNull();
        assertThat(inherited.isResolved()).isFalse();
        assertThat(inherited.resolveAgainst(proxy)).isSameAs(proxy);
        assertThatThrownBy(() -> inherited.resolveAgainst(ScheduledNetworkRoute.inherit()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledNetworkRoute(
                ScheduledNetworkRoute.Mode.DIRECT, "127.0.0.1", 7890, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("执行失败投影不携带插件 Throwable 或自由诊断文本")
    void executionFailureIsSafeDataOnly() {
        ScheduledExecutionException exception = new ScheduledExecutionException(
                ScheduledFailure.Category.RETRYABLE_NETWORK,
                "network.timeout",
                2_000L);

        assertThat(exception.getCause()).isNull();
        assertThat(exception.toFailure()).isEqualTo(new ScheduledFailure(
                ScheduledFailure.Category.RETRYABLE_NETWORK,
                "network.timeout",
                2_000L));
        assertThat(ScheduledFailure.class.getRecordComponents())
                .extracting(component -> component.getName())
                .doesNotContain("message", "throwable", "cause");
    }

    @Test
    @DisplayName("Guard 的继续动作不携失败信息")
    void guardContinueIsDataOnly() {
        assertThat(ScheduledGuardDecision.proceed().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        assertThatThrownBy(() -> new ScheduledGuardDecision(
                ScheduledGuardDecision.Action.CONTINUE, "unexpected", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
