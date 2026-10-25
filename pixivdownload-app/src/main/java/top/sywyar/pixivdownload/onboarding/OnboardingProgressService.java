package top.sywyar.pixivdownload.onboarding;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 记录新用户引导流程的进度信号，供 GUI「首页」分步引导轮询。
 *
 * <p>纯内存状态：进程内单例，重启后从头开始（引导本身是一次性体验，
 * 不需要持久化；GUI 侧另有 {@code OnboardingState} 控制是否默认停留首页）。</p>
 */
@Service
public class OnboardingProgressService {

    private final AtomicBoolean batchVisited = new AtomicBoolean(false);
    private final Set<String> completedSteps = ConcurrentHashMap.newKeySet();

    public void recordBatchVisit() {
        batchVisited.set(true);
    }

    public void markStepCompleted(String stepId) {
        if (stepId != null && !stepId.isBlank()) {
            completedSteps.add(stepId.trim());
        }
    }

    public boolean isBatchVisited() {
        return batchVisited.get();
    }

    public List<String> completedSteps() {
        return completedSteps.stream().sorted().toList();
    }
}
