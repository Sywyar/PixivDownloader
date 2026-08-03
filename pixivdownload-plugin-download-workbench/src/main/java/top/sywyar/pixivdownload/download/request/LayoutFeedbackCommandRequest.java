package top.sywyar.pixivdownload.download.request;

import top.sywyar.pixivdownload.download.LayoutFeedbackIdentityDeriver;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackCommandType;
import top.sywyar.pixivdownload.download.state.LayoutFeedbackStateStore;

import java.util.List;

/**
 * 服务端状态命令请求（动作式协议，禁止整包 state / seen 覆盖）。
 *
 * <p>字段规则：
 * <ul>
 *   <li>{@code expectedRevision}：CAS 期望版本，必须 &gt;= 0；</li>
 *   <li>{@code surveyId}：必须合法且长度受控；</li>
 *   <li>{@code command}：只能是 record_seen / snooze / never / submitted；</li>
 *   <li>{@code record_seen}：layoutIds 必填、1-3 个、只含稳定布局 ID、不得重复；</li>
 *   <li>{@code snooze} / {@code never} / {@code submitted}：layoutIds 必须缺失或为空。</li>
 * </ul>
 *
 * <p>客户端不得提交时间戳、完整 state / seen、用户建议、布局回答或 PostHog token；
 * 未知 JSON 字段由严格 ObjectReader 拒绝。校验失败抛出 {@link IllegalArgumentException}，
 * 由控制器映射为 400。
 */
public record LayoutFeedbackCommandRequest(
        long expectedRevision,
        String surveyId,
        String command,
        List<String> layoutIds
) {

    public LayoutFeedbackCommandRequest {
        if (expectedRevision < 0) {
            throw new IllegalArgumentException("expectedRevision must be >= 0");
        }
        if (!LayoutFeedbackIdentityDeriver.isValidSurveyId(surveyId)) {
            throw new IllegalArgumentException("invalid survey id");
        }
        LayoutFeedbackCommandType type = LayoutFeedbackCommandType.fromWire(command);
        if (type == null) {
            throw new IllegalArgumentException("unknown command");
        }
        if (type == LayoutFeedbackCommandType.RECORD_SEEN) {
            if (layoutIds == null || layoutIds.isEmpty()) {
                throw new IllegalArgumentException("record_seen requires layoutIds");
            }
            if (layoutIds.size() > 3) {
                throw new IllegalArgumentException("record_seen accepts at most 3 layoutIds");
            }
            for (String layoutId : layoutIds) {
                if (!LayoutFeedbackStateStore.LAYOUT_IDS.contains(layoutId)) {
                    throw new IllegalArgumentException("unknown layout id");
                }
            }
            if (layoutIds.stream().distinct().count() != layoutIds.size()) {
                throw new IllegalArgumentException("duplicate layoutIds");
            }
            layoutIds = List.copyOf(layoutIds);
        } else if (layoutIds != null && !layoutIds.isEmpty()) {
            throw new IllegalArgumentException("state commands must not carry layoutIds");
        }
    }

    public LayoutFeedbackCommandType type() {
        return LayoutFeedbackCommandType.fromWire(command);
    }
}
