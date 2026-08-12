package top.sywyar.pixivdownload.notificationbase;

import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceContext;
import top.sywyar.pixivdownload.plugin.api.maintenance.MaintenanceTask;

final class NotificationInboxRetentionTask implements MaintenanceTask {

    private final NotificationInboxService inbox;

    NotificationInboxRetentionTask(NotificationInboxService inbox) {
        this.inbox = inbox;
    }

    @Override
    public String name() {
        return "notification-inbox-retention";
    }

    @Override
    public void execute(MaintenanceContext context) {
        inbox.pruneRetentionPool();
    }
}
