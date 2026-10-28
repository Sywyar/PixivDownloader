package top.sywyar.pixivdownload.schedule;

/** 持久化任务定义的 schema、版本或载荷无法由当前来源实现安全解释。 */
public final class ScheduleDefinitionException extends Exception {

    public ScheduleDefinitionException(String message, Throwable cause) {
        super(message, cause);
    }

    public ScheduleDefinitionException(String message) {
        super(message);
    }
}
