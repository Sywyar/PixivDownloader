package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.download.config.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import org.springframework.boot.ApplicationArguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SetupService {

    private final Path configFile;
    private final ObjectMapper objectMapper;
    private final AppMessages messages;

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);

    @Getter
    private volatile boolean setupComplete = false;
    @Getter
    private volatile String mode     = null;  // "solo" | "multi"
    @Getter
    private final boolean introMode;  // --intro 启动参数
    private volatile String username = null;
    private volatile String passwordHash = null;
    private volatile String salt     = null;  // 仅旧 SHA-256 哈希需要（向后兼容用）

    /** token → expiry timestamp (ms)，内存中同时保存短期和长期 session */
    private final ConcurrentHashMap<String, Long> sessions = new ConcurrentHashMap<>();

    /** 需要持久化的长期 session token 集合 */
    private final ConcurrentHashMap<String, Long> persistentSessions = new ConcurrentHashMap<>();

    private static final long SESSION_SHORT = 2L  * 3600 * 1000;       // 2 小时
    private static final long SESSION_LONG  = 30L * 24 * 3600 * 1000;  // 30 天

    public SetupService(DownloadConfig downloadConfig,
                        ObjectMapper objectMapper,
                        ApplicationArguments args,
                        AppMessages messages) {
        this.configFile = RuntimeFiles.resolveSetupConfigPath(downloadConfig.getRootFolder());
        this.objectMapper = objectMapper;
        this.messages = messages;
        this.introMode = Arrays.asList(args.getSourceArgs()).contains("--intro");
        load();
        if (passwordHash != null && !passwordHash.startsWith("$2") && !persistentSessions.isEmpty()) {
            sessions.clear();
            persistentSessions.clear();
            log.info(message("setup.log.password-hash.legacy-detected"));
            try {
                save();
            } catch (IOException e) {
                log.warn(message("setup.log.config.load.failed", e.getMessage()));
            }
        }
    }

    // ---- 配置加载/保存 -----------------------------------------------

    private void load() {
        if (!Files.exists(configFile)) return;
        try {
            SetupConfig config = objectMapper.readValue(configFile.toFile(), SetupConfig.class);
            this.setupComplete = config.isSetupComplete();
            this.mode          = config.getMode();
            this.username      = config.getUsername();
            this.passwordHash  = config.getPasswordHash();
            this.salt          = config.getSalt();

            // 还原持久化 session，过滤已过期的
            if (config.getSessions() != null) {
                long now = System.currentTimeMillis();
                config.getSessions().forEach((token, expiry) -> {
                    if (expiry > now) {
                        sessions.put(token, expiry);
                        persistentSessions.put(token, expiry);
                    }
                });
                log.info(message(
                        "setup.log.config.loaded.restored",
                        this.mode,
                        persistentSessions.size()
                ));
            } else {
                log.info(message("setup.log.config.loaded", this.mode));
            }
        } catch (IOException e) {
            log.warn(message("setup.log.config.load.failed", e.getMessage()));
        }
    }

    private void save() throws IOException {
        SetupConfig config = new SetupConfig();
        config.setSetupComplete(setupComplete);
        config.setMode(mode);
        config.setUsername(username);
        config.setPasswordHash(passwordHash);
        config.setSalt(salt);

        // 只持久化未过期的长期 session
        long now = System.currentTimeMillis();
        LinkedHashMap<String, Long> toSave = new LinkedHashMap<>();
        persistentSessions.forEach((token, expiry) -> {
            if (expiry > now) toSave.put(token, expiry);
        });
        config.setSessions(toSave);

        Files.createDirectories(configFile.getParent());
        objectMapper.writeValue(configFile.toFile(), config);
    }

    // ---- 初始化配置 -----------------------------------------------------

    public void init(String uname, String pwd, String usageMode) throws IOException {
        this.salt         = null;  // BCrypt 不需要单独的 salt 字段
        this.passwordHash = BCRYPT.encode(pwd);
        this.username     = uname;
        this.mode         = usageMode;
        this.setupComplete = true;
        save();
        log.info(message("setup.log.completed", usageMode));
    }

    // ---- 修改密码 -------------------------------------------------------

    /**
     * 修改管理员密码。校验旧密码后用 BCrypt 重新编码新密码并落盘。
     * 出于安全考虑，密码变更后所有现存 session（含长期记住的）一并失效，
     * 调用方必须重新登录。
     */
    public synchronized void changePassword(String oldPwd, String newPwd) throws IOException {
        if (!setupComplete || username == null || passwordHash == null) {
            throw new IllegalStateException("Setup not completed");
        }
        if (!checkLogin(username, oldPwd)) {
            throw new IllegalArgumentException("Invalid current password");
        }
        if (newPwd == null || newPwd.length() < 6) {
            throw new IllegalArgumentException("New password must be at least 6 characters");
        }
        this.salt = null;
        this.passwordHash = BCRYPT.encode(newPwd);
        sessions.clear();
        persistentSessions.clear();
        save();
        log.info(message("setup.log.password.changed"));
    }

    // ---- 登录验证 -------------------------------------------------------

    public boolean checkLogin(String uname, String pwd) {
        if (username == null || !username.equals(uname) || passwordHash == null) return false;
        // BCrypt 哈希以 $2a$/$2b$/$2y$ 开头；旧版为 64 位 hex（SHA-256）
        if (passwordHash.startsWith("$2")) {
            return BCRYPT.matches(pwd, passwordHash);
        }
        // 向后兼容：旧 SHA-256 哈希验证通过后自动升级为 BCrypt
        if (passwordHash.equals(legacySha256Hash(pwd, salt))) {
            this.salt         = null;
            this.passwordHash = BCRYPT.encode(pwd);
            try {
                save();
            } catch (IOException e) {
                log.warn(message("setup.log.password-hash.upgrade.failed", e.getMessage()));
            }
            log.info(message("setup.log.password-hash.upgraded"));
            return true;
        }
        return false;
    }

    // ---- Session 管理 --------------------------------------------------

    public String createSession(boolean remember) {
        String token = UUID.randomUUID().toString();
        long expiry = System.currentTimeMillis() + (remember ? SESSION_LONG : SESSION_SHORT);
        sessions.put(token, expiry);
        if (remember) {
            persistentSessions.put(token, expiry);
            try {
                save();
            } catch (IOException e) {
                log.warn(message("setup.log.session.save.failed", e.getMessage()));
            }
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
                try {
                    save();
                } catch (IOException e) {
                    log.warn(message("setup.log.session.expired-cleanup.failed", e.getMessage()));
                }
            }
            return false;
        }
        return true;
    }

    public boolean isAdminLoggedIn(HttpServletRequest request) {
        String token = SessionUtils.extractToken(request);
        return token != null && isValidSession(token);
    }

    /**
     * 是否拥有"全局可见"权限：solo 模式下任何请求都拥有；multi 模式仅登录管理员拥有。
     * 用于下载状态、聚合 SSE 等需要在多用户场景下区分访问范围的端点。
     */
    public boolean hasAdminScope(HttpServletRequest request) {
        return !"multi".equals(getMode()) || isAdminLoggedIn(request);
    }

    public void removeSession(String token) {
        if (token == null) return;
        sessions.remove(token);
        if (persistentSessions.remove(token) != null) {
            try {
                save();
            } catch (IOException e) {
                log.warn(message("setup.log.session.remove.failed", e.getMessage()));
            }
        }
    }

    // ---- 工具 ----------------------------------------------------------

    /** 旧版 SHA-256 哈希，仅用于向后兼容验证，不再用于新密码存储 */
    private static String legacySha256Hash(String password, String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(((s == null ? "" : s) + password).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
