package top.sywyar.pixivdownload.core.schedule.db;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import top.sywyar.pixivdownload.core.db.schema.DatabaseInitializer;
import top.sywyar.pixivdownload.core.schedule.ScheduleTaskDefinitionUpdate;
import top.sywyar.pixivdownload.core.schedule.ScheduledPendingWork;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskCreate;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleLastOutcome;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunCompletion;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunState;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleRunToken;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** {@link ScheduledTaskStore} 的核心 MyBatis 实现。 */
@Repository
@RequiredArgsConstructor
public class ScheduledTaskStoreImpl implements ScheduledTaskStore {

    private final ScheduledTaskMapper mapper;
    /** 仅表达表结构初始化必须先完成。 */
    @SuppressWarnings("unused")
    private final DatabaseInitializer databaseInitializer;

    @PostConstruct
    public void init() {
        mapper.recoverInterruptedRuns(System.currentTimeMillis());
    }

    @Override
    public List<ScheduledTask> findAll() {
        return mapper.findAll();
    }

    @Override
    public ScheduledTask findById(long id) {
        return mapper.findById(id);
    }

    @Override
    public int countAll() {
        return mapper.countAll();
    }

    @Override
    public List<ScheduledTask> findDue(long now) {
        return mapper.findDue(now);
    }

    @Override
    public List<ScheduledTask> findByCredentialAccount(String policyOwnerPluginId,
                                                       String policyId,
                                                       String accountKey) {
        return mapper.findByCredentialAccount(policyOwnerPluginId, policyId, accountKey);
    }

    @Override
    @Transactional
    public long create(ScheduledTaskCreate command) {
        Objects.requireNonNull(command, "command");
        ScheduledTaskInsertRow row = new ScheduledTaskInsertRow();
        row.setName(command.name());
        row.setSourceType(command.sourceType());
        row.setSourceOwnerPluginId(command.sourceOwnerPluginId());
        row.setDefinitionSchema(command.definitionSchema());
        row.setDefinitionVersion(command.definitionVersion());
        row.setDefinitionJson(command.definitionJson());
        row.setPresentationJson(command.presentationJson());
        row.setTriggerKind(command.triggerKind());
        row.setIntervalMinutes(command.intervalMinutes());
        row.setCronExpr(command.cronExpr());
        row.setNextRunTime(command.nextRunTime());
        row.setCreatedTime(command.createdTime());
        mapper.insert(row);
        if (row.getId() == null || row.getId() <= 0) {
            throw new IllegalStateException("scheduled task insert did not return a generated id");
        }
        return row.getId();
    }

    @Override
    public Optional<ScheduleRunToken> tryQueueDue(long id,
                                                  long expectedStateVersion,
                                                  String claimToken,
                                                  long now) {
        requireText(claimToken, "claim token");
        return Optional.ofNullable(mapper.tryQueueDue(id, expectedStateVersion, claimToken, now));
    }

    @Override
    public Optional<ScheduleRunToken> tryQueueNow(long id,
                                                  long expectedStateVersion,
                                                  String claimToken) {
        requireText(claimToken, "claim token");
        return Optional.ofNullable(mapper.tryQueueNow(id, expectedStateVersion, claimToken));
    }

    @Override
    public Optional<ScheduleRunToken> startRun(long id, ScheduleRunToken queuedToken) {
        requireTokenState(queuedToken, ScheduleRunState.QUEUED);
        return Optional.ofNullable(mapper.startRun(id, queuedToken));
    }

    @Override
    public OptionalLong completeRun(long id,
                                    ScheduleRunToken runningToken,
                                    ScheduleRunCompletion completion) {
        requireTokenState(runningToken, ScheduleRunState.RUNNING);
        Objects.requireNonNull(completion, "completion");
        return optionalLong(mapper.completeRun(id, runningToken, completion));
    }

    @Override
    public OptionalLong finishCancelled(long id,
                                        ScheduleRunToken activeToken,
                                        ScheduleLastOutcome outcome,
                                        long finishedTime,
                                        String outcomeCode,
                                        String outcomeMessage,
                                        Long nextRunTime) {
        Objects.requireNonNull(activeToken, "activeToken");
        if (activeToken.runState() != ScheduleRunState.RUNNING
                && activeToken.runState() != ScheduleRunState.QUEUED
                && activeToken.runState() != ScheduleRunState.CANCEL_REQUESTED) {
            throw new IllegalArgumentException("cancel completion requires an active run token");
        }
        if (outcome != ScheduleLastOutcome.CANCELLED
                && outcome != ScheduleLastOutcome.ERROR
                && outcome != ScheduleLastOutcome.INTERRUPTED) {
            throw new IllegalArgumentException("cancel completion outcome must be CANCELLED, ERROR or INTERRUPTED");
        }
        return optionalLong(mapper.finishCancelled(id, activeToken, outcome, finishedTime,
                outcomeCode, outcomeMessage, nextRunTime));
    }

