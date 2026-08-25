package top.sywyar.pixivdownload.schedule;

import lombok.extern.slf4j.Slf4j;
import top.sywyar.pixivdownload.core.schedule.ScheduledTask;
import top.sywyar.pixivdownload.core.schedule.ScheduledTaskStore;
import top.sywyar.pixivdownload.core.schedule.state.ScheduleSuspendReason;
import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.i18n.NamespaceMessageResolver;
import top.sywyar.pixivdownload.notification.NotificationDispatcher;
import top.sywyar.pixivdownload.notification.NotificationScenario;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.ScheduleCapabilityAccess;
import top.sywyar.pixivdownload.plugin.api.schedule.capability.SchedulePlanningLease;
import top.sywyar.pixivdownload.plugin.api.schedule.credential.ScheduledCredentialIncidentPresentation;
import top.sywyar.pixivdownload.plugin.api.schedule.source.ScheduledSourceDescriptor;
import top.sywyar.pixivdownload.plugin.api.schedule.work.ScheduledWorkNotificationPresentation;
import top.sywyar.pixivdownload.setup.UserDisplayNameProvider;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** 生成并发送计划任务的 best-effort 通知，不参与任务状态持久化。 */
@Slf4j
final class ScheduleNotificationService {

    /** 账号级策略通知里逐条列出受影响任务的最大条数，超出附「等共 N 个」。 */
    private static final int TASK_LIST_LIMIT = 15;

    private final ScheduledTaskStore store;
    private final ScheduleCapabilityAccess scheduleCapabilityRegistry;
    private final NotificationDispatcher notificationDispatcher;
    private final MessageResolver messages;
    private final NamespaceMessageResolver namespaceMessageResolver;
    private final UserDisplayNameProvider userDisplayNameProvider;

    ScheduleNotificationService(
            ScheduledTaskStore store,
            ScheduleCapabilityAccess scheduleCapabilityRegistry,
            NotificationDispatcher notificationDispatcher,
            MessageResolver messages,
            NamespaceMessageResolver namespaceMessageResolver,
            UserDisplayNameProvider userDisplayNameProvider) {
        this.store = Objects.requireNonNull(store, "store");
        this.scheduleCapabilityRegistry = Objects.requireNonNull(
                scheduleCapabilityRegistry, "scheduleCapabilityRegistry");
        this.notificationDispatcher = Objects.requireNonNull(
                notificationDispatcher, "notificationDispatcher");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.namespaceMessageResolver = Objects.requireNonNull(
                namespaceMessageResolver, "namespaceMessageResolver");
        this.userDisplayNameProvider = Objects.requireNonNull(
                userDisplayNameProvider, "userDisplayNameProvider");
    }

    /** 本轮中达到自动重试上限、需要在最终 next_run_time 确定后再发送的通知事件。 */
    record PendingExhaustedNotification(
            String workType,
            String workId,
            int attempts,
            long triggerTime,
            String reason,
            ScheduledWorkNotificationPresentation presentation) {
    }

    /** 账号级策略挂起完成后，按策略在 execution lease 内物化的安全场景投影发送通知。 */
    void handlePolicyAccountIncident(
            ScheduledTask task,
            String suspendCode,
            ScheduledCredentialIncidentPresentation presentation) {
        NotificationScenario scenario = NotificationScenario.findById(
                presentation.scenarioId()).orElse(null);
        if (scenario == null) {
            log.warn("Scheduled task {} skipped unknown credential incident notification scenario",
                    task.id());
            return;
        }
        String accountKey = task.credentialAccountKey();
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        List<ScheduledTask> affected = collectAffectedPolicyTasks(
                task, accountKey, suspendCode);
        int frozen = Math.max(1, affected.size());
        Map<String, String> ph = new LinkedHashMap<>(presentation.scalarAttributes());
        presentation.timeAttributes().forEach((key, value) -> ph.put(key, formatTime(value)));
        ph.put("account_id", accountKey == null ? "-" : accountKey);
        ph.put("tasks_count", String.valueOf(frozen));
        ph.put("tasks_list_html", buildTaskList(locale, affected, true));
        ph.put("tasks_list_md", buildTaskList(locale, affected, false));
        ph.put("trigger_time", formatTime(System.currentTimeMillis()));
        sendNotification(scenario, ph);
    }

