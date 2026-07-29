package top.sywyar.pixivdownload.push;

import java.util.regex.Pattern;

/**
 * 推送通道的开放稳定标识。具体标识由贡献通道的插件拥有，核心只校验可安全用于配置、协议投影与诊断的
 * canonical token，不枚举任何具体通道。
 *
 * @param id 小写稳定 token
 */
public record PushChannelId(String id) {

    private static final Pattern VALID_ID = Pattern.compile("[a-z][a-z0-9-]{0,63}");

    public PushChannelId {
        if (id == null || !VALID_ID.matcher(id).matches()) {
            throw new IllegalArgumentException(
                    "channel id must match [a-z][a-z0-9-]{0,63}");
        }
    }
}