    @Override
    public OptionalLong releaseQueued(long id, ScheduleRunToken queuedToken, Long nextRunTime) {
        requireTokenState(queuedToken, ScheduleRunState.QUEUED);
        return optionalLong(mapper.releaseQueued(id, queuedToken, nextRunTime));
    }

    @Override
    public OptionalLong suspend(long id,
                                long expectedStateVersion,
                                ScheduleSuspendReason reason,
                                String code,
                                String detailJson) {
        Objects.requireNonNull(reason, "reason");
        return optionalLong(mapper.suspend(id, expectedStateVersion, reason, code, detailJson));
    }

    @Override
    public OptionalLong resume(long id,
                               long expectedStateVersion,
                               ScheduleSuspendReason expectedReason,
                               String expectedCode,
                               Long nextRunTime) {
        Objects.requireNonNull(expectedReason, "expectedReason");
        return optionalLong(mapper.resume(id, expectedStateVersion, expectedReason,
                expectedCode, nextRunTime));
    }

    @Override
    public int suspendByCredentialAccount(String policyOwnerPluginId,
                                          String policyId,
                                          String accountKey,
                                          ScheduleSuspendReason reason,
                                          String code,
                                          String detailJson) {
        requireText(policyOwnerPluginId, "credential policy owner");
        requireText(policyId, "credential policy id");
        requireText(accountKey, "credential account key");
        Objects.requireNonNull(reason, "reason");
        return mapper.suspendByCredentialAccount(policyOwnerPluginId, policyId, accountKey,
                reason, code, detailJson);
    }

    @Override
    public int resumeByCredentialAccount(String policyOwnerPluginId,
                                         String policyId,
                                         String accountKey,
                                         ScheduleSuspendReason expectedReason,
                                         String expectedCode,
                                         Long nextRunTime) {
        requireText(policyOwnerPluginId, "credential policy owner");
        requireText(policyId, "credential policy id");
        requireText(accountKey, "credential account key");
        Objects.requireNonNull(expectedReason, "expectedReason");
        return mapper.resumeByCredentialAccount(policyOwnerPluginId, policyId, accountKey,
                expectedReason, expectedCode, nextRunTime);
    }

    @Override
    @Transactional
    public OptionalLong updateDefinition(long id,
                                         long expectedStateVersion,
                                         ScheduleTaskDefinitionUpdate update) {
        Objects.requireNonNull(update, "update");
        Long newVersion = mapper.updateDefinition(id, expectedStateVersion, update);
        if (newVersion == null) {
            return OptionalLong.empty();
        }
        mapper.deleteAllPendingWork(id);
        return OptionalLong.of(newVersion);
    }

    @Override
    public OptionalLong updateEnabled(long id, long expectedStateVersion, boolean enabled) {
        return optionalLong(mapper.updateEnabled(id, expectedStateVersion, enabled));
    }

    @Override
    public OptionalLong updateProxy(long id,
                                    long expectedStateVersion,
                                    String proxySnapshot) {
        return optionalLong(mapper.updateProxy(id, expectedStateVersion, proxySnapshot));
    }

    @Override
    @Transactional
    public boolean deleteAggregate(long id, long expectedStateVersion) {
        if (mapper.deleteTaskByVersion(id, expectedStateVersion) != 1) {
            return false;
        }
        mapper.deleteAllPendingWork(id);
        mapper.deleteLegacyPendingByTask(id);
        mapper.deleteCredentialByTask(id);
        return true;
    }

