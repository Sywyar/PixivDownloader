package top.sywyar.pixivdownload.setup;

import lombok.Data;
import lombok.ToString;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * setup_config.json 的结构化映射。
 */
@Data
public class SetupConfig {
    private boolean setupComplete;
    private String mode;
    private String username;
    /** 用户自定义的称呼，用于个性化问候（邮件、画廊侧边栏等）；与登录用 {@link #username} 相互独立。 */
    private String displayName;
    @ToString.Exclude private String passwordHash;
    @ToString.Exclude private String salt;
    /** token → expiry timestamp (ms) */
    @ToString.Exclude private Map<String, Long> sessions = new ConcurrentHashMap<>();
}
