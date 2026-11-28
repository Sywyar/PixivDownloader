package top.sywyar.pixivdownload.schedule.execution;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SyncTaskExecutor;
import top.sywyar.pixivdownload.core.work.service.DownloadPathAction;
import top.sywyar.pixivdownload.core.work.service.DownloadPathPlan;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import top.sywyar.pixivdownload.config.OutboundProxySettings;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityPublication;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistry;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleCapabilityRegistryTestAccess;
import top.sywyar.pixivdownload.core.schedule.capability.ScheduleOwnerBundle;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityOwner;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialBindResult;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialContext;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialPolicy;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledExecutionException;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.ScheduledFailure;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.source.*;
import top.sywyar.pixivdownload.plugin.api.schedule.work.*;
import top.sywyar.pixivdownload.schedule.*;
import top.sywyar.pixivdownload.schedule.batch.ManualBatchService;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("独立手动批次的真实引擎与生命周期")
class ManualBatchServiceTest extends ScheduleExecutionEngineTestSupport {
    private record Fixture(ManualBatchService service, String token, AtomicInteger probes, ScheduledCredentialPolicy policy,
                           ScheduleCapabilityRegistry registry, ScheduleCapabilityPublication workPublication) {}

    private Fixture service(ScheduledSourceExecutor source, ScheduledWorkExecutor work) {
        return service(source, work, false);
    }

    private Fixture service(ScheduledSourceExecutor source, ScheduledWorkExecutor work, boolean separateWorkOwner) {
        return service(source, work, separateWorkOwner, false, context -> ScheduledGuardDecision.proceed());
    }

