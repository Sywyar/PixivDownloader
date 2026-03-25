package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SetupService {

    private final Path configFile;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private volatile boolean setupComplete = false;
    private volatile String mode     = null;  // "solo" | "multi"
    private volatile String username = null;
    private volatile String passwordHash = null;
    private volatile String salt     = null;

    /** token → expiry timestamp (ms)，内存中同时保存短期和长期 session */
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();

    /** 需要持久化的长期 session token 集合 */
    private final ConcurrentHashMap<String, Long> persistentSessions = new ConcurrentHashMap<>();

    private static final long SESSION_SHORT = 2L  * 3600 * 1000;       // 2 小时
    private static final long SESSION_LONG  = 30L * 24 * 3600 * 1000;  // 30 天

    public SetupService(@Value("${download.root-folder:pixiv-download}") String rootFolder) {
        this.configFile = Path.of(rootFolder, "setup_config.json");
        load();
    }

    // ---- 配置加载/保存 -----------------------------------------------

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(configFile)) return;
        try {
            Map<String, Object> map = objectMapper.readValue(configFile.toFile(), Map.class);
            this.setupComplete  = Boolean.TRUE.equals(map.get("setupComplete"));
            this.mode           = (String) map.get("mode");
            this.username       = (String) map.get("username");
            this.passwordHash   = (String) map.get("passwordHash");
            this.salt           = (String) map.get("salt");

            // 还原持久化 session，过滤已过期的
            Object raw = map.get("sessions");
            if (raw instanceof Map<?, ?> savedSessions) {
                long now = System.currentTimeMillis();
                savedSessions.forEach((k, v) -> {
                    if (k instanceof String token && v instanceof Number expiry) {
                        if (expiry.longValue() > now) {
                            sessions.put(token, expiry.longValue());
                            persistentSessions.put(token, expiry.longValue());
                        }
                    }
                });
                log.info("Setup config loaded: mode={}, restored {} session(s)", this.mode, persistentSessions.size());
            } else {
                log.info("Setup config loaded: mode={}", this.mode);
            }
        } catch (IOException e) {
            log.warn("Failed to load setup config: {}", e.getMessage());
        }
    }

    private void save() throws IOException {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("setupComplete", setupComplete);
        map.put("mode",          mode);
        map.put("username",      username);
        map.put("passwordHash",  passwordHash);
        map.put("salt",          salt);
        // 只持久化未过期的长期 session
        long now = System.currentTimeMillis();
        Map<String, Long> toSave = new LinkedHashMap<>();
        persistentSessions.forEach((token, expiry) -> {
            if (expiry > now) toSave.put(token, expiry);
        });
        map.put("sessions", toSave);
        Files.createDirectories(configFile.getParent());
        objectMapper.writeValue(configFile.toFile(), map);
    }

    // ---- 公开状态 -------------------------------------------------------

    public boolean isSetupComplete() { return setupComplete; }

    public String getMode() { return mode; }

    // ---- 初始化配置 -----------------------------------------------------

    public void init(String uname, String pwd, String usageMode) throws IOException {
        this.salt         = UUID.randomUUID().toString().replace("-", "");
        this.passwordHash = hash(pwd, this.salt);
        this.username     = uname;
        this.mode         = usageMode;
        this.setupComplete = true;
        save();
        log.info("Setup completed: mode={}", usageMode);
    }

    // ---- 登录验证 -------------------------------------------------------

    public boolean checkLogin(String uname, String pwd) {
        return username != null && username.equals(uname)
                && passwordHash != null && passwordHash.equals(hash(pwd, salt));
    }

    // ---- Session 管理 --------------------------------------------------

    public String createSession(boolean remember) {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + (remember ? SESSION_LONG : SESSION_SHORT);
        sessions.put(token, expiry);
        if (remember) {
            persistentSessions.put(token, expiry);
            try { save(); } catch (IOException e) { log.warn("保存 session 失败: {}", e.getMessage()); }
        }
        return token;
    }

    public boolean isValidSession(String token) {
        if (token == null || token.isBlank()) return false;
        Long exp = sessions.get(token);
        if (exp == null) return false;
        if (System.currentTimeMillis() > exp) {
            sessions.remove(token);
            if (persistentSessions.remove(token) != null) {
                try { save(); } catch (IOException e) { log.warn("清理过期 session 失败: {}", e.getMessage()); }
            }
            return false;
        }
        return true;
    }

    public void removeSession(String token) {
        if (token == null) return;
        sessions.remove(token);
        if (persistentSessions.remove(token) != null) {
            try { save(); } catch (IOException e) { log.warn("移除 session 失败: {}", e.getMessage()); }
        }
    }

    // ---- 工具 ----------------------------------------------------------

    private String hash(String password, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update((s + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
