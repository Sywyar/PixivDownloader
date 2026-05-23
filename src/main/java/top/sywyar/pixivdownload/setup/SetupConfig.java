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
    @ToString.Exclude private String passwordHash;
    @ToString.Exclude private String salt;
    /** token → expiry timestamp (ms) */
    @ToString.Exclude private Map<String, Long> sessions = new ConcurrentHashMap<>();
}
