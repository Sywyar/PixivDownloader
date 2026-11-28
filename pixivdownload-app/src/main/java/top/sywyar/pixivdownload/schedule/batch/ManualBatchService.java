package top.sywyar.pixivdownload.schedule.batch;

import top.sywyar.pixivdownload.common.InvalidPage;

import top.sywyar.pixivdownload.setup.ProgramAccess;
import top.sywyar.pixivdownload.plugin.api.contract.InputFieldException;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.task.TaskExecutor;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import top.sywyar.pixivdownload.core.database.DatabaseAvailability;
import top.sywyar.pixivdownload.core.schedule.*;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.plugin.api.download.context.AcquisitionCredentials;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialProbeResult;
import top.sywyar.pixivdownload.plugin.api.schedule.execution.*;
import top.sywyar.pixivdownload.plugin.api.web.ManualBatchSubmission;
import top.sywyar.pixivdownload.plugin.api.schedule.guard.ScheduledGuardDecision;
import top.sywyar.pixivdownload.plugin.api.schedule.source.*;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkKey;
import top.sywyar.pixivdownload.schedule.*;
import top.sywyar.pixivdownload.schedule.definition.ScheduleTaskDefinitionValidator;
import top.sywyar.pixivdownload.schedule.definition.ScheduleExecutionPlanGate;
import top.sywyar.pixivdownload.schedule.execution.*;
import top.sywyar.pixivdownload.schedule.persistence.ScheduleWorkPersistenceCodec;

import java.util.*;
import top.sywyar.pixivdownload.plugin.api.web.ProgramIdentity;