    @Override
    @Transactional
    public OptionalLong bindCredential(long taskId,
                                       long expectedStateVersion,
                                       String policyOwnerPluginId,
                                       String policyId,
                                       String accountKey,
                                       String policyStateJson,
                                       String secret,
                                       String secretReference,
                                       long updatedTime) {
        requireText(policyOwnerPluginId, "credential policy owner");
        requireText(policyId, "credential policy id");
        if (secret == null && secretReference == null) {
            throw new IllegalArgumentException("credential secret or reference is required");
        }
        Long newVersion = mapper.advanceIdleStateVersion(taskId, expectedStateVersion);
        if (newVersion == null) {
            return OptionalLong.empty();
        }
        mapper.upsertCredential(taskId, policyOwnerPluginId, policyId, accountKey,
                normalizePolicyState(policyStateJson), secret, secretReference, updatedTime);
        return OptionalLong.of(newVersion);
    }

    @Override
    @Transactional
    public OptionalLong removeCredential(long taskId,
                                         long expectedStateVersion,
                                         String expectedPolicyOwnerPluginId,
                                         String expectedPolicyId) {
        ScheduledCredentialBinding current = mapper.findCredentialBinding(taskId);
        if (current == null
                || !Objects.equals(current.policyOwnerPluginId(), expectedPolicyOwnerPluginId)
                || !Objects.equals(current.policyId(), expectedPolicyId)) {
            return OptionalLong.empty();
        }
        Long newVersion = mapper.advanceIdleStateVersion(taskId, expectedStateVersion);
        if (newVersion == null) {
            return OptionalLong.empty();
        }
        if (mapper.deleteCredential(taskId, expectedPolicyOwnerPluginId, expectedPolicyId) != 1) {
            throw new IllegalStateException("credential changed during version-guarded removal");
        }
        return OptionalLong.of(newVersion);
    }

    @Override
    @Transactional
    public OptionalLong updateCredentialPolicyState(long taskId,
                                                    long expectedStateVersion,
                                                    String expectedPolicyOwnerPluginId,
                                                    String expectedPolicyId,
                                                    String expectedPolicyStateJson,
                                                    String newPolicyStateJson,
                                                    long updatedTime) {
        String expected = normalizePolicyState(expectedPolicyStateJson);
        ScheduledCredentialBinding current = mapper.findCredentialBinding(taskId);
        if (current == null
                || !Objects.equals(current.policyOwnerPluginId(), expectedPolicyOwnerPluginId)
                || !Objects.equals(current.policyId(), expectedPolicyId)
                || !Objects.equals(normalizePolicyState(current.policyStateJson()), expected)) {
            return OptionalLong.empty();
        }
        Long newVersion = mapper.advanceIdleStateVersion(taskId, expectedStateVersion);
        if (newVersion == null) {
            return OptionalLong.empty();
        }
        int changed = mapper.updateCredentialPolicyState(taskId, expectedPolicyOwnerPluginId,
                expectedPolicyId, expected, normalizePolicyState(newPolicyStateJson), updatedTime);
        if (changed != 1) {
            throw new IllegalStateException("credential policy state changed during version-guarded update");
        }
        return OptionalLong.of(newVersion);
    }

    @Override
    public String findCredentialSecret(long taskId,
                                       String policyOwnerPluginId,
                                       String policyId) {
        return mapper.findCredentialSecret(taskId, policyOwnerPluginId, policyId);
    }

    @Override
    public int upsertPendingWork(ScheduledPendingWork pendingWork) {
        Objects.requireNonNull(pendingWork, "pendingWork");
        return mapper.upsertPendingWork(pendingWork);
    }

    @Override
    public List<ScheduledPendingWork> listPendingWork(long taskId) {
        return mapper.listPendingWork(taskId);
    }

    @Override
    public int deletePendingWork(long taskId, String workType, String workId) {
        return mapper.deletePendingWork(taskId, workType, workId);
    }

    @Override
    @Transactional
    public OptionalLong clearPendingWork(long taskId,
                                         long expectedStateVersion,
                                         String workType,
                                         String workId) {
        Long newVersion = mapper.advanceIdleStateVersion(taskId, expectedStateVersion);
        if (newVersion == null) {
            return OptionalLong.empty();
        }
        mapper.deletePendingWork(taskId, workType, workId);
        return OptionalLong.of(newVersion);
    }

    private static OptionalLong optionalLong(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static void requireTokenState(ScheduleRunToken token, ScheduleRunState expected) {
        Objects.requireNonNull(token, "token");
        if (token.runState() != expected) {
            throw new IllegalArgumentException("run token must be " + expected);
        }
    }

    private static void requireText(String value, String label) {
        if (!hasText(value)) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String normalizePolicyState(String policyStateJson) {
        return policyStateJson == null || policyStateJson.isBlank() ? "{}" : policyStateJson;
    }
}
