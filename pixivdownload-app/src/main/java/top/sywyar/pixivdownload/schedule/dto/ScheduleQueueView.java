package top.sywyar.pixivdownload.schedule.dto;

import java.util.List;
import java.util.Map;

/**
 * 计划任务<b>最近一轮运行队列</b>的对外视图（管理员专用）。
 *
 * <p>来自内存中的 {@link top.sywyar.pixivdownload.schedule.ScheduleRunQueue}，不落库。任务从未运行
 * （或进程重启后）时 {@code startedTime=null}、{@code items} 为空——此时前端保留本地缓存继续展示，
 * 直到任务再次运行刷新。{@code truncated} 表示本轮发现的作品数超过登记上限、列表只含前若干条。
 */
public record ScheduleQueueView(
        long taskId,
        Long startedTime,
        boolean truncated,
        int total,
        List<Item> items
) {
    public ScheduleQueueView {
        items = items == null ? List.of() : List.copyOf(items);
    }

    /**
     * 中性队列条目。作品身份由 {@code workType + workId} 共同构成，二者都按不透明字符串投影；
     * {@code status} / {@code message} 是宿主机器态，展示快照与结果属性由声明作品类型的前端模块解释。
     *
     * <p>{@code liveStatus} 是执行器在读取时提供的有界原始机器状态。宿主只校验、复制和隔离失败，
     * 不解释字段含义；执行器缺席、未授权读取或返回非法内容时固定为空 Map。
     */
    public record Item(
            String workId,
            String workType,
            String title,
            String author,
            String thumbnailReference,
            Map<String, String> presentationAttributes,
            String status,
            String message,
            Map<String, String> resultAttributes,
            Map<String, String> liveStatus
    ) {
        public Item {
            presentationAttributes = presentationAttributes == null
                    ? Map.of()
                    : Map.copyOf(presentationAttributes);
            resultAttributes = resultAttributes == null
                    ? Map.of()
                    : Map.copyOf(resultAttributes);
            liveStatus = liveStatus == null ? Map.of() : Map.copyOf(liveStatus);
        }
    }
}
