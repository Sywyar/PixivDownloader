package top.sywyar.pixivdownload.core.schedule.db;

import top.sywyar.pixivdownload.plugin.api.schema.TableSpec;
import java.util.ArrayList;
import java.util.List;

/** 转移定义与待处理记录，但旧进程的执行认领不能在新安装中继续生效。 */
public final class ScheduleTransferRows {
    private ScheduleTransferRows() {}
    public static List<Object> paused(TableSpec table, List<Object> row) {
        if (!table.name().equals("scheduled_tasks")) return row;
        var result = new ArrayList<>(row);
        boolean interrupted = false;
        for (int i = 0; i < table.columns().size(); i++) {
            String column = table.columns().get(i).name();
            if ((column.equals("run_state") || column.equals("run_claim_token")) && row.get(i) != null) interrupted = true;
            switch (column) {
                case "enabled", "pending_retry_armed" -> result.set(i, 0L);
                case "run_state", "run_claim_token", "run_started_time", "next_run_time" -> result.set(i, null);
                default -> { }
            }
        }
        if (interrupted) for (int i = 0; i < table.columns().size(); i++)
            if (table.columns().get(i).name().equals("last_outcome")) result.set(i, "INTERRUPTED");
        return result;
    }
}
