package top.sywyar.pixivdownload.plugin.api.event;

/**
 * 访客邀请变更（创建/吊销/配额调整）。事件骨架，字段随发布链路接入按需扩充。
 */
public record GuestInviteChangedEvent(String inviteCode) {
}
