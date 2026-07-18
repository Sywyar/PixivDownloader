package top.sywyar.pixivdownload.push.controller;

import top.sywyar.pixivdownload.i18n.MessageResolver;
import top.sywyar.pixivdownload.push.PushPluginMessages;
import top.sywyar.pixivdownload.push.PushResult;

import java.util.List;
import java.util.Locale;

/**
 * GUI "测试推送" 端点的响应：整体汇总 + 每通道明细。
 *
 * @param success   是否全部成功（{@code total>0 && succeeded==total}）
 * @param total     参与测试的通道数
 * @param succeeded 成功通道数
 * @param results   每通道明细；{@code detail} 已脱敏（绝不含 token / device-key），可安全展示
 */
public record PushTestResponse(boolean success, int total, int succeeded, List<Item> results) {

    /**
     * @param channel 通道 id（如 {@code bark} / {@code dingtalk} / {@code telegram}）
     * @param status  {@code OK} / {@code FAILED} / {@code SKIPPED}
     * @param detail  失败 / 跳过原因（已脱敏）；成功时为 {@code null}
     */
    public record Item(String channel, String status, String detail) {
    }

    public static PushTestResponse from(List<PushResult> results, MessageResolver messages, Locale locale) {
        List<PushResult> normalized = results == null
                ? List.of()
                : results.stream()
                .map(result -> result == null
                        ? PushResult.failed(null, PushResult.DETAIL_UNEXPECTED_ERROR)
                        : result)
                .toList();
        List<Item> items = normalized.stream()
                .map(r -> new Item(
                        r.channel() == null ? "unknown" : r.channel().id(),
                        r.status() == null ? PushResult.Status.FAILED.name() : r.status().name(),
                        PushPluginMessages.detail(messages, locale, r)))
                .toList();
        int total = items.size();
        int succeeded = (int) normalized.stream().filter(PushResult::isOk).count();
        boolean success = total > 0 && succeeded == total;
        return new PushTestResponse(success, total, succeeded, items);
    }
}