    private Fixture service(ScheduledSourceExecutor source, ScheduledWorkExecutor work, boolean separateWorkOwner,
                            boolean fixed, Guard guardDecision) {
        var registry = new ScheduleCapabilityRegistry();
        var probes = new AtomicInteger();
        ScheduledCredentialPolicy policy = spy(new ScheduledCredentialPolicy() {
            @Override public boolean isBindingFixed(String sourceType, String definitionJson) {
                return fixed && SOURCE.equals(sourceType) && "{}".equals(definitionJson);
            }
            @Override public String policyId() { return POLICY; }
            @Override public ScheduledCredentialProbeResult probe(ScheduledCredentialContext context) {
                probes.incrementAndGet();
                return ScheduledCredentialProbeResult.valid("account-1");
            }
        });
        var bundle = bindingBundle(new ScheduleCapabilityOwner("fixture", "fixture-package", 1), source, work,
                policy, guard(guardDecision));
        var sourcePublication = ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                bundle.owner(), bundle.sourceDescriptors(), bundle.sourceExecutors(), separateWorkOwner ? List.of() : List.of(work),
                bundle.credentialPolicies(), bundle.guards()));
        var workPublication = separateWorkOwner ? publishWork(registry, 1, work) : sourcePublication;
        var json = JsonMapper.builder().build();
        var service = new ManualBatchService(registry, mock(ScheduledTaskStore.class), new ScheduleRunState(),
                mock(TransactionTemplate.class), new ScheduleConfig(), new ScheduleWorkPersistenceCodec(json),
                new ScheduleNetworkRouteResolver(mock(OutboundProxySettings.class)), new SyncTaskExecutor(),
                new ScheduleWorkConcurrencyLimiter(), json, () -> true);
        service.start();
        return new Fixture(service, activationToken(registry), probes, policy, registry, workPublication);
    }

    private ScheduleCapabilityPublication publishWork(ScheduleCapabilityRegistry registry, long generation, ScheduledWorkExecutor work) {
        var owner = new ScheduleCapabilityOwner("other-work", "other-work-package", generation);
        return ScheduleCapabilityRegistryTestAccess.publish(registry, ScheduleOwnerBundle.prepare(
                owner, List.of(), List.of(), List.of(work), List.of(), List.of()));
    }

    private ManualBatchService.Request request(String token) {
        return new ManualBatchService.Request("Fixture", SOURCE, token, "{}", null);
    }

    @Test
    void httpUsesRealEngineStrictInputAndCookieCsrf() throws Exception {
        var source = spy(sourceExecutor(1, context -> {
            context.workSink().submit(work("http-work"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var mvc = http(fixture);
        String base = "/api/download/batches";
        var json = JsonMapper.builder().enable(tools.jackson.databind.DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
        var expectedWeight = new java.math.BigDecimal("1.000000000000000001");
        var body = json.createObjectNode().put("name", "Fixture").put("sourceType", SOURCE).put("activationToken", fixture.token());
        body.putObject("definition").putObject("source").put("userId", "42")
                .put("weight", expectedWeight);
        byte[] bytes = json.writeValueAsBytes(body);
        try {
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .cookie(new jakarta.servlet.http.Cookie("pixiv_session", "admin"))
                    .header("Origin", "https://foreign.invalid").content(bytes))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .content("{\"unknown\":true}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            for (var invalid : List.of(body.deepCopy().put("definition", "{}"), body.deepCopy().putNull("definition"),
                    body.deepCopy().set("definition", json.createArrayNode()),
                    body.deepCopy().set("definition", json.createObjectNode().put("oversized", "a".repeat(1_048_576))))) {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                        .content(json.writeValueAsBytes(invalid)))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
            }
            for (String field : List.of("name", "sourceType", "activationToken", "proxy")) {
                mvc.perform(post(base).contentType("application/json").content(json.writeValueAsBytes(body.deepCopy().put(field, 123))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST"))
                        .andExpect(jsonPath("$.field").value("/" + field)).andExpect(jsonPath("$.reason").value("type"));
            }
            mvc.perform(get(base)).andExpect(status().isNoContent());
            verify(source, never()).discover(any());
            var receipt = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json")
                    .header("X-Acquisition-Credential", "fixture-secret").content(bytes))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isAccepted()).andReturn().getResponse();
            String location = receipt.getHeader("Location");
            assertThat(location).isEqualTo(base + "/" + json.readTree(receipt.getContentAsByteArray()).required("id").asString());
            var done = awaitIdle(fixture.service());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(location))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.state").value("COMPLETED"));
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            verify(source).prepare(argThat(draft -> {
                var weight = json.readTree(draft.definitionJson()).at("/source/weight");
                return weight.isNumber() && weight.decimalValue().compareTo(expectedWeight) == 0;
            }));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(base + "/" + done.id() + "/items").param("limit", "1"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.items[0].key.id").value("http-work"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("fixture-secret"))));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(base).contentType("application/json").content(bytes))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(base + "/" + done.id()))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNoContent());
        } finally { fixture.service().stop(); }
    }

    private org.springframework.test.web.servlet.MockMvc http(Fixture fixture) {
        var messages = top.sywyar.pixivdownload.i18n.TestI18nBeans.appMessages();
        var locale = mock(top.sywyar.pixivdownload.i18n.AppLocaleResolver.class);
        when(locale.resolveLocale(any())).thenReturn(java.util.Locale.US);
        return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new top.sywyar.pixivdownload.schedule.batch.ManualBatchController(fixture.service(),
                        new top.sywyar.pixivdownload.gui.ToolApiRequests(messages, mock(top.sywyar.pixivdownload.i18n.WebI18nService.class)), messages))
                .addFilters(new top.sywyar.pixivdownload.setup.CsrfProtectionFilter(locale, messages,
                        mock(top.sywyar.pixivdownload.plugin.registry.route.RouteAccessRegistry.class))).build();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void fixedCredentialCanBeBoundOnceAndSurvivesRejectedReplacementAndRemoval(boolean suspendOnBind) throws Exception {
        var hold = new java.util.concurrent.atomic.AtomicBoolean(true);
        var fixture = service(sourceExecutor(1, context -> {
            assertThat(context.credential().copySecret()).containsExactly("original".toCharArray());
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> ScheduledWorkResult.completed()), false, true, context -> !suspendOnBind && hold.get()
                ? new ScheduledGuardDecision(ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK, "fixture.hold", 0)
                : ScheduledGuardDecision.proceed());
        if (suspendOnBind) {
            doAnswer(call -> hold.get() ? new ScheduledCredentialBindResult(ScheduledCredentialProbeResult.valid("account-1"),
                    "{}", ScheduledGuardResult.decision(new ScheduledGuardDecision(
                            ScheduledGuardDecision.Action.SUSPEND_POLICY_TASK, "fixture.hold", 0))) : call.callRealMethod())
                    .when(fixture.policy()).probeForBinding(any());
        }
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            var id = service.create(request(fixture.token()), null).id();
            var unbound = awaitIdle(service);
            service.credentials(id, unbound.run(), "original");
            service.resume(id, false);
            var bound = awaitIdle(service);
            assertThat(bound.state()).isEqualTo(ManualBatchService.State.SUSPENDED);
            for (var candidate : java.util.Arrays.asList("replacement", null)) {
                var update = org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/download/batches/{id}/credentials", id).contentType("application/json")
                        .content("{\"expectedRun\":" + bound.run() + "}");
                if (candidate != null) update.header("X-Acquisition-Credential", candidate);
                mvc.perform(update).andExpect(status().isBadRequest())
                        .andExpect(jsonPath("$.code").value("schedule.error.credential-fixed"));
            }
            hold.set(false);
            service.resume(id, false);
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.COMPLETED);
        } finally { service.stop(); }
    }

    @Test
    void initialCredentialProbeCanRequestDelayedRetryBeforeDiscovery() throws Exception {
        var source = spy(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        doReturn(ScheduledCredentialBindResult.fromProbe(ScheduledCredentialProbeResult.retryLater("fixture.probe", 3200)))
                .when(fixture.policy()).probeForBinding(any());
        try {
            var id = fixture.service().create(request(fixture.token()), "fixture-secret").id();
            awaitIdle(fixture.service());
            http(fixture).perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/download/batches/{id}", id))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.state").value("RETRY_LATER"))
                    .andExpect(jsonPath("$.code").value("fixture.probe")).andExpect(jsonPath("$.retryAfterMillis").value(3200));
            verify(source, never()).discover(any());
        } finally { fixture.service().stop(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "FAIL,FAILED,0", "SUSPEND_POLICY_TASK,SUSPENDED,0", "SUSPEND_CREDENTIAL,SUSPENDED,0",
            "RETRY_LATER,RETRY_LATER,7300", "RETRY_LATER,RETRY_LATER,0"})
    void batchStatusReportsGuardDecision(ScheduledGuardDecision.Action action, String state, long delay) throws Exception {
        var decision = new java.util.concurrent.atomic.AtomicReference<>(new ScheduledGuardDecision(action, "fixture.guard", delay));
        var fixture = service(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()),
                workExecutor(context -> ScheduledWorkResult.completed()), false, false, context -> decision.get());
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            var id = service.create(request(fixture.token()), "fixture-secret").id();
            awaitIdle(service);
            var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/download/batches/{id}", id);
            var result = mvc.perform(get).andExpect(status().isOk()).andExpect(jsonPath("$.state").value(state))
                    .andExpect(jsonPath("$.code").value("fixture.guard"));
            if (action == ScheduledGuardDecision.Action.RETRY_LATER)
                result.andExpect(jsonPath("$.retryAfterMillis").value(delay));
            else result.andExpect(jsonPath("$.retryAfterMillis").doesNotExist());
            decision.set(ScheduledGuardDecision.proceed());
            service.resume(id, false);
            awaitIdle(service);
            mvc.perform(get).andExpect(jsonPath("$.state").value("COMPLETED"))
                    .andExpect(jsonPath("$.retryAfterMillis").doesNotExist());
        } finally { service.stop(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"ALWAYS,false,resume", "REDISCOVERED_ONLY,true,resume",
            "REDISCOVERED_ONLY,false,resume", "REDISCOVERED_ONLY,false,retry"})
    void acceptedWorkSurvivesInterruptionBeforeDispatch(ScheduledPendingReplayPolicy policy, boolean returnsB, String followup) throws Exception {
        String reason = "DOWNLOAD_PATH_ACTION_REQUIRED";
        var scans = new AtomicInteger();
        var calls = new CopyOnWriteArrayList<String>();
        var worker = spy(workExecutor(context -> ScheduledWorkResult.completed()));
        doAnswer(invocation -> {
            ScheduledWork work = invocation.getArgument(0);
            ScheduledWorkContext context = invocation.getArgument(1);
            String id = work.key().id();
            calls.add(id);
            if (id.equals("A") && context.userAction(reason).isEmpty())
                throw new ScheduledExecutionException(ScheduledFailure.Category.USER_ACTION_REQUIRED, reason);
            return ScheduledWorkResult.completed();
        }).when(worker).execute(any(), any());
        var fixture = service(sourceExecutor(1, policy, context -> {
            int scan = scans.incrementAndGet();
            context.workSink().submit(work("A"));
            if (scan != 2 || returnsB) context.workSink().submit(work("B"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), worker);
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            var id = service.create(request(fixture.token()), "fixture-secret").id();
            String path = "/api/download/batches/" + id;
            var first = awaitIdle(service);
            assertThat(calls).containsExactly("A");
            mvc.perform(get(path + "/items")).andExpect(jsonPath("$.items[0].message").value(reason))
                    .andExpect(jsonPath("$.items[1].status").value("paused"));
            var decision = new ManualBatchService.Resolve(first.run(), WORK, "A", reason, "TRUNCATE", false);
            mvc.perform(post(path + "/resolve").contentType("application/json").content(JsonMapper.shared().writeValueAsBytes(decision)))
                    .andExpect(status().isOk());
            mvc.perform(post(path + "/resume")).andExpect(status().isOk());
            var resumed = awaitIdle(service);
            if (policy == ScheduledPendingReplayPolicy.REDISCOVERED_ONLY && !returnsB) {
                assertThat(resumed.state()).isEqualTo(ManualBatchService.State.PAUSED);
                assertThat(resumed.completed()).isOne();
                assertThat(calls).containsExactly("A", "A");
                mvc.perform(post(path + "/" + followup)).andExpect(status().isOk());
                awaitIdle(service);
            }
            mvc.perform(get(path)).andExpect(jsonPath("$.state").value("COMPLETED")).andExpect(jsonPath("$.total").value(2))
                    .andExpect(jsonPath("$.completed").value(2)).andExpect(jsonPath("$.failed").value(0));
            mvc.perform(get(path + "/items")).andExpect(jsonPath("$.items[0].key.id").value("A"))
                    .andExpect(jsonPath("$.items[0].status").value("downloaded")).andExpect(jsonPath("$.items[1].key.id").value("B"))
                    .andExpect(jsonPath("$.items[1].status").value("downloaded"));
            assertThat(calls).containsExactly("A", "A", "B");
            assertThat(scans.get()).isEqualTo(policy == ScheduledPendingReplayPolicy.REDISCOVERED_ONLY && !returnsB && followup.equals("resume") ? 3 : 2);
            mvc.perform(post(path + "/retry")).andExpect(status().isConflict());
        } finally { service.stop(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(value = ScheduledFailure.Category.class, names = {"RETRYABLE_NETWORK", "NOT_FOUND"})
    void retryUsesEnvelopeFromMostRecentFailureWithoutDiscovery(ScheduledFailure.Category secondFailure) throws Exception {
        var scans = new AtomicInteger();
        var revisions = new CopyOnWriteArrayList<Integer>();
        var worker = spy(workExecutor(context -> ScheduledWorkResult.completed()));
        doAnswer(invocation -> {
            ScheduledWork work = invocation.getArgument(0);
            int revision = JsonMapper.shared().readTree(work.payloadJson()).required("revision").asInt();
            revisions.add(revision);
            if (revision == 1) throw new ScheduledExecutionException(ScheduledFailure.Category.NOT_FOUND, "fixture.missing");
            if (revisions.size() == 2) throw new ScheduledExecutionException(secondFailure, "fixture.unavailable");
            return ScheduledWorkResult.completed();
        }).when(worker).execute(any(), any());
        var fixture = service(sourceExecutor(1, context -> {
            var work = new ScheduledWork(new ScheduledWorkKey(WORK, "item"), "fixture.work", 1,
                    "{\"revision\":" + scans.incrementAndGet() + "}", ScheduledWorkPresentation.empty(), List.of());
            context.workSink().submit(work);
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), worker);
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            var id = service.create(request(fixture.token()), "fixture-secret").id();
            String path = "/api/download/batches/" + id;
            assertThat(awaitIdle(service).failed()).isOne();
            mvc.perform(post(path + "/resume")).andExpect(status().isOk());
            assertThat(awaitIdle(service).failed()).isOne();
            mvc.perform(post(path + "/retry")).andExpect(status().isOk());
            assertThat(awaitIdle(service).completed()).isOne();
            mvc.perform(get(path + "/items")).andExpect(jsonPath("$.total").value(1)).andExpect(jsonPath("$.items[0].status").value("downloaded"));
            assertThat(revisions).containsExactly(1, 2, 2);
            assertThat(scans.get()).isEqualTo(2);
        } finally { service.stop(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"retry", "resume"})
    void redispatchedItemUsesCurrentExecutorPublication(String operation) throws Exception {
        var firstResult = new ScheduledWorkResult(ScheduledWorkResult.Outcome.COMPLETED, "generation-one", java.util.Map.of());
        var first = spy(workExecutor(context -> firstResult));
        doThrow(new ScheduledExecutionException(ScheduledFailure.Category.NOT_FOUND, "fixture.missing"))
                .when(first).execute(argThat(work -> work.key().id().equals("recover")), any());
        var fixture = service(sourceExecutor(1, context -> {
            context.workSink().submit(work("completed"));
            context.workSink().submit(work("recover"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), first, true);
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var second = spy(workExecutor(context -> {
            started.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("executor not released");
            } catch (InterruptedException failure) { throw ScheduledExecutionException.cancelled(); }
            return new ScheduledWorkResult(ScheduledWorkResult.Outcome.COMPLETED, "generation-two", java.util.Map.of());
        }));
        var mvc = http(fixture);
        var service = fixture.service();
        try {
            var id = service.create(request(fixture.token()), "fixture-secret").id();
            String path = "/api/download/batches/" + id;
            var initial = awaitIdle(service);
            assertThat(initial.completed()).isOne();
            assertThat(initial.failed()).isOne();
            var old = fixture.workPublication();
            assertThat(ScheduleCapabilityRegistryTestAccess.withdraw(fixture.registry(), old)).isPresent();
            var replacement = publishWork(fixture.registry(), 2, second);
            assertThat(replacement.publicationId()).isNotEqualTo(old.publicationId());
            assertThat(activationToken(fixture.registry())).isEqualTo(fixture.token());

            mvc.perform(post(path + "/" + operation)).andExpect(status().isOk());
            assertThat(started.await(10, TimeUnit.SECONDS)).isTrue();
            var items = get(path + "/items");
            mvc.perform(items).andExpect(status().isOk())
                    .andExpect(jsonPath("$.items[1].status").value("pending"))
                    .andExpect(jsonPath("$.items[1].workExecutorPublicationId").value(replacement.publicationId()));
            release.countDown();
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.COMPLETED);
            mvc.perform(get(path)).andExpect(jsonPath("$.total").value(2)).andExpect(jsonPath("$.completed").value(2))
                    .andExpect(jsonPath("$.failed").value(0)).andExpect(jsonPath("$.skipped").value(0));
            var response = mvc.perform(items).andExpect(status().isOk()).andReturn().getResponse();
            var page = JsonMapper.shared().readValue(response.getContentAsByteArray(), ManualBatchService.Items.class);
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.items()).extracting(item -> item.key().id()).containsExactly("completed", "recover");
            assertThat(page.items()).extracting(item -> item.result().resultCode()).containsExactly("generation-one", "generation-two");
            assertThat(page.items()).extracting(ScheduleRunQueue.Item::workExecutorOwner).containsExactly(old.owner(), replacement.owner());
            assertThat(page.items()).extracting(ScheduleRunQueue.Item::workExecutorPublicationId)
                    .containsExactly(old.publicationId(), replacement.publicationId());
            verify(second, never()).execute(argThat(work -> work.key().id().equals("completed")), any());
            verify(second).execute(argThat(work -> work.key().id().equals("recover")), any());
        } finally { release.countDown(); service.stop(); }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"retry,false,DEFAULT_NAME", "retry,true,TRUNCATE",
            "resume,false,TRUNCATE", "resume,true,DEFAULT_NAME"})
    void onlyFirstPendingDecisionIsConsumed(String operation, boolean remember, String choice) throws Exception {
        String reason = "DOWNLOAD_PATH_ACTION_REQUIRED";
        var discoveries = new AtomicInteger();
        var actions = new CopyOnWriteArrayList<String>();
        var fixture = service(sourceExecutor(1, context -> {
            if (discoveries.incrementAndGet() == 1) context.workSink().submit(work("42"));
            else context.workSink().submit(work("next"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            actions.add(context.userAction(reason).orElse("ASK"));
            if (actions.size() <= 2) {
                var action = DownloadPathAction.parse(actions.getLast());
                try {
                    var plan = DownloadPathPlan.resolve(java.nio.file.Path.of("works"),
                            path -> !path.endsWith("too-long.jpg"), cap -> List.of(cap == 1 ? "t" : "too-long"),
                            List.of("default"), List.of(".jpg"), List.of(), action);
                    assertThat(plan.baseNames()).containsExactly(choice.equals("DEFAULT_NAME") ? "default" : "t");
                } catch (DownloadPathPlan.NeedsAction waiting) {
                    throw new ScheduledExecutionException(ScheduledFailure.Category.USER_ACTION_REQUIRED, waiting.getMessage());
                }
            }
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            var id = service.create(request(fixture.token()), "fixture-secret").id();
            var waiting = awaitIdle(service);
            String path = "/api/download/batches/" + id;
            var json = JsonMapper.shared();
            var decision = json.createObjectNode().put("expectedRun", waiting.run()).put("workType", WORK)
                    .put("workId", "42").put("reasonCode", reason).put("userAction", choice).put("rememberForRun", remember);
            for (String field : List.of("workId", "workType", "reasonCode", "userAction")) {
                mvc.perform(post(path + "/resolve").contentType("application/json")
                        .content(json.writeValueAsBytes(decision.deepCopy().put(field, 42))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST"))
                        .andExpect(jsonPath("$.field").value("/" + field)).andExpect(jsonPath("$.reason").value("type"));
            }
            for (String unsupported : List.of("TRUNCAT", "ASK")) {
                mvc.perform(post(path + "/resolve").contentType("application/json")
                        .content(json.writeValueAsBytes(decision.deepCopy().put("userAction", unsupported))))
                        .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST"))
                        .andExpect(jsonPath("$.field").value("/userAction")).andExpect(jsonPath("$.reason").value("invalid_value"));
            }
            mvc.perform(post(path + "/resolve").contentType("application/json").content(json.writeValueAsBytes(decision)))
                    .andExpect(status().isOk());
            decision.put("userAction", "CANCEL").put("rememberForRun", !remember);
            mvc.perform(post(path + "/resolve").contentType("application/json").content(json.writeValueAsBytes(decision)))
                    .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("batch.action-changed"));
            mvc.perform(post(path + "/" + operation)).andExpect(status().isOk());
            awaitIdle(service);
            mvc.perform(get(path + "/items")).andExpect(jsonPath("$.items[0].key.id").value("42"))
                    .andExpect(jsonPath("$.items[0].status").value("downloaded"));
            if (operation.equals("retry")) assertThat(actions).containsExactly("ASK", choice);
            else assertThat(actions).containsExactly("ASK", choice, remember ? choice : "ASK");
        } finally { service.stop(); }
    }

    @Test
    void rejectedDefinitionLeavesNoBatchOrCredentialProbe() throws Exception {
        var source = spy(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()));
        doThrow(new ScheduledExecutionException(ScheduledFailure.Category.INVALID_DEFINITION, "fixture.invalid"))
                .when(source).prepare(argThat(draft -> draft.definitionJson().equals("{\"valid\":false}")));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var mvc = http(fixture);
        var json = JsonMapper.shared();
        String base = "/api/download/batches";
        var body = json.createObjectNode().put("name", "Fixture").put("sourceType", SOURCE).put("activationToken", fixture.token());
        var definition = body.putObject("definition").put("valid", false);
        try {
            mvc.perform(post(base).contentType("application/json").header("X-Acquisition-Credential", "fixture-secret")
                    .content(json.writeValueAsBytes(body))).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_TOOL_REQUEST")).andExpect(jsonPath("$.field").value("/definition"))
                    .andExpect(jsonPath("$.reason").value("invalid_value")).andExpect(header().doesNotExist("Location"));
            mvc.perform(get(base)).andExpect(status().isNoContent());
            assertThat(fixture.probes()).hasValue(0);
            verify(source, never()).discover(any());
            definition.put("valid", true);
            String location = mvc.perform(post(base).contentType("application/json").header("X-Acquisition-Credential", "fixture-secret")
                    .content(json.writeValueAsBytes(body))).andExpect(status().isAccepted())
                    .andReturn().getResponse().getHeader("Location");
            assertThat(location).isEqualTo(base + "/" + awaitIdle(fixture.service()).id());
            mvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("COMPLETED"));
            assertThat(fixture.probes().get()).isPositive();
        } finally { fixture.service().stop(); }
    }

    @Test
    void concurrentCreateCannotPublishOverDefinitionBeingChecked() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var source = spy(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()));
        doAnswer(call -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("preparation timed out");
            return call.callRealMethod();
        }).when(source).prepare(any());
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var service = fixture.service();
        try {
            var command = request(fixture.token());
            var creating = CompletableFuture.supplyAsync(() -> service.create(command, null));
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.current()).isNull();
            assertThatThrownBy(() -> service.create(command, null)).isInstanceOf(IllegalStateException.class)
                    .hasMessage("batch.exists");
            release.countDown();
            assertThat(creating.get(10, TimeUnit.SECONDS).id()).isEqualTo(awaitIdle(service).id());
        } finally {
            release.countDown();
            service.stop();
        }
    }

    @Test
    void shutdownWaitsForWorkAndCredentialReplacementRequiresIdleRun() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var fixture = service(sourceExecutor(1, context -> {
            context.workSink().submit(work("blocked"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            entered.countDown();
            try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
            catch (InterruptedException failure) { throw ScheduledExecutionException.cancelled(); }
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        var stopped = new CountDownLatch(1);
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> service.credentials(accepted.id(), accepted.run(), "replacement"))
                    .isInstanceOf(IllegalStateException.class);
            Thread.ofPlatform().start(() -> { service.stop(); stopped.countDown(); });
            assertThat(stopped.await(50, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            assertThat(stopped.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.get(accepted.id()).state()).isEqualTo(ManualBatchService.State.CANCELLED);
        } finally { release.countDown(); service.stop(); }
    }
    private ManualBatchService.View awaitIdle(ManualBatchService service) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            var view = service.current();
            if (!List.of(ManualBatchService.State.RUNNING, ManualBatchService.State.PAUSING, ManualBatchService.State.CANCELLING).contains(view.state())) return view;
            Thread.sleep(5);
        }
        throw new AssertionError("batch did not drain");
    }

    @Test
    void providerShortcutPinsItsSourceAndRequiresTheSameCreateAndWorkGrants() throws Exception {
        var source = spy(sourceExecutor(1, context -> {
            context.workSink().submit(work("from-shortcut"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var batch = fixture.service();
        var key = java.util.UUID.randomUUID();
        var authorized = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(key,
                java.util.Set.of("core:batches.create", "fixture:batches.execute"));
        try {
            for (String grant : authorized.permissions()) {
                var incomplete = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(key, java.util.Set.of(grant));
                assertThatThrownBy(() -> batch.submit("fixture", SOURCE, "test", "{}", null, incomplete))
                        .isInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);
            }
            for (String sourceType : List.of(SOURCE, "missing-source")) {
                assertThatThrownBy(() -> batch.submit("retired-owner", sourceType, "test", "{}", null, authorized))
                        .isInstanceOfSatisfying(top.sywyar.pixivdownload.i18n.LocalizedException.class, failure -> {
                            assertThat(failure.getStatus().value()).isEqualTo(404);
                            assertThat(failure.getMessageCode()).isEqualTo("batch.not-found");
                        });
            }
            verify(source, never()).prepare(any());
            var id = batch.submit("fixture", SOURCE, "test", "{}", "fixture-secret", authorized);
            assertThat(awaitIdle(batch).state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(batch.current(authorized).id()).isEqualTo(id);
        } finally { batch.stop(); }
    }

    @Test
    @DisplayName("暂停等待实际作品退出，恢复不重放已完成作品，删除只作用于精确批次")
    void pausesAfterDrainAndResumesWithoutRepeatingCompletedWork() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executions = new AtomicInteger();
        var discoveries = new AtomicInteger();
        var fixture = service(sourceExecutor(1, context -> {
            var expected = discoveries.getAndIncrement() == 0 ? "fixture-secret" : "replacement";
            assertThat(context.credential().copySecret()).containsExactly(expected.toCharArray());
            for (String id : List.of("first", "second", "third")) context.workSink().submit(work(id));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            if (executions.incrementAndGet() == 2) {
                entered.countDown();
                try { if (!release.await(10, TimeUnit.SECONDS)) throw new AssertionError("release timeout"); }
                catch (InterruptedException failure) { throw ScheduledExecutionException.cancelled(); }
            }
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(service.stop(accepted.id(), true).state()).isEqualTo(ManualBatchService.State.PAUSING);
            assertThatThrownBy(() -> service.delete(accepted.id())).isInstanceOf(IllegalStateException.class);
            release.countDown();
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.PAUSED);
            assertThatThrownBy(() -> service.credentials(accepted.id(), accepted.run() - 1, "replacement"))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(service.credentials(accepted.id(), accepted.run(), "replacement").state()).isEqualTo(ManualBatchService.State.PAUSED);
            service.credentials(accepted.id(), accepted.run(), null);
            service.credentials(accepted.id(), accepted.run(), "replacement");
            service.resume(accepted.id(), false);
            var done = awaitIdle(service);
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(done.completed()).isEqualTo(3);
            assertThat(executions).hasValue(3);
            assertThat(service.items(done.id(), 1, 1).items()).singleElement()
                    .satisfies(item -> assertThat(item.key().id()).isEqualTo("second"));
            assertThatThrownBy(() -> service.delete(java.util.UUID.randomUUID())).isInstanceOf(java.util.NoSuchElementException.class);
            service.delete(done.id());
            assertThat(service.current()).isNull();
        } finally { release.countDown(); service.stop(); }
    }

    @Test
    @DisplayName("重试只处理失败项，不重新扫描来源或重复成功作品")
    void retriesOnlyFailedWork() throws Exception {
        var discoveries = new AtomicInteger();
        var executions = new AtomicInteger();
        var fixture = service(sourceExecutor(1, context -> {
            discoveries.incrementAndGet();
            context.workSink().submit(work("good"));
            context.workSink().submit(work("bad"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), workExecutor(context -> {
            if (executions.incrementAndGet() == 2) throw new ScheduledExecutionException(ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
            return ScheduledWorkResult.completed();
        }));
        var service = fixture.service();
        try {
            var accepted = service.create(request(fixture.token()), "fixture-secret");
            assertThat(awaitIdle(service).state()).isEqualTo(ManualBatchService.State.FAILED);
            service.resume(accepted.id(), true);
            var done = awaitIdle(service);
            assertThat(done.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(done.completed()).isEqualTo(2);
            assertThat(executions).hasValue(3);
            assertThat(discoveries).hasValue(1);
        } finally { service.stop(); }
    }

    @Test
    @DisplayName("过期来源令牌保留挂起批次且不调用来源，宿主停止后拒绝新提交")
    void stalePublicationFailsClosed() throws Exception {
        var source = spy(sourceExecutor(1, context -> ScheduledDiscoveryResult.withoutCheckpoint()));
        var fixture = service(source, workExecutor(context -> ScheduledWorkResult.completed()));
        var service = fixture.service();
        try {
            service.create(request("retired"), "fixture-secret");
            var view = awaitIdle(service);
            assertThat(view.state()).isEqualTo(ManualBatchService.State.SUSPENDED);
            assertThat(view.code()).isEqualTo("schedule.error.source-publication-changed");
            verify(source, never()).prepare(any());
            verify(source, never()).plan(any());
            verify(source, never()).discover(any());
        } finally { service.stop(); }
        assertThatThrownBy(() -> service.create(request(fixture.token()), null)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryAfterIncompleteRescanLeavesNextPageResumable() throws Exception {
        var scans = new AtomicInteger();
        var calls = new CopyOnWriteArrayList<String>();
        var worker = spy(workExecutor(context -> ScheduledWorkResult.completed()));
        doAnswer(invocation -> {
            ScheduledWork work = invocation.getArgument(0);
            String key = work.key().id();
            boolean first = !calls.contains(key);
            calls.add(key);
            if (first && !key.equals("C")) throw new ScheduledExecutionException(ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.retry");
            return ScheduledWorkResult.completed();
        }).when(worker).execute(any(), any());
        var fixture = service(sourceExecutor(1, context -> {
            int scan = scans.incrementAndGet();
            var sink = context.workSink();
            sink.submit(work("A"));
            if (scan >= 2) sink.submit(work("B"));
            sink.drain();
            if (scan == 2) throw new ScheduledExecutionException(ScheduledFailure.Category.RETRYABLE_NETWORK, "fixture.next-page");
            if (scan == 3) sink.submit(work("C"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }), worker);
        var service = fixture.service();
        var mvc = http(fixture);
        try {
            String route = "/api/download/batches/" + service.create(request(fixture.token()), "fixture-secret").id();
            assertThat(awaitIdle(service).failed()).isOne();
            mvc.perform(post(route + "/resume")).andExpect(status().isOk());
            assertThat(awaitIdle(service).code()).isEqualTo("fixture.next-page");
            mvc.perform(post(route + "/retry")).andExpect(status().isOk());
            awaitIdle(service);
            mvc.perform(get(route)).andExpect(jsonPath("$.state").value("PAUSED"))
                    .andExpect(jsonPath("$.code").value("batch.discovery-incomplete"))
                    .andExpect(jsonPath("$.completed").value(2)).andExpect(jsonPath("$.failed").value(0));
            assertThat(scans).hasValue(2);
            assertThat(calls).containsExactly("A", "A", "B", "B");
            mvc.perform(post(route + "/resume")).andExpect(status().isOk());
            awaitIdle(service);
            mvc.perform(get(route)).andExpect(jsonPath("$.state").value("COMPLETED"))
                    .andExpect(jsonPath("$.total").value(3)).andExpect(jsonPath("$.completed").value(3));
            assertThat(scans).hasValue(3);
            assertThat(calls).containsExactly("A", "A", "B", "B", "C");
        } finally { service.stop(); }
    }

    @Test @DisplayName("批次来源未授权时不调用插件，完整计划拒绝未授权作品且不探活凭据")
    void programChecksSourceAndEveryWorkOwnerBeforeCredentials() throws Exception {
        var source = spy(sourceExecutor(1, context -> {
            context.workSink().submit(work("program-work"));
            return ScheduledDiscoveryResult.withoutCheckpoint();
        }));
        var worker = spy(workExecutor(context -> ScheduledWorkResult.completed()));
        var fixture = service(source, worker, true);
        var id = java.util.UUID.randomUUID();
        var denied = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(id, java.util.Set.of());
        var sourceOnly = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(id, java.util.Set.of("fixture:batches.execute"));
        try {
            assertThatThrownBy(() -> fixture.service().create(request(fixture.token()), "fixture-secret", denied))
                    .isInstanceOf(top.sywyar.pixivdownload.i18n.LocalizedException.class);
            assertThat(fixture.service().current()).isNull();
            verify(source, never()).prepare(any());
            var accepted = fixture.service().create(request(fixture.token()), "fixture-secret", sourceOnly);
            var failed = awaitIdle(fixture.service());
            assertThat(failed.code()).isEqualTo("auth.program.forbidden");
            assertThat(failed.programPermission()).isEqualTo("other-work:batches.execute");
            assertThat(fixture.probes()).hasValue(0);
            verify(source, never()).discover(any());
            verify(worker, never()).execute(any(), any());
            var other = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(java.util.UUID.randomUUID(), sourceOnly.permissions());
            assertThat(fixture.service().current(other)).isNull();
            assertThatThrownBy(() -> fixture.service().authorize(accepted.id(), other)).isInstanceOf(java.util.NoSuchElementException.class);
            var allowed = new top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity(id,
                    java.util.Set.of("fixture:batches.execute", "other-work:batches.execute"));
            fixture.service().resume(accepted.id(), false, allowed);
            var completed = awaitIdle(fixture.service());
            assertThat(completed.state()).isEqualTo(ManualBatchService.State.COMPLETED);
            assertThat(completed.programPermission()).isNull();
            assertThat(fixture.probes().get()).isPositive();
            verify(worker).execute(any(), any());
        } finally { fixture.service().stop(); }
    }
}
