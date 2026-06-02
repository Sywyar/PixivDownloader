package top.sywyar.pixivdownload.push;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.i18n.AppMessages;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 推送派发器——框架的<b>唯一入口</b>。业务侧只需构造一个 {@link PushMessage} 调 {@link #push}，
 * 由它广播给所有"已启用且已配置"的通道。
 * <p>
 * 通过 Spring 的 {@code List<PushChannel>} 注入自动发现全部通道，本类<b>不感知</b>任何具体通道：
 * 新增通道不改动此类。整体 best-effort——{@link PushConfig#isEnabled() 总开关}关闭时直接跳过；
 * 单个通道的失败 / 异常被隔离，绝不影响其它通道，也<b>绝不向调用方抛出</b>。
 */
@Service
@Slf4j
public class PushService {

    private final PushConfig pushConfig;
    private final List<PushChannel> channels;
    private final Map<PushChannelType, PushChannel> byType;
    private final AppMessages messages;

    public PushService(PushConfig pushConfig, List<PushChannel> channels, AppMessages messages) {
        this.pushConfig = pushConfig;
        // List<PushChannel> 可能为空（未注册任何通道实现），属正常情况。
        this.channels = channels == null ? List.of() : channels;
        this.messages = messages;
        Map<PushChannelType, PushChannel> map = new EnumMap<>(PushChannelType.class);
        for (PushChannel channel : this.channels) {
            map.putIfAbsent(channel.type(), channel);
        }
        this.byType = map;
    }

    /**
     * 向所有已启用且已配置的通道广播一条消息。
     *
     * @return 每个参与通道一条 {@link PushResult}；总开关关闭 / 无可用通道时返回空列表。绝不抛异常。
     */
    public List<PushResult> push(PushMessage message) {
        if (!pushConfig.isEnabled()) {
            log.debug(messages.getForLog("push.log.skipped.disabled"));
            return List.of();
        }
        if (message == null) {
            return List.of();
        }
        List<PushResult> results = new ArrayList<>();
        for (PushChannel channel : channels) {
            if (!channel.isConfigured()) {
                continue;
            }
            results.add(dispatch(channel, message));
        }
        if (results.isEmpty()) {
            log.debug(messages.getForLog("push.log.skipped.no-channel"));
        }
        return results;
    }

    /**
     * 向指定类型的单个通道发送（定向通知 / 后续测试入口）。总开关关闭、无此通道或该通道未配置时返回
     * {@link PushResult.Status#SKIPPED}。绝不抛异常。
     */
    public PushResult push(PushChannelType type, PushMessage message) {
        if (!pushConfig.isEnabled()) {
            return PushResult.skipped(type, "push disabled");
        }
        for (PushChannel channel : channels) {
            if (channel.type() == type) {
                if (!channel.isConfigured()) {
                    return PushResult.skipped(type, "channel not configured");
                }
                return dispatch(channel, message == null ? PushMessage.of("", "") : message);
            }
        }
        return PushResult.skipped(type, "no such channel");
    }

    /**
     * 测试路径：用调用方传入的<b>临时设置</b>（GUI 当前表单值，尚未保存）逐个发送一条测试消息。
     * <p>
     * 与 {@link #push} 不同，本方法<b>不</b>检查 {@link PushConfig#isEnabled() 总开关}，也不读取任何已保存的
     * 通道配置——便于用户在启用 / 保存前先验证连通性（对齐 {@code MailService.sendTest} /
     * {@code AiService.chatTest} 的语义）。设置不完整或找不到对应通道时该项记 {@link PushResult.Status#SKIPPED}。
     * 绝不抛异常。
     *
     * @param settingsList 每个元素是某通道的临时设置快照（仅含调用方希望测试的通道）
     * @return 与 {@code settingsList} 一一对应的发送结果
     */
    public List<PushResult> test(List<PushChannelSettings> settingsList, PushMessage message) {
        if (settingsList == null || settingsList.isEmpty()) {
            return List.of();
        }
        PushMessage payload = message == null ? PushMessage.of("", "") : message;
        List<PushResult> results = new ArrayList<>();
        for (PushChannelSettings settings : settingsList) {
            if (settings == null) {
                continue;
            }
            PushChannel channel = byType.get(settings.type());
            if (channel == null) {
                results.add(PushResult.skipped(settings.type(), "no such channel"));
                continue;
            }
            if (!settings.isComplete()) {
                results.add(PushResult.skipped(settings.type(), "incomplete settings"));
                continue;
            }
            results.add(dispatchTest(channel, settings, payload));
        }
        return results;
    }

    /**
     * 双保险：通道实现已承诺 best-effort 不抛，这里仍兜一层 {@link RuntimeException}，确保广播不会因单个
     * 通道的意外异常中断。
     */
    private PushResult dispatch(PushChannel channel, PushMessage message) {
        try {
            return channel.send(message);
        } catch (RuntimeException e) {
            log.warn(messages.getForLog("push.log.channel.error", channel.type().id(),
                    e.getClass().getSimpleName()));
            return PushResult.failed(channel.type(), "unexpected error");
        }
    }

    private PushResult dispatchTest(PushChannel channel, PushChannelSettings settings, PushMessage message) {
        try {
            return channel.sendTest(settings, message);
        } catch (RuntimeException e) {
            log.warn(messages.getForLog("push.log.channel.error", channel.type().id(),
                    e.getClass().getSimpleName()));
            return PushResult.failed(channel.type(), "unexpected error");
        }
    }
}
