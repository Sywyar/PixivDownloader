package top.sywyar.pixivdownload.schedule.batch;

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
public final class ManualBatchService implements SmartLifecycle {
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
    public enum State { RUNNING, PAUSING, CANCELLING, PAUSED, CANCELLED, COMPLETED, FAILED, SUSPENDED }
    public record View(UUID id, String name, String sourceType, State state, String code, int run,
                       long createdTime, int total, long completed, long skipped, long failed) {}
    public record Items(int offset, int total, List<ScheduleRunQueue.Item> items) {}
    public record Resolve(int expectedRun, String workType, String workId, String reasonCode, String userAction, boolean rememberForRun) {}

    public synchronized View credentials(UUID id, int expectedRun, String credential) {
        Batch batch = require(id);
        if (!running || active(batch) || expectedRun != batch.run || batch.state == State.COMPLETED)
            throw new IllegalStateException("batch.not-idle");
        var candidates = credentialCandidates(credential);
        batch.clearCredentials();
        batch.secrets.addAll(candidates);
        batch.prepared = false;
        LOG.info("Manual batch credentials replaced id={} run={} present={}", id, batch.run, !candidates.isEmpty());
        return view(batch);
    }

    private static List<char[]> credentialCandidates(String credential) {
        List<String> candidates = credential == null ? List.of() : AcquisitionCredentials.candidates(credential);
        return candidates.isEmpty() ? List.of() : new AcquisitionCredentials(candidates).copySecrets();
    }

    public synchronized View resolve(UUID id, Resolve request) {
        Batch batch = require(id);
        if (active(batch) || request.expectedRun() != batch.run) throw new IllegalStateException("batch.not-idle");
        if (request.userAction() == null || !request.userAction().matches("[A-Z][A-Z0-9_]{0,63}")) throw new IllegalArgumentException();
        var key = new ScheduledWorkKey(request.workType(), request.workId());
        synchronized (batch) {
            var row = batch.pending.get(key);
            if (row == null || !Objects.equals(request.reasonCode(), row.reasonCode())
                    || !json.readTree(row.reasonDetailJson()).path("requiresUserAction").asBoolean(false))
                throw new IllegalStateException("batch.action-changed");
            batch.pending.put(key, new ScheduledPendingWork(row.taskId(), row.workType(), row.workId(), row.payloadSchema(),
                    row.payloadVersion(), row.payloadJson(), row.relationsJson(), row.presentationJson(), row.reasonCode(),
                    json.writeValueAsString(Map.of("requiresUserAction", true, "userAction", request.userAction(), "rememberForRun", request.rememberForRun())),
                    row.attempts(), row.firstSeenTime(), row.lastAttemptTime()));
        }
        return view(batch);
    }

    public synchronized View create(Request request, String credential) {
        return create(request, credential, null);
    }

    public synchronized View create(Request request, String credential, ProgramIdentity program) {
        if (!running) throw new IllegalStateException("batch.stopping");
        database.requireAvailable();
        if (current != null) throw new IllegalStateException("batch.exists");
        if (request == null || request.name() == null || request.name().isBlank() || request.name().length() > 200
                || request.name().chars().anyMatch(Character::isISOControl)
                || request.sourceType() == null || !request.sourceType().matches("[a-zA-Z0-9][a-zA-Z0-9._-]{0,127}")
                || request.activationToken() == null || request.activationToken().isBlank() || request.activationToken().length() > 128
                || request.definitionJson() == null
                || request.definitionJson().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ScheduledTaskDefinition.MAX_DEFINITION_BYTES)
            throw new IllegalArgumentException();
        if (request.proxy() != null && (request.proxy().length() > 512
                || !request.proxy().isBlank() && top.sywyar.pixivdownload.config.OutboundProxyOverride.parse(request.proxy()) == null))
            throw new IllegalArgumentException();
        requireSource(request.sourceType(), program);
        Batch batch = new Batch(request, credential, program);
        current = batch;
        launch(batch, false);
        return view(batch);
    }

