package top.sywyar.pixivdownload.setup;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import top.sywyar.pixivdownload.common.ServerStateProvider;
import top.sywyar.pixivdownload.common.SessionUtils;
import top.sywyar.pixivdownload.config.RuntimeFiles;
import top.sywyar.pixivdownload.core.appconfig.DownloadConfig;
import top.sywyar.pixivdownload.i18n.AppMessages;

import org.springframework.boot.ApplicationArguments;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class SetupService implements ServerStateProvider, ApplicationModeProvider, UserDisplayNameProvider {

    private final Path configFile;
    private final ObjectMapper objectMapper;
    private final AppMessages messages;

    private static final BCryptPasswordEncoder BCRYPT = new BCryptPasswordEncoder(12);
    private static final SecureRandom SESSION_RANDOM = new SecureRandom();
    private static final int MAX_TOKEN_LENGTH = 128;
    public static final int MIN_PASSWORD_LENGTH = 12;

    @Getter
    private volatile boolean setupComplete = false;
    @Getter
    private volatile String mode     = null;  // "solo" | "multi"
    @Getter
    private final boolean introMode;  // --intro 启动参数
    private volatile String username = null;
    @Getter
    private volatile String displayName = null;  // 用户自定义称呼（个性化问候），独立于登录用 username
    private volatile String passwordHash = null;
    private volatile String salt     = null;  // 仅旧 SHA-256 哈希需要（向后兼容用）
    @Getter
    private volatile boolean configurationCorrupted = false;
    private volatile boolean sessionStorageMigrationNeeded = false;

    /** SHA-256(token) → expiry timestamp (ms)，内存中同时保存短期和长期 session */
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
        if (sessionStorageMigrationNeeded) {
            try {
                save(false);
            } catch (IOException e) {
                log.error(message("setup.log.config.load.failed", e.getMessage()), e);
            }
        }
        if (!configurationCorrupted
                && passwordHash != null && !passwordHash.startsWith("$2")
                && !persistentSessions.isEmpty()) {
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
            SetupConfig config = SetupConfigFile.read(configFile, objectMapper);
            this.setupComplete = config.isSetupComplete();
            this.mode          = config.getMode();
            this.username      = config.getUsername();
            this.displayName   = config.getDisplayName();
            this.passwordHash  = config.getPasswordHash();
            this.salt          = config.getSalt();

            // 还原持久化 session，过滤已过期的
            if (config.getSessions() != null) {
                long now = System.currentTimeMillis();
                config.getSessions().forEach((storedToken, expiry) -> {
                    boolean digestFormat = isTokenDigest(storedToken);
                    sessionStorageMigrationNeeded |= !digestFormat || expiry == null || expiry <= now;
                    if (storedToken != null && expiry != null && expiry > now) {
                        String digest = digestFormat
                                ? storedToken.toLowerCase()
                                : tokenDigest(storedToken);
                        sessions.put(digest, expiry);
                        persistentSessions.put(digest, expiry);
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
            this.configurationCorrupted = true;
            log.error(message("setup.log.config.load.failed", e.getMessage()), e);
        }
    }

    private synchronized void save() throws IOException {
        save(true);
    }

    private synchronized void save(boolean backupCurrent) throws IOException {
        SetupConfig config = new SetupConfig();
        config.setSetupComplete(setupComplete);
        config.setMode(mode);
        config.setUsername(username);
        config.setDisplayName(displayName);
        config.setPasswordHash(passwordHash);
        config.setSalt(salt);

        // 只持久化未过期的长期 session
        long now = System.currentTimeMillis();
        LinkedHashMap<String, Long> toSave = new LinkedHashMap<>();
        persistentSessions.forEach((token, expiry) -> {
            if (expiry > now) toSave.put(token, expiry);
        });
        config.setSessions(toSave);

        SetupConfigFile.write(configFile, config, objectMapper,
                backupCurrent && !sessionStorageMigrationNeeded);
        sessionStorageMigrationNeeded = false;
    }

    // ---- 初始化配置 -----------------------------------------------------

    public synchronized void init(String uname, String pwd, String usageMode) throws IOException {
        if (configurationCorrupted) {
            throw new IllegalStateException("setup configuration is corrupted; restore it before initialization");
        }
        if (setupComplete) {
            throw new IllegalStateException("setup is already complete");
        }
        if (pwd == null || pwd.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        this.salt = null;  // BCrypt 不需要单独的 salt 字段
        this.passwordHash = BCRYPT.encode(pwd);
        this.username = uname;
        this.mode = usageMode;
        this.setupComplete = true;
        try {
            save();
        } catch (IOException e) {
            this.setupComplete = false;
            this.mode = null;
            this.username = null;
            this.passwordHash = null;
            throw e;
        }
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
        if (newPwd == null || newPwd.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException(
                    "New password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        this.salt = null;
        this.passwordHash = BCRYPT.encode(newPwd);
        sessions.clear();
        persistentSessions.clear();
        save();
        log.info(message("setup.log.password.changed"));
    }

    // ---- 称呼（个性化问候） --------------------------------------------

    /**
     * 更新用户自定义称呼并落盘。传入空白会清空称呼（回退到各处的默认问候）。
     * 与登录用 {@link #username} 无关，仅用于个性化展示。
     */
    public synchronized void updateDisplayName(String name) throws IOException {
        String trimmed = name == null ? null : name.trim();
        this.displayName = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        save();
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
        byte[] tokenBytes = new byte[32];
        SESSION_RANDOM.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        Arrays.fill(tokenBytes, (byte) 0);
        String digest = tokenDigest(token);
        long expiry = System.currentTimeMillis() + (remember ? SESSION_LONG : SESSION_SHORT);
        sessions.put(digest, expiry);
        if (remember) {
            persistentSessions.put(digest, expiry);
            try {
                save();
            } catch (IOException e) {
                log.warn(message("setup.log.session.save.failed", e.getMessage()));
            }
        }
        return token;
    }

    public boolean isValidSession(String token) {
        if (token == null || token.isBlank() || token.length() > MAX_TOKEN_LENGTH) return false;
        String digest = tokenDigest(token);
        Long exp = sessions.get(digest);
        if (exp == null) return false;
        if (System.currentTimeMillis() > exp) {
            sessions.remove(digest);
            if (persistentSessions.remove(digest) != null) {
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
        if (token == null || token.length() > MAX_TOKEN_LENGTH) return;
        String digest = tokenDigest(token);
        sessions.remove(digest);
        if (persistentSessions.remove(digest) != null) {
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

    private static String tokenDigest(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static boolean isTokenDigest(String value) {
        return value != null && value.length() == 64
                && value.chars().allMatch(ch -> Character.digit(ch, 16) >= 0);
    }

    private String message(String code, Object... args) {
        return messages.getForLog(code, args);
    }
}
