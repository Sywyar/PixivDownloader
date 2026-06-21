package top.sywyar.pixivdownload.push.controller;

import top.sywyar.pixivdownload.push.PushResult;

import java.util.List;

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

    public static PushTestResponse from(List<PushResult> results) {
        List<Item> items = results.stream()
                .map(r -> new Item(r.channel().id(), r.status().name(), r.detail()))
                .toList();
        int total = items.size();
        int succeeded = (int) results.stream().filter(PushResult::isOk).count();
        boolean success = total > 0 && succeeded == total;
        return new PushTestResponse(success, total, succeeded, items);
    }
}
