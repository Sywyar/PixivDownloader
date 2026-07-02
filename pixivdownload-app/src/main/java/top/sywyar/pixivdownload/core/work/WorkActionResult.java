package top.sywyar.pixivdownload.core.work;

import lombok.Getter;

@Getter
public class WorkActionResult {
    public static final String SUCCESS = "success";
    public static final String FAILED = "failed";
    public static final String SKIPPED = "skipped";
    public static final String EXISTS = "exists";

    private final String status;
    private final String message;

    private WorkActionResult(String status, String message) {
        this.status = status;
        this.message = message;
    }

    public static WorkActionResult success(String message) {
        return new WorkActionResult(SUCCESS, message);
    }

    public static WorkActionResult failed(String message) {
        return new WorkActionResult(FAILED, message);
    }

    public static WorkActionResult skipped(String message) {
        return new WorkActionResult(SKIPPED, message);
    }

    public static WorkActionResult exists(String message) {
        return new WorkActionResult(EXISTS, message);
    }
}
