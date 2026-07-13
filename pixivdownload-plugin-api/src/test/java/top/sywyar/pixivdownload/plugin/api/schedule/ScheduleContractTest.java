package top.sywyar.pixivdownload.plugin.api.schedule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialHandle;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialRequirement;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledCancellation;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionPlan;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledExecutionGuard;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardBinding;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardContext;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardEvidence;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardPoint;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.network.ScheduledNetworkRoute;
import top.sywyar.pixivdownload.plugin.api.schedule.security.ScheduledSensitiveFieldNames;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledCheckpoint;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledDiscoveryResult;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledPendingReplayPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceContext;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceFrontendContribution;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourcePresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskDefinition;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledTaskPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledWorkSink;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWork;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkExecutor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRelation;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkResult;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunContext;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkRunStatistics;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("通用计划任务纯 JDK 契约")
class ScheduleContractTest {

    @Test
    @DisplayName("旧凭证策略的绑定探活默认方法只委托一次并要求 BIND purpose")
    void legacyCredentialPolicyUsesSingleBindProbe() throws Exception {
        AtomicInteger probes = new AtomicInteger();
        ScheduledCredentialPolicy policy = new ScheduledCredentialPolicy() {
            @Override
            public String policyId() {
                return "fixture:credential";
            }

            @Override
            public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                probes.incrementAndGet();
                return ScheduledCredentialProbeResult.valid("account-1");
            }
        };
        ScheduledCredentialContext bindContext = credentialContext(
                ScheduledCredentialContext.Purpose.BIND);

        ScheduledCredentialBindResult result = policy.probeForBinding(bindContext);

        assertThat(result.probeResult().status())
                .isEqualTo(ScheduledCredentialProbeResult.Status.VALID);
        assertThat(result.initialPolicyStateJson()).isEqualTo("{}");
        assertThat(result.postBindResult().decision().action())
                .isEqualTo(ScheduledGuardDecision.Action.CONTINUE);
        assertThat(probes).hasValue(1);
        assertThat(ScheduledCredentialPolicy.class
                .getMethod("probeForBinding", ScheduledCredentialContext.class)
                .isDefault()).isTrue();
        assertThatThrownBy(() -> policy.probeForBinding(credentialContext(
                ScheduledCredentialContext.Purpose.RUN_START)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(probes).hasValue(1);
    }

    @Test
    @DisplayName("绑定结果只允许有效凭证携带任务级策略挂起")
    void credentialBindResultRestrictsPostBindAction() {
        ScheduledGuardResult taskSuspend = new ScheduledGuardResult(
                new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK,
                        "fixture.warning", 0L),
                new ScheduledGuardEvidence(Map.of("excerpt", "safe")));

        ScheduledCredentialBindResult result = new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid("account-1"),
                "{\"schema\":\"fixture.state\",\"version\":1}",
                taskSuspend);