/** 独立手动批次，只保留当前批次；不创建定时任务，不依赖浏览器保持连接。 */
public final class ManualBatchService implements SmartLifecycle, ManualBatchSubmission {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ManualBatchService.class);
    private final ScheduleCapabilityAccess capabilities;
    private final ScheduledTaskStore schedules;
    private final ScheduleRunState scheduledRuns;
    private final TransactionTemplate transactions;
    private final ScheduleConfig config;
    private final ScheduleWorkPersistenceCodec codec;
    private final ScheduleNetworkRouteResolver routes;
    private final TaskExecutor workers;
    private final ScheduleWorkConcurrencyLimiter concurrency;
    private final ObjectMapper json;
    private final DatabaseAvailability database;
    private boolean running;
    private boolean creating;
    private Batch current;

    public ManualBatchService(ScheduleCapabilityAccess capabilities, ScheduledTaskStore schedules,
                              ScheduleRunState scheduledRuns, TransactionTemplate transactions, ScheduleConfig config,
                              ScheduleWorkPersistenceCodec codec, ScheduleNetworkRouteResolver routes, TaskExecutor workers,
                              ScheduleWorkConcurrencyLimiter concurrency, ObjectMapper json, DatabaseAvailability database) {
        this.capabilities = capabilities; this.schedules = schedules; this.scheduledRuns = scheduledRuns;
        this.transactions = transactions; this.config = config; this.codec = codec; this.routes = routes;
        this.workers = workers; this.concurrency = concurrency; this.json = json; this.database = database;
    }

    public record Request(String name, String sourceType, String activationToken, String definitionJson, String proxy) {
        @Override public String toString() { return "BatchRequest[redacted]"; }
    }
    public enum State { RUNNING, PAUSING, CANCELLING, PAUSED, CANCELLED, COMPLETED, FAILED, SUSPENDED, RETRY_LATER }
    public record View(UUID id, String name, String sourceType, State state,
                       @Schema(types = {"string", "null"}, examples = {"batch.work-failed", "batch.work.capacity-exceeded",
                               "schedule.error.source-publication-changed"}) String code,
                       @Schema(types = {"integer", "null"}, format = "int64", minimum = "0") Long retryAfterMillis,
                       @Schema(types = {"string", "null"}) String programPermission,
                       @top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable String field,
                       @top.sywyar.pixivdownload.plugin.api.web.HttpApiNullable String reason, int run,
                       long createdTime, int total, long completed, long skipped, long failed) {}
    public record Items(int offset, int total, List<ScheduleRunQueue.Item> items) {}
    public record Resolve(
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, minimum = "1") int expectedRun,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workType,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String workId,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED) String reasonCode,
            @Schema(requiredMode = Schema.RequiredMode.REQUIRED, pattern = "[A-Z][A-Z0-9_]{0,63}") String userAction,
            boolean rememberForRun) {}

    public synchronized View credentials(UUID id, int expectedRun, String credential) {
        Batch batch = require(id);
        if (!running || active(batch) || expectedRun != batch.run || batch.state == State.COMPLETED)
            throw new IllegalStateException("batch.not-idle");
        Runnable replace = () -> {
            var candidates = credentialCandidates(credential);
            batch.clearCredentials();
            batch.secrets.addAll(candidates);
            batch.task = null;
            batch.prepared = false;
        };
        if (batch.task != null && batch.task.credentialSecretReference() != null) {
            ScheduleCredentialService.withMutableBinding(capabilities, batch.task, replace);
        } else {
            replace.run();
        }
        LOG.info("Manual batch credentials replaced id={} run={} present={}", id, batch.run, !batch.secrets.isEmpty());
        return view(batch);
    }

    private static List<char[]> credentialCandidates(String credential) {
        List<String> candidates = credential == null ? List.of() : AcquisitionCredentials.candidates(credential);
        return candidates.isEmpty() ? List.of() : new AcquisitionCredentials(candidates).copySecrets();
    }

    public synchronized View resolve(UUID id, Resolve request) {
        Batch batch = require(id);
        if (active(batch) || request.expectedRun() != batch.run) throw new IllegalStateException("batch.not-idle");
        var key = new ScheduledWorkKey(request.workType(), request.workId());
        synchronized (batch) {
            var row = batch.pending.get(key);
            if (row == null || !Objects.equals(request.reasonCode(), row.reasonCode())
                    || !json.readTree(row.reasonDetailJson()).path("requiresUserAction").asBoolean(false))
                throw new IllegalStateException("batch.action-changed");
            if (!row.acceptsUserAction(request.userAction())) throw new InputFieldException("/userAction", "invalid_value");
            batch.pending.put(key, new ScheduledPendingWork(row.taskId(), row.workType(), row.workId(), row.payloadSchema(),
                    row.payloadVersion(), row.payloadJson(), row.relationsJson(), row.presentationJson(), row.reasonCode(),
                    json.writeValueAsString(Map.of("requiresUserAction", false, "userAction", request.userAction(), "rememberForRun", request.rememberForRun())),
                    row.attempts(), row.firstSeenTime(), row.lastAttemptTime()));
        }
        return view(batch);
    }

    public View create(Request request, String credential) {
        return create(request, credential, null);
    }

    @Override
    public UUID submit(String sourceOwner, String sourceType, String name, String definitionJson,
                                    String credential, ProgramIdentity program) {
        requirePermission(program, "core", "batches.create");
        try (var planning = capabilities.prepareSource(sourceType).orElseThrow(
                () -> new NoSuchElementException("batch.not-found"))) {
            if (!planning.owner().featurePluginId().equals(sourceOwner)) throw new NoSuchElementException("batch.not-found");
            var command = new Request(name, sourceType, planning.activationToken(), definitionJson, null);
            return create(command, credential, program).id();
        } catch (NoSuchElementException missing) {
            throw new top.sywyar.pixivdownload.i18n.LocalizedException(org.springframework.http.HttpStatus.NOT_FOUND,
                    "batch.not-found", "Batch source not found");
        } catch (IllegalStateException busy) {
            if (!"batch.exists".equals(busy.getMessage()) && !"batch.stopping".equals(busy.getMessage())) throw busy;
            throw new top.sywyar.pixivdownload.i18n.LocalizedException(org.springframework.http.HttpStatus.CONFLICT,
                    busy.getMessage(), "Batch unavailable");
        }
    }

    public View create(Request request, String credential, ProgramIdentity program) {
        database.requireAvailable();
        if (request == null) throw new IllegalArgumentException();
        if (request.name() == null || request.name().isBlank() || request.name().length() > 200
                || request.name().chars().anyMatch(Character::isISOControl))
            throw new InputFieldException("/name", "invalid_value");
        if (request.sourceType() == null || !request.sourceType().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}"))
            throw new InputFieldException("/sourceType", "invalid_value");
        if (request.activationToken() == null || request.activationToken().isBlank() || request.activationToken().length() > 128)
            throw new InputFieldException("/activationToken", "invalid_value");
        if (request.definitionJson() == null) throw new InputFieldException("/definition", "required");
        if (request.definitionJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ScheduledTaskDefinition.MAX_DEFINITION_BYTES)
            throw new InputFieldException("/definition", "too_large");
        if (request.proxy() != null && (request.proxy().length() > 512
                || !request.proxy().isBlank() && top.sywyar.pixivdownload.config.OutboundProxyOverride.parse(request.proxy()) == null))
            throw new InputFieldException("/proxy", "invalid_value");
        synchronized (this) {
            if (!running) throw new IllegalStateException("batch.stopping");
            if (current != null || creating) throw new IllegalStateException("batch.exists");
            creating = true;
        }
        Batch batch = null;
        try {
            requireSource(request.sourceType(), program);
            batch = new Batch(request, credential, program);
            try {
                prepareDefinition(batch);
            } catch (ScheduleSourceUnavailableException | ScheduleSourcePublicationChangedException | ScheduledExecutionException deferred) {
                // Execution-time capability failures keep their existing asynchronous batch outcome.
            }
            synchronized (this) {
                if (!running) throw new IllegalStateException("batch.stopping");
                launch(batch, false);
                current = batch;
                return view(batch);
            }
        } finally {
            synchronized (this) {
                if (batch != null && current != batch) batch.clearCredentials();
                creating = false;
                notifyAll();
            }
        }
    }

    private void requireSource(String sourceType, ProgramIdentity program) {
        if (program == null) return;
        try (var planning = capabilities.prepareSource(sourceType).orElseThrow(
                () -> new NoSuchElementException("batch.not-found"))) {
            requirePermission(program, planning.owner().featurePluginId(), "batches.execute");
        }
    }

    private static void requirePermission(ProgramIdentity program, String owner, String permission) {
        if (program != null && !program.permits(owner, permission))
            throw new ProgramAccess.MissingPermission(owner, permission);
    }

    public synchronized void authorize(UUID id, ProgramIdentity program) {
        if (program != null && !program.id().equals(require(id).programId))
            throw new NoSuchElementException("batch.not-found");
    }

    public synchronized View current(ProgramIdentity program) {
        return current == null || program != null && !program.id().equals(current.programId) ? null : view(current);
    }

    public synchronized View current() { return current == null ? null : view(current); }
    public synchronized View get(UUID id) { return view(require(id)); }
    public synchronized Items items(UUID id, int offset, int limit) {
        InvalidPage.check(offset, limit, 200, null);
        return require(id).ledger.page(offset, limit);
    }

    public synchronized View stop(UUID id, boolean pause) {
        Batch batch = require(id);
        if (!active(batch)) {
            if (!pause && batch.state != State.COMPLETED) {
                batch.state = State.CANCELLED;
                batch.retryAfterMillis = null;
            }
            return view(batch);
        }
        batch.state = pause ? State.PAUSING : State.CANCELLING;
        batch.runs.requestCancel(1);
        return view(batch);
    }

    public synchronized View resume(UUID id, boolean failedOnly) {
        return resume(id, failedOnly, null);
    }

    public synchronized View resume(UUID id, boolean failedOnly, ProgramIdentity program) {
        authorize(id, program);
        Batch batch = require(id);
        if (!running || active(batch) || batch.state == State.COMPLETED) throw new IllegalStateException("batch.not-idle");
        requireSource(batch.request.sourceType(), program);
        batch.program = program;
        if (failedOnly) {
            synchronized (batch) {
                var retry = batch.ledger.retryRows(1, codec, batch.pending);
                if (retry.isEmpty()) throw new IllegalStateException("batch.no-failed-work");
                batch.pending.clear();
                retry.forEach(batch::upsertPendingWork);
            }
        }
        launch(batch, failedOnly);
        return view(batch);
    }

    public synchronized void delete(UUID id) {
        Batch batch = require(id);
        if (active(batch)) throw new IllegalStateException("batch.not-idle");
        batch.clearCredentials();
        current = null;
    }

    private void launch(Batch batch, boolean pendingOnly) {
        batch.state = State.RUNNING;
        batch.code = null;
        batch.retryAfterMillis = null;
        batch.programPermission = null;
        batch.field = null;
        batch.reason = null;
        batch.ledger.resetCapacityFailure();
        batch.run++;
        var claim = batch.runs.tryMarkRunning(1);
        if (claim == null) throw new IllegalStateException("batch.not-idle");
        var thread = Thread.ofPlatform().name("manual-batch-" + batch.id).unstarted(() -> execute(batch, claim, pendingOnly));
        try { thread.start(); }
        catch (RuntimeException failure) {
            batch.runs.clear(claim); batch.state = State.FAILED; batch.code = "batch.dispatch-failed";
            throw failure;
        }
        LOG.info("Manual batch accepted id={} run={} retryOnly={}", batch.id, batch.run, pendingOnly);
    }

    private void execute(Batch batch, ScheduleRunState.Claim claim, boolean pendingOnly) {
        String code = null;
        Long retryAfterMillis = null;
        String missingPermission = null;
        InputFieldException invalidInput = null;
        State result = State.COMPLETED;
        long started = System.nanoTime();
        try {
            if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
            if (!batch.prepared) prepare(batch);
            if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
            if (!pendingOnly) batch.discoveryComplete = false;
            var execution = batch.engine.executeBatch(batch.task, batch.request.activationToken(), pendingOnly,
                    decision -> suspendAccount(batch, decision));
            if (!pendingOnly) batch.discoveryComplete = true;
            if (execution.credentialRevoked()) batch.clearCredentials();

        } catch (ScheduleExecutionControlException failure) {
            result = switch (failure.action()) {
                case SUSPEND_CREDENTIAL, SUSPEND_POLICY_TASK, SUSPEND_POLICY_ACCOUNT -> State.SUSPENDED;
                case RETRY_LATER -> State.RETRY_LATER;
                case FAIL, CONTINUE, REVOKE_CREDENTIAL_AND_CONTINUE -> State.FAILED;
            };
            code = failure.reasonCode();
            if (result == State.RETRY_LATER) retryAfterMillis = failure.retryAfterMillis();
        } catch (ScheduledExecutionException failure) {
            result = failure.category() == ScheduledFailure.Category.CANCELLED ? State.CANCELLED : State.FAILED;
            code = failure.code();
            if (failure instanceof ScheduleExecutionEngine.OwnerDenied denied) missingPermission = denied.owner() + ":batches.execute";
        } catch (ScheduleSourcePublicationChangedException failure) {
            result = State.SUSPENDED; code = "schedule.error.source-publication-changed";
        } catch (ScheduleSourceUnavailableException | ScheduleExecutorUnavailableException failure) {
            result = State.SUSPENDED; code = "schedule.error.execution-capability-unavailable";
        } catch (ProgramAccess.MissingPermission failure) {
            missingPermission = failure.permission();
            result = State.FAILED; code = "auth.program.forbidden";
        } catch (InputFieldException failure) {
            invalidInput = failure;
            result = State.FAILED; code = "schedule.error.definition-invalid";
        } catch (ScheduleDefinitionException | IllegalArgumentException failure) {
            result = State.FAILED; code = "schedule.error.definition-invalid";
        } catch (Throwable failure) {
            result = State.FAILED; code = "batch.execution-failed";
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            LOG.warn("Manual batch failed id={} run={} type={}", batch.id, batch.run, failure.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                synchronized (batch) { batch.ledger.pauseUndispatched(1, codec, batch.pending); }
                if (result == State.COMPLETED) {
                    var counts = batch.ledger.counts();
                    if (counts.failed() > counts.undispatched()) {
                        result = State.FAILED; code = "batch.work-failed";
                    } else if (counts.completed() + counts.skipped() < counts.total() || !batch.discoveryComplete) {
                        result = State.PAUSED; code = BatchWorkLedger.DISCOVERY_INCOMPLETE;
                    }
                }
                if (batch.state == State.PAUSING) result = State.PAUSED;
                else if (batch.state == State.CANCELLING) result = State.CANCELLED;
                if (batch.ledger.capacityExceeded()) code = "batch.work.capacity-exceeded";
                batch.state = result; batch.code = code;
                batch.retryAfterMillis = result == State.RETRY_LATER ? retryAfterMillis : null;
                if (invalidInput != null && "schedule.error.definition-invalid".equals(code)) {
                    batch.field = invalidInput.field();
                    batch.reason = invalidInput.reason();
                }
                batch.programPermission = "auth.program.forbidden".equals(code) ? missingPermission : null;
                batch.runs.clear(claim);
                View view = view(batch);
                LOG.info("Manual batch finished id={} run={} state={} code={} total={} completed={} skipped={} failed={} elapsedMs={}",
                        batch.id, batch.run, result, code, view.total(), view.completed(), view.skipped(), view.failed(),
                        (System.nanoTime() - started) / 1_000_000);
                notifyAll();
            }
        }
    }

    private void prepareDefinition(Batch batch) throws ScheduleSourceUnavailableException,
            ScheduleSourcePublicationChangedException, ScheduledExecutionException {
        database.requireAvailable();
        try (var planning = capabilities.prepareSource(batch.request.sourceType()).orElseThrow(
                () -> new ScheduleSourceUnavailableException(batch.request.sourceType()))) {
            requirePermission(batch.program, planning.owner().featurePluginId(), "batches.execute");
            if (!batch.request.activationToken().equals(planning.activationToken()))
                throw new ScheduleSourcePublicationChangedException(batch.request.sourceType());
            if (!capabilities.activate(planning)) throw new ScheduleSourceUnavailableException(batch.request.sourceType());
            var descriptor = planning.descriptor().orElseThrow(() -> new ScheduleSourceUnavailableException(batch.request.sourceType()));
            var source = planning.sourceExecutor().orElseThrow(() -> new ScheduleSourceUnavailableException(batch.request.sourceType()));
            var draft = new ScheduledTaskDraft(1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion(),
                    batch.request.definitionJson(), new ScheduledTaskPresentation(batch.request.name(), null, Map.of()));
            var validator = new ScheduleTaskDefinitionValidator(json);
            validator.validatePrepared(draft.toDefinition(), 1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion());
            if (descriptor.inputSchemaJson() != null)
                BatchDefinitionSchema.validate(json, descriptor.inputSchemaJson(), draft.definitionJson());
            planning.cancellation().throwIfCancellationRequested();
            batch.definition = validator.validatePrepared(source.prepare(draft), 1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion());
            var plan = source.plan(batch.definition);
            ScheduleExecutionPlanGate.validate(descriptor, plan);
            planning.cancellation().throwIfCancellationRequested();
            batch.owner = planning.owner().featurePluginId();
        } catch (ScheduledExecutionException failure) {
            if (failure.category() != ScheduledFailure.Category.INVALID_DEFINITION
                    && failure.category() != ScheduledFailure.Category.PAYLOAD_UNSUPPORTED) throw failure;
            throw new InputFieldException("/definition", "invalid_value");
        } catch (InputFieldException | top.sywyar.pixivdownload.i18n.LocalizedException failure) {
            throw failure;
        } catch (IllegalArgumentException failure) {
            throw new InputFieldException("/definition", "invalid_value");
        }
    }

    private void prepare(Batch batch) throws Exception {
        if (batch.owner == null) prepareDefinition(batch);
        var unbound = batch.task(null, null, null, null);
        if (batch.secrets.isEmpty()) {
            batch.task = unbound;
        } else {
            try (var binding = batch.engine.prepareCredentialBinding(unbound, batch.request.activationToken())) {
                if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
                List<String> candidates = batch.secrets.stream().map(String::new).toList();
                var results = binding.probeCandidates(candidates);
                for (var bound : results) {
                    var probe = bound.probeResult();
                    if (probe.status() == ScheduledCredentialProbeResult.Status.RETRY_LATER)
                        throw new ScheduleExecutionControlException(ScheduledGuardDecision.Action.RETRY_LATER,
                                probe.code(), probe.retryAfterMillis(), null);
                    if (probe.status() != ScheduledCredentialProbeResult.Status.VALID)
                        throw new ScheduledExecutionException(ScheduledFailure.Category.CREDENTIAL_INVALID, probe.code());
                }
                batch.accounts = results.stream().map(value -> value.probeResult().accountKey()).toList();
                var first = results.getFirst();
                batch.task = batch.task(binding.policyOwnerPluginId(), binding.policyId(), first.probeResult().accountKey(), first.initialPolicyStateJson());
                // 探活已确认账号，即使绑定后的策略立即挂起，仍须保护这次绑定。
                for (var bound : results) {
                    var decision = bound.postBindResult().decision();
                    if (decision.action() != ScheduledGuardDecision.Action.CONTINUE)
                        throw new ScheduleExecutionControlException(decision.action(), decision.reasonCode(),
                                decision.retryAfterMillis(), bound.postBindResult().evidence());
                }
            }
        }
        batch.prepared = true;
    }

    private void suspendAccount(Batch batch, ScheduleExecutionControlException decision) {
        var task = batch.task;
        if (task.credentialAccountKey() == null) throw new IllegalStateException("batch.account-unbound");
        List<Long> affected = transactions.execute(status -> {
            List<Long> ids = new ArrayList<>();
            for (var candidate : schedules.findByCredentialAccount(task.credentialPolicyOwnerPluginId(), task.credentialPolicyId(), task.credentialAccountKey())) {
                if (candidate.suspendReason() != null) continue;
                if (schedules.suspend(candidate.id(), candidate.stateVersion(), ScheduleSuspendReason.POLICY,
                        decision.reasonCode(), json.writeValueAsString(decision.evidence().attributes())).isEmpty())
                    throw new IllegalStateException("batch.account-changed");
                ids.add(candidate.id());
            }
            return ids;
        });
        Objects.requireNonNull(affected).forEach(scheduledRuns::requestCancel);
    }

    private Batch require(UUID id) {
        if (current == null || !current.id.equals(id)) throw new NoSuchElementException("batch.not-found");
        return current;
    }
    private static boolean active(Batch batch) { return batch.runs.get(1) != null; }
    private static View view(Batch batch) {
        var counts = batch.ledger.counts();
        return new View(batch.id, batch.request.name(), batch.request.sourceType(), batch.state, batch.code, batch.retryAfterMillis, batch.programPermission, batch.field, batch.reason, batch.run, batch.created,
                counts.total(), counts.completed(), counts.skipped(), counts.failed());
    }

    @Override public synchronized void start() { running = true; }
    @Override public synchronized boolean isRunning() { return running; }
    @Override public int getPhase() { return Integer.MAX_VALUE; }
    @Override public void stop(Runnable callback) { stop(); callback.run(); }
    @Override public void stop() {
        boolean interrupted = false;
        synchronized (this) {
            running = false;
            if (current != null && active(current)) stop(current.id, false);
            while (creating || current != null && active(current)) {
                try { wait(); } catch (InterruptedException failure) { interrupted = true; }
            }
            if (current != null) current.clearCredentials();
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private final class Batch implements ScheduledWorkStore {
        final UUID id = UUID.randomUUID();
        final long created = System.currentTimeMillis();
        final Request request;
        final UUID programId;
        ProgramIdentity program;
        final ScheduleRunState runs = new ScheduleRunState();
        final BatchWorkLedger ledger = new BatchWorkLedger(json);
        final Map<ScheduledWorkKey, ScheduledPendingWork> pending = new LinkedHashMap<>();
        final List<char[]> secrets;
        final ScheduleExecutionEngine engine;
        List<String> accounts = List.of();
        ScheduledTaskDefinition definition;
        ScheduledTask task;
        boolean prepared;
        boolean discoveryComplete;
        String owner;
        State state;
        String code;
        Long retryAfterMillis;
        String programPermission;
        String field;
        String reason;
        int run;

        Batch(Request request, String credential, ProgramIdentity program) {
            this.request = request;
            this.program = program;
            this.programId = program == null ? null : program.id();
            this.secrets = new ArrayList<>(credentialCandidates(credential));
            this.engine = new ScheduleExecutionEngine(this, capabilities, runs, ledger, config, codec, routes, workers, concurrency, json, database,
                    owner -> this.program == null || this.program.permits(owner, "batches.execute"));
        }
        ScheduledTask task(String policyOwner, String policy, String account, String policyState) {
            return new ScheduledTask(1L, request.name(), false, definition.sourceType(), owner, definition.definitionSchema(), definition.definitionVersion(),
                    definition.definitionJson(), json.writeValueAsString(definition.presentation()), null, null, null, request.proxy(), null, null,
                    null, null, null, ScheduledTask.CURRENT_STORAGE_VERSION, null, null, ScheduleLastOutcome.NEVER, null, null,
                    null, null, null, 0, policyOwner, policy, account, policyState, policy == null ? null : id.toString(), created);
        }
        synchronized void clearCredentials() { secrets.forEach(value -> Arrays.fill(value, '\0')); secrets.clear(); accounts = List.of(); }
        @Override public synchronized int upsertPendingWork(ScheduledPendingWork row) { pending.put(new ScheduledWorkKey(row.workType(), row.workId()), row); return 1; }
        @Override public synchronized List<ScheduledPendingWork> listPendingWork(long id) { return List.copyOf(pending.values()); }
        @Override public synchronized int deletePendingWork(long id, String type, String key) { return pending.remove(new ScheduledWorkKey(type, key)) == null ? 0 : 1; }
        @Override public synchronized List<ScheduledCredentialValue> findCredentialCandidates(long id, String owner, String policy) {
            if (task == null || !Objects.equals(owner, task.credentialPolicyOwnerPluginId()) || !Objects.equals(policy, task.credentialPolicyId())) return List.of();
            List<ScheduledCredentialValue> values = new ArrayList<>();
            for (int i = 0; i < secrets.size(); i++) values.add(new ScheduledCredentialValue(accounts.get(i), new String(secrets.get(i))));
            return values;
        }
        @Override public List<ScheduledTask> findByCredentialAccount(String owner, String policy, String account) {
            return schedules.findByCredentialAccount(owner, policy, account);
        }
    }
}