    private void requireSource(String sourceType, ProgramIdentity program) {
        if (program == null) return;
        try (var planning = capabilities.prepareSource(sourceType).orElseThrow(
                () -> new NoSuchElementException("batch.not-found"))) {
            requirePermission(program, planning.owner().featurePluginId());
        }
    }

    private static void requirePermission(ProgramIdentity program, String owner) {
        if (program != null && !program.permits(owner, "batches.execute"))
            throw new top.sywyar.pixivdownload.i18n.LocalizedException(org.springframework.http.HttpStatus.FORBIDDEN,
                    "auth.program.forbidden", "The program key does not grant this operation");
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
        if (offset < 0 || limit < 1 || limit > 200) throw new IllegalArgumentException();
        return require(id).ledger.page(offset, limit);
    }

    public synchronized View stop(UUID id, boolean pause) {
        Batch batch = require(id);
        if (!active(batch)) {
            if (!pause && batch.state != State.COMPLETED) batch.state = State.CANCELLED;
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
            var retry = batch.ledger.retryRows(1, codec);
            if (retry.isEmpty()) throw new IllegalStateException("batch.no-failed-work");
            synchronized (batch) {
                var previous = new LinkedHashMap<>(batch.pending);
                batch.pending.clear();
                retry.forEach(row -> {
                    var old = previous.get(new ScheduledWorkKey(row.workType(), row.workId()));
                    batch.upsertPendingWork(old == null ? row : new ScheduledPendingWork(row.taskId(), row.workType(), row.workId(), row.payloadSchema(),
                            row.payloadVersion(), row.payloadJson(), row.relationsJson(), row.presentationJson(), row.reasonCode(),
                            old.reasonDetailJson(), 0, old.firstSeenTime(), old.lastAttemptTime()));
                });
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
        State result = State.COMPLETED;
        long started = System.nanoTime();
        try {
            if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
            if (!batch.prepared) prepare(batch);
            if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
            var execution = batch.engine.executeBatch(batch.task, batch.request.activationToken(), pendingOnly,
                    decision -> suspendAccount(batch, decision));
            if (!pendingOnly) batch.discoveryComplete = true;
            if (execution.credentialRevoked()) batch.clearCredentials();
            if (batch.ledger.counts().failed() > 0) {
                result = State.FAILED;
                code = "batch.work-failed";
            } else if (!batch.discoveryComplete) {
                result = State.PAUSED;
                code = "batch.discovery-incomplete";
            }
        } catch (ScheduleExecutionControlException failure) {
            result = State.SUSPENDED; code = failure.reasonCode();
        } catch (ScheduledExecutionException failure) {
            result = failure.category() == ScheduledFailure.Category.CANCELLED ? State.CANCELLED : State.FAILED;
            code = failure.code();
        } catch (ScheduleSourcePublicationChangedException failure) {
            result = State.SUSPENDED; code = "schedule.error.source-publication-changed";
        } catch (ScheduleSourceUnavailableException | ScheduleExecutorUnavailableException failure) {
            result = State.SUSPENDED; code = "schedule.error.execution-capability-unavailable";
        } catch (top.sywyar.pixivdownload.i18n.LocalizedException failure) {
            result = State.FAILED; code = "auth.program.forbidden";
        } catch (ScheduleDefinitionException | IllegalArgumentException failure) {
            result = State.FAILED; code = "schedule.error.definition-invalid";
        } catch (Throwable failure) {
            result = State.FAILED; code = "batch.execution-failed";
            if (failure instanceof VirtualMachineError fatal) throw fatal;
            if (failure instanceof ThreadDeath fatal) throw fatal;
            LOG.warn("Manual batch failed id={} run={} type={}", batch.id, batch.run, failure.getClass().getSimpleName());
        } finally {
            synchronized (this) {
                if (batch.state == State.PAUSING) result = State.PAUSED;
                else if (batch.state == State.CANCELLING) result = State.CANCELLED;
                if (batch.ledger.capacityExceeded()) code = "batch.work.capacity-exceeded";
                batch.state = result; batch.code = code;
                batch.runs.clear(claim);
                View view = view(batch);
                LOG.info("Manual batch finished id={} run={} state={} code={} total={} completed={} skipped={} failed={} elapsedMs={}",
                        batch.id, batch.run, result, code, view.total(), view.completed(), view.skipped(), view.failed(),
                        (System.nanoTime() - started) / 1_000_000);
                notifyAll();
            }
        }
    }

    private void prepare(Batch batch) throws Exception {
        database.requireAvailable();
        try (var planning = capabilities.prepareSource(batch.request.sourceType()).orElseThrow(
                () -> new ScheduleSourceUnavailableException(batch.request.sourceType()))) {
            requirePermission(batch.program, planning.owner().featurePluginId());
            if (!batch.request.activationToken().equals(planning.activationToken()))
                throw new ScheduleSourcePublicationChangedException(batch.request.sourceType());
            if (!capabilities.activate(planning)) throw new ScheduleSourceUnavailableException(batch.request.sourceType());
            var descriptor = planning.descriptor().orElseThrow();
            var source = planning.sourceExecutor().orElseThrow();
            var draft = new ScheduledTaskDraft(1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion(),
                    batch.request.definitionJson(), new ScheduledTaskPresentation(batch.request.name(), null, Map.of()));
            var validator = new ScheduleTaskDefinitionValidator(json);
            validator.validatePrepared(draft.toDefinition(), 1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion());
            planning.cancellation().throwIfCancellationRequested();
            batch.definition = validator.validatePrepared(source.prepare(draft), 1, planning.sourceType(), descriptor.definitionSchema(), descriptor.definitionVersion());
            var plan = source.plan(batch.definition);
            ScheduleExecutionPlanGate.validate(descriptor, plan);
            planning.cancellation().throwIfCancellationRequested();
            batch.owner = planning.owner().featurePluginId();
            batch.task = batch.task(null, null, null, null);
        } catch (ScheduledExecutionException failure) {
            if (failure.category() == ScheduledFailure.Category.CANCELLED) throw ScheduledExecutionException.cancelled();
            throw new ScheduleDefinitionException("invalid batch source definition");
        } catch (top.sywyar.pixivdownload.i18n.LocalizedException failure) {
            throw failure;
        } catch (RuntimeException failure) {
            throw new ScheduleDefinitionException("invalid batch source definition");
        }
        if (!batch.secrets.isEmpty()) {
            try (var binding = batch.engine.prepareCredentialBinding(batch.task, batch.request.activationToken())) {
                if (batch.runs.isCancelRequested(1)) throw ScheduledExecutionException.cancelled();
                List<String> candidates = batch.secrets.stream().map(String::new).toList();
                var results = binding.probeCandidates(candidates);
                for (var bound : results) {
                    if (bound.probeResult().status() != ScheduledCredentialProbeResult.Status.VALID)
                        throw new ScheduledExecutionException(ScheduledFailure.Category.CREDENTIAL_INVALID, bound.probeResult().code());
                    if (bound.postBindResult().decision().action() != ScheduledGuardDecision.Action.CONTINUE)
                        throw new ScheduleExecutionControlException(bound.postBindResult().decision().action(),
                                bound.postBindResult().decision().reasonCode(), 0, bound.postBindResult().evidence());
                }
                batch.accounts = results.stream().map(value -> value.probeResult().accountKey()).toList();
                var first = results.getFirst();
                batch.task = batch.task(binding.policyOwnerPluginId(), binding.policyId(), first.probeResult().accountKey(), first.initialPolicyStateJson());
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
        return new View(batch.id, batch.request.name(), batch.request.sourceType(), batch.state, batch.code, batch.run, batch.created,
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
            while (current != null && active(current)) {
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