        assertThat(result.postBindResult()).isSameAs(taskSuspend);
        assertThatThrownBy(() -> new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.invalid("fixture.invalid"),
                "{}", taskSuspend))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid("account-1"),
                "{}",
                ScheduledGuardResult.decision(new ScheduledGuardDecision(
                        ScheduledGuardDecision.Action.SUSPEND_POLICY_ACCOUNT,
                        "fixture.warning", 0L))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledCredentialBindResult(
                ScheduledCredentialProbeResult.valid("account-1"),
                "x".repeat(ScheduledCredentialBindResult.MAX_POLICY_STATE_BYTES + 1),
                null))
                .isInstanceOf(IllegalArgumentException.class);
    }

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
    @DisplayName("作品展示、关系与执行结果在 API 边界限制数量和 UTF-8 大小")
    void workPresentationRelationsAndResultAreBounded() {
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "题".repeat(ScheduledWorkPresentation.MAX_TITLE_BYTES),
                null, null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> tooManyAttributes = new LinkedHashMap<>();
        for (int i = 0; i <= ScheduledWorkPresentation.MAX_ATTRIBUTES; i++) {
            tooManyAttributes.put("key" + i, "value");
        }
        assertThatThrownBy(() -> new ScheduledWorkPresentation(
                "title", null, null, tooManyAttributes))
                .isInstanceOf(IllegalArgumentException.class);

        ScheduledWorkRelation relation = new ScheduledWorkRelation(
                "source", "id", "fixture.relation", 1, "{}");
        List<ScheduledWorkRelation> tooManyRelations = new ArrayList<>();
        for (int i = 0; i <= ScheduledWork.MAX_RELATIONS; i++) {
            tooManyRelations.add(relation);
        }
        assertThatThrownBy(() -> new ScheduledWork(
                new ScheduledWorkKey("fixture", "id"), "fixture.work", 1, "{}",
                ScheduledWorkPresentation.empty(), tooManyRelations))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() -> new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.COMPLETED,
                "not a machine code",
                Map.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.COMPLETED,
                "fixture.completed",
                Map.of("detail", "值".repeat(ScheduledWorkResult.MAX_ATTRIBUTE_VALUE_BYTES))))
                .isInstanceOf(IllegalArgumentException.class);
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

    @Test
    @DisplayName("旧作品执行器通过默认轮末与异常清理方法保持兼容")
    void legacyWorkExecutorUsesDefaultRunFinalizer() throws Exception {
        ScheduledWorkExecutor executor = new ScheduledWorkExecutor() {
            @Override
            public String workType() {
                return "fixture:work";
            }

            @Override
            public ScheduledWorkResult execute(ScheduledWork work, ScheduledWorkContext context) {
                return ScheduledWorkResult.completed();
            }
        };

        executor.finishRun(null);
        executor.abortRun(null);

        assertThat(executor.maxConcurrency()).isEqualTo(Integer.MAX_VALUE);
        assertThat(ScheduledWorkExecutor.class
                .getMethod("maxConcurrency")
                .isDefault()).isTrue();
        assertThat(ScheduledWorkExecutor.class
                .getMethod("finishRun", ScheduledWorkRunContext.class)
                .isDefault()).isTrue();
        assertThat(ScheduledWorkExecutor.class
                .getMethod("abortRun", ScheduledTaskDefinition.class)
                .isDefault()).isTrue();
        Map<String, String> status = executor.status(new ScheduledWorkKey("fixture:work", "1"));
        assertThat(status).isEmpty();
        assertThatThrownBy(() -> status.put("translation", "running"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(ScheduledWorkExecutor.class
                .getMethod("status", ScheduledWorkKey.class)
                .isDefault()).isTrue();
    }

    @Test
    @DisplayName("轮末统计要求每次执行尝试都有唯一耐久终态")
    void workRunStatisticsAreFullyAccounted() {
        ScheduledWorkRunStatistics statistics = new ScheduledWorkRunStatistics(
                10, 4, 2, 1, 3);

        assertThat(statistics.completedWorkCount()).isEqualTo(4);
        assertThat(statistics.pendingWorkCount()).isEqualTo(3);
        assertThatThrownBy(() -> new ScheduledWorkRunStatistics(
                1, 1, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkRunStatistics(
                0, -1, 0, 0, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledWorkRunStatistics(
                Long.MAX_VALUE, Long.MAX_VALUE, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("旧 Guard 由默认结果方法包装且不产生证据")
    void legacyGuardUsesDefaultResultWrapper() throws Exception {
        ScheduledGuardDecision decision = new ScheduledGuardDecision(
                ScheduledGuardDecision.Action.RETRY_LATER,
                "fixture.retry",
                2_000L);
        ScheduledExecutionGuard guard = new ScheduledExecutionGuard() {
            @Override
            public String guardId() {
                return "fixture:guard";
            }

            @Override
            public ScheduledGuardDecision evaluate(ScheduledGuardContext context) {
                return decision;
            }
        };

        ScheduledGuardResult result = guard.evaluateResult(null);

        assertThat(result.decision()).isSameAs(decision);
        assertThat(result.evidence().attributes()).isEmpty();
        assertThat(ScheduledExecutionGuard.class
                .getMethod("evaluateResult",
                        ScheduledGuardContext.class)
                .isDefault()).isTrue();
    }

    @Test
    @DisplayName("Guard 证据只接受有界不可变字符串属性")
    void guardEvidenceIsBoundedAndImmutable() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("modifiedAt", "1720000000000");
        mutable.put("excerpt", "risk warning");

        ScheduledGuardEvidence evidence = new ScheduledGuardEvidence(mutable);
        mutable.put("later", "changed");

        assertThat(evidence.attributes())
                .containsEntry("modifiedAt", "1720000000000")
                .containsEntry("excerpt", "risk warning")
                .doesNotContainKey("later");
        assertThatThrownBy(() -> evidence.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("invalid key", "value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("cookie", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("refresh_token", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("PHPSESSID", "12345_opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("session_key", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("connect.sid", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("sid_guard", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("source.collectionId"))
                .isFalse();
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("seriesId")).isFalse();
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("seriesID")).isFalse();
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("userId")).isFalse();
        assertThat(ScheduledSensitiveFieldNames.isSensitiveFieldName("sidCount")).isFalse();
        assertThatThrownBy(() -> new ScheduledGuardEvidence(
                Map.of("excerpt", "中".repeat(1_366))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledGuardResult(null, evidence))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("任务展示限制字符串属性并在契约边界拒绝敏感字段名")
    void taskPresentationIsBoundedImmutableAndCredentialFree() {
        Map<String, String> mutable = new LinkedHashMap<>();
        mutable.put("kind", "mixed");
        ScheduledTaskPresentation presentation = new ScheduledTaskPresentation(
                " 任务 ", " 摘要 ", mutable);
        mutable.put("later", "changed");

        assertThat(presentation.title()).isEqualTo("任务");
        assertThat(presentation.summary()).isEqualTo("摘要");
        assertThat(presentation.attributes())
                .containsExactly(Map.entry("kind", "mixed"))
                .doesNotContainKey("later");
        assertThatThrownBy(() -> presentation.attributes().put("extra", "value"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "任务", null, Map.of("PHPSESSID", "12345_opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "任务", null, Map.of("source.session_key", "opaque-session-value")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "题".repeat(ScheduledTaskPresentation.MAX_TITLE_BYTES), null, Map.of()))
                .isInstanceOf(IllegalArgumentException.class);

        Map<String, String> tooManyAttributes = new LinkedHashMap<>();
        for (int i = 0; i <= ScheduledTaskPresentation.MAX_ATTRIBUTES; i++) {
            tooManyAttributes.put("key" + i, "value");
        }
        assertThatThrownBy(() -> new ScheduledTaskPresentation(
                "任务", null, tooManyAttributes))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Guard policy 状态读取为向后兼容的可选只读快照")
    void guardPolicyStateAccessorIsOptionalDefault() throws Exception {
        ScheduledGuardContext context = new ScheduledGuardContext() {
            @Override
            public ScheduledGuardPoint point() {
                return ScheduledGuardPoint.RUN_START;
            }

            @Override
            public long attemptedWorkCount() {
                return 0;
            }

            @Override
            public ScheduledFailure failure() {
                return null;
            }

            @Override
            public ScheduledTaskDefinition task() {
                return null;
            }

            @Override
            public ScheduledNetworkRoute route() {
                return null;
            }

            @Override
            public ScheduledCredentialHandle credential() {
                return null;
            }

            @Override
            public ScheduledCancellation cancellation() {
                return () -> false;
            }
        };
        var method = ScheduledGuardContext.class
                .getMethod("credentialPolicyStateJson");

        assertThat(context.credentialPolicyStateJson()).isEmpty();
        assertThat(method.isDefault()).isTrue();
        assertThat(method.getReturnType()).isEqualTo(java.util.Optional.class);
    }

    @Test
    @DisplayName("Guard 旧决定构造器与 record 组件保持原 ABI")
    void guardDecisionKeepsLegacyConstructorAbi() throws Exception {
        Constructor<ScheduledGuardDecision> constructor = ScheduledGuardDecision.class
                .getDeclaredConstructor(
                        ScheduledGuardDecision.Action.class,
                        String.class,
                        long.class);

        assertThat(constructor).isNotNull();
        assertThat(ScheduledGuardDecision.class.getRecordComponents())
                .extracting(component -> component.getName())
                .containsExactly("action", "reasonCode", "retryAfterMillis");
    }

    @Test
    @DisplayName("本地作品终态默认退化提交且拒绝已完成结果")
    void localWorkCompletionFallsBackToSubmit() throws Exception {
        ScheduledWork work = new ScheduledWork(
                new ScheduledWorkKey("fixture:work", "local-1"),
                "fixture:work",
                1,
                "{}",
                ScheduledWorkPresentation.empty(),
                List.of());
        AtomicReference<ScheduledWork> submitted = new AtomicReference<>();
        ScheduledWorkSink sink = submitted::set;

        sink.completeLocally(work, ScheduledWorkResult.alreadyCompleted());

        assertThat(submitted.get()).isSameAs(work);
        submitted.set(null);
        sink.completeLocally(work, new ScheduledWorkResult(
                ScheduledWorkResult.Outcome.SKIPPED,
                "work.filtered",
                Map.of()));
        assertThat(submitted.get()).isSameAs(work);
        assertThatThrownBy(() -> sink.completeLocally(work, ScheduledWorkResult.completed()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> sink.completeLocally(work, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ScheduledWorkSink.class
                .getMethod("completeLocally", ScheduledWork.class, ScheduledWorkResult.class)
                .isDefault()).isTrue();
        sink.drain();
        assertThat(ScheduledWorkSink.class
                .getMethod("drain")
                .isDefault()).isTrue();
        assertThat(ScheduledWorkSink.class.isAnnotationPresent(FunctionalInterface.class)).isTrue();
    }

    @Test
    @DisplayName("旧来源默认重放全部 pending 且无 pending 索引")
    void legacySourceUsesCompatiblePendingDefaults() throws Exception {
        ScheduledSourceContext context = new ScheduledSourceContext() {
            @Override
            public ScheduledCheckpoint checkpoint() {
                return null;
            }

            @Override
            public ScheduledWorkSink workSink() {
                return work -> { };
            }

            @Override
            public ScheduledTaskDefinition task() {
                return null;
            }

            @Override
            public ScheduledNetworkRoute route() {
                return null;
            }

            @Override
            public ScheduledCredentialHandle credential() {
                return null;
            }

            @Override
            public ScheduledCancellation cancellation() {
                return () -> false;
            }
        };
        ScheduledSourceExecutor executor = new ScheduledSourceExecutor() {
            @Override
            public String sourceType() {
                return "fixture:source";
            }

            @Override
            public ScheduledExecutionPlan plan(ScheduledTaskDefinition task) {
                return ScheduledExecutionPlan.credentialFree(Set.of("fixture:work"));
            }

            @Override
            public ScheduledDiscoveryResult discover(ScheduledSourceContext sourceContext) {
                return ScheduledDiscoveryResult.withoutCheckpoint();
            }
        };
        ScheduledWorkKey key = new ScheduledWorkKey("fixture:work", "pending-1");

        assertThat(context.isPending(key)).isFalse();
        assertThat(executor.pendingReplayPolicy()).isEqualTo(ScheduledPendingReplayPolicy.ALWAYS);
        assertThat(ScheduledPendingReplayPolicy.values())
                .containsExactly(
                        ScheduledPendingReplayPolicy.ALWAYS,
                        ScheduledPendingReplayPolicy.REDISCOVERED_ONLY);
        assertThat(ScheduledSourceContext.class
                .getMethod("isPending", ScheduledWorkKey.class)
                .isDefault()).isTrue();
        assertThat(ScheduledSourceExecutor.class
                .getMethod("pendingReplayPolicy")
                .isDefault()).isTrue();
    }

    private static ScheduledCredentialContext credentialContext(
            ScheduledCredentialContext.Purpose purpose) {
        return new ScheduledCredentialContext() {
            @Override
            public Purpose purpose() {
                return purpose;
            }

            @Override
            public ScheduledTaskDefinition task() {
                return null;
            }

            @Override
            public ScheduledNetworkRoute route() {
                return ScheduledNetworkRoute.direct();
            }

            @Override
            public ScheduledCredentialHandle credential() {
                return null;
            }

            @Override
            public ScheduledCancellation cancellation() {
                return () -> false;
            }
        };
    }
}
