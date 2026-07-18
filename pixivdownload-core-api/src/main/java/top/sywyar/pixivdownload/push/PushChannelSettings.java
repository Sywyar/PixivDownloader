package top.sywyar.pixivdownload.push;

/**
 * 单个推送通道的不可变设置快照。持久设置与一次性设置使用同一契约，
 * 具体来源和保存方式由调用方决定。
 */
public interface PushChannelSettings {

    /** 这份设置属于哪个通道；派发器据此路由到对应实现。 */
    PushChannelType type();

    /** 必填字段是否齐全（可发送）。 */
    boolean isComplete();
}
