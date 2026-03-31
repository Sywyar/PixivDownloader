package top.sywyar.pixivdownload.setup;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * setup_config.json 的结构化映射。
 */
@Data
public class SetupConfig {
    private boolean setupComplete;
    private String mode;
    private String username;
    private String passwordHash;
    private String salt;
    /** token → expiry timestamp (ms) */
    private Map<String, Long> sessions = new LinkedHashMap<>();
}