    /** 任务级凭证挂起通知；具体凭证格式和恢复交互由策略 owner 展示。 */
    void handleSuspend(
            ScheduledTask task,
            ScheduleCredentialSuspensionNotice notice,
            long triggerTime) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        ph.put("trigger_time", formatTime(triggerTime));
        if (notice.reason()
                == ScheduleCredentialSuspensionNotice.Reason.FAILURE_CIRCUIT_OPEN) {
            ph.put("consecutive_failures", String.valueOf(notice.consecutiveFailures()));
            ph.put("last_error_excerpt", notice.lastErrorExcerpt() == null
                    ? "" : notice.lastErrorExcerpt());
            sendNotification(NotificationScenario.CREDENTIAL_FAILURE_CIRCUIT_OPEN, ph);
        } else {
            ph.put("reason", notice.reason().name());
            sendNotification(NotificationScenario.CREDENTIAL_SUSPENDED, ph);
        }
    }

    /** attempts 刚到达 {@code schedule.pending-max-attempts} 后，在最终 next_run_time 确定时发通知。 */
    void sendPendingExhaustedNotifications(
            ScheduledTask task,
            List<PendingExhaustedNotification> notifications,
            Long nextRun) {
        if (notifications == null || notifications.isEmpty()) {
            return;
        }
        List<PendingExhaustedNotification> snapshot;
        synchronized (notifications) {
            snapshot = List.copyOf(notifications);
        }
        for (PendingExhaustedNotification event : snapshot) {
            notifyPendingExhausted(task, event, nextRun);
        }
    }

    /** 凭证失效但策略允许匿名继续时发送一次降级通知（best-effort）。 */
    void notifyDegradedAnonymous(ScheduledTask task, int completed, long triggerTime, Long nextRun) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("completed", String.valueOf(completed));
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        sendNotification(NotificationScenario.CREDENTIAL_REVOKED_CONTINUING, ph);
    }

    /** 运行成功且本轮有新下载：发摘要通知（best-effort）。 */
    void notifyRunSummary(ScheduledTask task, int completed, long triggerTime, Long nextRun) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("completed", String.valueOf(completed));
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        sendNotification(NotificationScenario.RUN_SUMMARY, ph);
    }

    /** 整轮运行失败（状态由非 ERROR 转入 ERROR）：发失败通知（best-effort）。 */
    void notifyRunFailure(ScheduledTask task, String errorExcerpt, long triggerTime, Long nextRun) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        Map<String, String> ph = baseTaskPlaceholders(task, locale);
        ph.put("trigger_time", formatTime(triggerTime));
        ph.put("next_run_time", formatTime(nextRun));
        ph.put("last_error_excerpt", errorExcerpt == null ? "" : errorExcerpt);
        sendNotification(NotificationScenario.RUN_FAILED, ph);
    }

    /** 单个 pending-exhausted 事件的邮件 + 推送通知，best-effort、不影响调度。 */
    private void notifyPendingExhausted(ScheduledTask task, PendingExhaustedNotification event, Long nextRun) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        ph.put("work_id", displayToken(event.workId()));
        ph.put("work_kind", workKindLabel(locale, event.workType(), event.presentation()));
        ph.put("work_url", event.presentation() == null
                || event.presentation().referenceUrl() == null
                ? ""
                : event.presentation().referenceUrl());
        ph.put("attempts", String.valueOf(event.attempts()));
        ph.put("trigger_time", formatTime(event.triggerTime()));
        ph.put("next_run_time", formatTime(nextRun));
        // 隔离表 reason 列未折叠空白、可能多行；通知展示前折叠为单行。
        ph.put("last_error_excerpt", collapseWhitespace(event.reason()));
        sendNotification(NotificationScenario.PENDING_EXHAUSTED, ph);
    }

    /** 任务级通知共用的基础占位符（任务名 / ID / 类型 / 触发方式），与邮件 / 推送同一套键。 */
    private Map<String, String> baseTaskPlaceholders(ScheduledTask task, Locale locale) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("task_name", task.name() == null ? "-" : task.name());
        ph.put("task_id", String.valueOf(task.id()));
        ph.put("task_type", taskTypeLabel(locale, task.sourceType()));
        ph.put("task_trigger", triggerLabel(locale, task.triggerKind(), task.intervalMinutes(), task.cronExpr()));
        return ph;
    }

    /** 取同 credential policy/account/reason 下已经持久化挂起的任务列表。 */
    private List<ScheduledTask> collectAffectedPolicyTasks(
            ScheduledTask current,
            String accountKey,
            String suspendCode) {
        if (accountKey == null || accountKey.isBlank()
                || current.credentialPolicyOwnerPluginId() == null
                || current.credentialPolicyId() == null
                || suspendCode == null) {
            return List.of(current);
        }
        List<ScheduledTask> result = new ArrayList<>();
        for (ScheduledTask task : store.findByCredentialAccount(
                current.credentialPolicyOwnerPluginId(),
                current.credentialPolicyId(),
                accountKey)) {
            if (task.suspendReason() == ScheduleSuspendReason.POLICY
                    && suspendCode.equals(task.suspendCode())) {
                result.add(task);
            }
        }
        return result.isEmpty() ? List.of(current) : result;
    }

    private String buildTaskList(Locale locale, List<ScheduledTask> tasks, boolean html) {
        if (tasks == null || tasks.isEmpty()) {
            return "-";
        }
        int limit = Math.min(tasks.size(), TASK_LIST_LIMIT);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < limit; i++) {
            ScheduledTask task = tasks.get(i);
            String name = task.name() == null ? "-" : task.name();
            String item = messages.get(
                    locale,
                    "schedule.notification.policy-account.task-item",
                    html ? escapeHtml(name) : escapeMarkdownLiteral(name),
                    task.id());
            lines.add(html ? item : "- " + item);
        }
        if (tasks.size() > limit) {
            String more = messages.get(
                    locale, "schedule.notification.policy-account.task-more", tasks.size());
            lines.add(html ? more : "- " + more);
        }
        return String.join(html ? "<br>" : "\n", lines);
    }

    /** 计划任务类型标签按当前 descriptor presentation 解析。 */
    private String taskTypeLabel(Locale locale, String sourceType) {
        if (sourceType == null || sourceType.isBlank()) {
            return "-";
        }
        SchedulePlanningLease planning = scheduleCapabilityRegistry.prepareSource(sourceType).orElse(null);
        if (planning == null) {
            return "-";
        }
        try (planning) {
            if (!scheduleCapabilityRegistry.activate(planning)) {
                return "-";
            }
            ScheduledSourceDescriptor descriptor = planning.descriptor().orElse(null);
            if (descriptor == null || planning.sourceExecutor().isEmpty()) {
                return "-";
            }
            String namespace = descriptor.presentation().displayNamespace();
            String key = descriptor.presentation().displayNameKey();
            try {
                String label = namespaceMessageResolver.resolve(namespace, locale, key).orElse(null);
                if (label != null && !label.isBlank()) {
                    return label;
                }
            } catch (RuntimeException failure) {
                log.debug("Scheduled source {} display label could not be resolved from namespace {}",
                        sourceType, namespace, failure);
            }
            String fallback = messages.getOrDefault(locale, key, key);
            return fallback == null || fallback.isBlank() ? key : fallback;
        }
    }

    private String workKindLabel(
            Locale locale,
            String workType,
            ScheduledWorkNotificationPresentation presentation) {
        if (presentation != null
                && presentation.displayNamespace() != null
                && presentation.displayNameKey() != null) {
            try {
                String label = namespaceMessageResolver.resolve(
                        presentation.displayNamespace(),
                        locale,
                        presentation.displayNameKey()).orElse(null);
                if (label != null && !label.isBlank()) {
                    return label;
                }
            } catch (RuntimeException failure) {
                log.debug("Scheduled work {} display label could not be resolved from namespace {}",
                        workType, presentation.displayNamespace(), failure);
            }
        }
        return displayToken(workType);
    }

    private String triggerLabel(Locale locale, String triggerKind, Integer intervalMinutes, String cronExpr) {
        if (ScheduledTask.TRIGGER_CRON.equals(triggerKind)) {
            return messages.get(
                    locale,
                    "schedule.notification.common.trigger.cron",
                    cronExpr == null ? "-" : cronExpr);
        }
        return messages.get(
                locale,
                "schedule.notification.common.trigger.interval",
                intervalMinutes == null ? "-" : String.valueOf(intervalMinutes));
    }

    private static String displayToken(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static String collapseWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private static String escapeHtml(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static String escapeMarkdownLiteral(String literal) {
        if (literal == null || literal.isEmpty()) {
            return literal == null ? "" : literal;
        }
        String special = "\\`*_[]";
        StringBuilder result = new StringBuilder(literal.length() + 8);
        for (int i = 0; i < literal.length(); i++) {
            char character = literal.charAt(i);
            if (special.indexOf(character) >= 0) {
                result.append('\\');
            }
            result.append(character);
        }
        return result.toString();
    }

    private void sendNotification(NotificationScenario scenario, Map<String, String> placeholders) {
        Locale locale = messages.normalizeLocale(Locale.getDefault());
        placeholders.putIfAbsent("username", greetingName(locale));
        notificationDispatcher.notify(scenario, locale, placeholders);
    }

    private String greetingName(Locale locale) {
        String displayName = userDisplayNameProvider.getDisplayName();
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        return messages.get(locale, "schedule.notification.placeholder.administrator");
    }

    private static String formatTime(long epochMs) {
        if (epochMs <= 0) {
            return "-";
        }
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date(epochMs));
    }

    private static String formatTime(Long epochMs) {
        return epochMs == null ? "-" : formatTime(epochMs.longValue());
    }
}
