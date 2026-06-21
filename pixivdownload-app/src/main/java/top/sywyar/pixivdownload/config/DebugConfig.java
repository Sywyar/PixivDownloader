package top.sywyar.pixivdownload.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 调试模式开关。注入本 bean 后调用 {@link #isEnabled()} 即可按需开启仅调试场景下的功能。
 * <p>
 * 该开关默认关闭，且在 GUI「配置 → 服务器」分组中默认隐藏，仅在触发彩蛋后才显示对应复选框，
 * 因此不会暴露给普通用户。
 */
@Data
@Component
@ConfigurationProperties(prefix = "debug")
public class DebugConfig {

    /** 是否开启调试模式。 */
    private volatile boolean enabled = false;
}
