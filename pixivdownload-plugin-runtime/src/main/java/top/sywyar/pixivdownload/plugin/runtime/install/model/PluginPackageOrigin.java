package top.sywyar.pixivdownload.plugin.runtime.install.model;

import top.sywyar.pixivdownload.plugin.signature.RepositoryIdentityMigrationAuthorization;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.plugin.signature.VerificationPolicy;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 一个待安装插件包的来源描述：来源类别 + 该来源声明的可选完整性期望（期望大小 / SHA-256 / 结构化签名）。安装器据来源决定
 * 是否在落盘前做统一供应链验签。
 *
 * <h2>完整性期望只来自受信来源</h2>
 * 期望大小与 SHA-256 一律来自<b>受信插件目录元数据</b>（{@link PluginPackageSource#MARKET_CATALOG}）。本地上传
 * （{@link PluginPackageSource#LOCAL_UPLOAD}）不得自行声明这两项期望，但可以携带 detached 签名；签名只有通过宿主
 * 官方信任根验证后才会被接受。本类只建模来源与期望，<b>不</b>发起任何下载 / 网络访问。
 *
 * @param source            来源类别
 * @param expectedSizeBytes 受信清单声明的期望文件字节数（无则 {@code null}）
 * @param expectedSha256    受信清单声明的期望 SHA-256（十六进制，无则 {@code null}）
 * @param repositoryId      来源仓库 id（本地上传为 {@code null}）
 * @param officialRepository 是否官方仓库来源
 * @param developmentOnly   是否只允许在当前显式开发模式中准入
 * @param signature          受信清单声明的结构化签名元数据
 * @param expectedPluginId   受信目录声明的插件 id（旧来源记录可空）
 * @param expectedVersion    受信目录声明的插件版本（旧来源记录可空）
 * @param expectedRequiredSdk 受信目录声明的 SDK 约束（未提供展示字段时可空）
 * @param expectedDependencies 受信目录声明的依赖（旧来源记录可空，空列表表示明确无依赖）
 * @param identityMigrationSignatures 旧插件 id 到旧 key 迁移授权签名的映射
 * @param repositoryIdentityMigrationAuthorizations 旧插件 id 到来源仓库根迁移声明的映射
 * @param identityMigrationConfirmed 管理员是否对当前精确仓库根迁移声明进行了显式确认
 * @param trustConfirmationSha256 管理员确认执行当前精确候选制品的 SHA-256；仅作本次安装令牌，不写入来源事实
 */
public record PluginPackageOrigin(
        PluginPackageSource source,
        String repositoryId,
        boolean officialRepository,
        boolean developmentOnly,
        Long expectedSizeBytes,
        String expectedSha256,
        SignatureMetadata signature,
        String expectedPluginId,
        String expectedVersion,
        String expectedRequiredSdk,
        List<String> expectedDependencies,
        Map<String, SignatureMetadata> identityMigrationSignatures,
        Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
        boolean identityMigrationConfirmed,
        String trustConfirmationSha256) {

    public PluginPackageOrigin {
        Objects.requireNonNull(source, "source");
        Map<String, SignatureMetadata> migrations = identityMigrationSignatures == null
                ? Map.of() : identityMigrationSignatures;
        Map<String, RepositoryIdentityMigrationAuthorization> repositoryMigrations =
                repositoryIdentityMigrationAuthorizations == null
                        ? Map.of() : repositoryIdentityMigrationAuthorizations;
        if (source == PluginPackageSource.LOCAL_UPLOAD
                && (expectedSizeBytes != null || hasText(expectedSha256)
                || hasText(repositoryId) || officialRepository || hasText(expectedPluginId)
                || hasText(expectedVersion) || hasText(expectedRequiredSdk) || expectedDependencies != null
                || !migrations.isEmpty()
                || !repositoryMigrations.isEmpty() || identityMigrationConfirmed)) {
            throw new IllegalArgumentException("LOCAL_UPLOAD must not carry catalog source bindings");
        }
        if (source == PluginPackageSource.LOCAL_UPLOAD && signature != null && developmentOnly) {
            throw new IllegalArgumentException("signed local uploads must not be development-only");
        } else if (source != PluginPackageSource.LOCAL_UPLOAD && developmentOnly) {
            throw new IllegalArgumentException("catalog packages must not be development-only");
        }
        repositoryId = trimToNull(repositoryId);
        expectedSha256 = trimToNull(expectedSha256);
        expectedPluginId = trimToNull(expectedPluginId);
        expectedVersion = trimToNull(expectedVersion);
        expectedRequiredSdk = trimToNull(expectedRequiredSdk);
        expectedDependencies = expectedDependencies != null ? List.copyOf(expectedDependencies) : null;
        trustConfirmationSha256 = trimToNull(trustConfirmationSha256);
        if (trustConfirmationSha256 != null && !trustConfirmationSha256.matches("[0-9A-Fa-f]{64}")) {
            throw new IllegalArgumentException("trustConfirmationSha256 is not a SHA-256 digest");
        }
        if (trustConfirmationSha256 != null) {
            trustConfirmationSha256 = trustConfirmationSha256.toLowerCase(java.util.Locale.ROOT);
        }
        if (invalidMigrationEntries(migrations)) {
            throw new IllegalArgumentException("identity migration signatures must bind a plugin id and signature");
        }
        if (invalidMigrationEntries(repositoryMigrations)) {
            throw new IllegalArgumentException(
                    "repository identity migration authorizations must bind a plugin id and authorization");
        }
        if ((!migrations.isEmpty() || !repositoryMigrations.isEmpty()) && signature == null) {
            throw new IllegalArgumentException("identity migration authorizations require a signed candidate artifact");
        }
        identityMigrationSignatures = Map.copyOf(migrations);
        repositoryIdentityMigrationAuthorizations = Map.copyOf(repositoryMigrations);
    }

    public PluginPackageOrigin(
            PluginPackageSource source,
            String repositoryId,
            boolean officialRepository,
            boolean developmentOnly,
            Long expectedSizeBytes,
            String expectedSha256,
            SignatureMetadata signature,
            Map<String, SignatureMetadata> identityMigrationSignatures,
            Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
            boolean identityMigrationConfirmed) {
        this(source, repositoryId, officialRepository, developmentOnly, expectedSizeBytes, expectedSha256,
                signature, null, null, null, null,
                identityMigrationSignatures, repositoryIdentityMigrationAuthorizations,
                identityMigrationConfirmed, null);
    }

    public PluginPackageOrigin(
            PluginPackageSource source,
            String repositoryId,
            boolean officialRepository,
            boolean developmentOnly,
            Long expectedSizeBytes,
            String expectedSha256,
             SignatureMetadata signature) {
         this(source, repositoryId, officialRepository, developmentOnly,
                expectedSizeBytes, expectedSha256, signature, null, null, null, null,
                Map.of(), Map.of(), false, null);
    }

    public PluginPackageOrigin(PluginPackageSource source, String repositoryId, boolean officialRepository,
                               Long expectedSizeBytes, String expectedSha256, SignatureMetadata signature) {
        this(source, repositoryId, officialRepository,
                source == PluginPackageSource.LOCAL_UPLOAD && signature == null,
                expectedSizeBytes, expectedSha256, signature, null, null, null, null,
                Map.of(), Map.of(), false, null);
    }

    /** 开发模式允许的未签名本地上传来源。 */
    public static PluginPackageOrigin localUpload() {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, false, true,
                null, null, null, null, null, null, null, Map.of(), Map.of(), false, null);
    }

    /** 正式运行允许管理员逐制品确认的未签名本地上传来源。 */
    public static PluginPackageOrigin localUnsignedUpload(String trustConfirmationSha256) {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, false, false,
                null, null, null, null, null, null, null,
                Map.of(), Map.of(), false, trustConfirmationSha256);
    }

    /** 带 detached 签名的本地上传来源；验签策略只接受宿主官方信任根。 */
    public static PluginPackageOrigin localUpload(SignatureMetadata signature) {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, false, false, null, null,
                Objects.requireNonNull(signature, "signature"), null, null, null, null,
                Map.of(), Map.of(), false, null);
    }

    /** 带 detached 签名和精确制品执行确认的本地上传来源。 */
    public static PluginPackageOrigin localUpload(
            SignatureMetadata signature, String trustConfirmationSha256) {
        return new PluginPackageOrigin(PluginPackageSource.LOCAL_UPLOAD, null, false, false, null, null,
                Objects.requireNonNull(signature, "signature"), null, null, null, null,
                Map.of(), Map.of(), false,
                trustConfirmationSha256);
    }

    /**
     * 受信目录来源：由受信插件目录清单提供期望大小 / SHA-256 / 结构化签名（任一可为 {@code null}）。本方法只构造来源描述、
     * 不发起任何网络访问。
     */
    public static PluginPackageOrigin forTrustedCatalog(String repositoryId, boolean officialRepository,
                                                        Long expectedSizeBytes, String expectedSha256,
                                                        SignatureMetadata signature) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, null, null, null, null,
                Map.of(), Map.of(), false, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(String repositoryId, boolean officialRepository,
                                                        Long expectedSizeBytes, String expectedSha256,
                                                        SignatureMetadata signature, String expectedPluginId,
                                                        String expectedVersion, String expectedRequiredSdk,
                                                        List<String> expectedDependencies) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, expectedPluginId, expectedVersion,
                expectedRequiredSdk, expectedDependencies, Map.of(), Map.of(), false, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(String repositoryId, boolean officialRepository,
                                                        Long expectedSizeBytes, String expectedSha256,
                                                        SignatureMetadata signature,
                                                        Map<String, SignatureMetadata> identityMigrationSignatures) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, null, null, null, null,
                identityMigrationSignatures, Map.of(), false, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(
            String repositoryId, boolean officialRepository,
            Long expectedSizeBytes, String expectedSha256,
            SignatureMetadata signature, String expectedPluginId,
            String expectedVersion, String expectedRequiredSdk,
            List<String> expectedDependencies,
            Map<String, SignatureMetadata> identityMigrationSignatures) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, expectedPluginId, expectedVersion,
                expectedRequiredSdk, expectedDependencies, identityMigrationSignatures, Map.of(), false, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(
            String repositoryId, boolean officialRepository,
            Long expectedSizeBytes, String expectedSha256,
            SignatureMetadata signature, String expectedPluginId,
            String expectedVersion, String expectedRequiredSdk,
            List<String> expectedDependencies,
            Map<String, SignatureMetadata> identityMigrationSignatures,
            Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
            boolean identityMigrationConfirmed) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, expectedPluginId, expectedVersion,
                expectedRequiredSdk, expectedDependencies, identityMigrationSignatures,
                repositoryIdentityMigrationAuthorizations, identityMigrationConfirmed, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(
            String repositoryId, boolean officialRepository,
            Long expectedSizeBytes, String expectedSha256,
            SignatureMetadata signature, String expectedPluginId,
            String expectedVersion, String expectedRequiredSdk,
            List<String> expectedDependencies,
            Map<String, SignatureMetadata> identityMigrationSignatures,
            Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
            boolean identityMigrationConfirmed,
            String trustConfirmationSha256) {
        return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, expectedPluginId, expectedVersion,
                expectedRequiredSdk, expectedDependencies, identityMigrationSignatures,
                repositoryIdentityMigrationAuthorizations, identityMigrationConfirmed, trustConfirmationSha256);
    }

    public static PluginPackageOrigin forTrustedCatalog(
            String repositoryId,
            boolean officialRepository,
            Long expectedSizeBytes,
            String expectedSha256,
            SignatureMetadata signature,
            Map<String, SignatureMetadata> identityMigrationSignatures,
             Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
             boolean identityMigrationConfirmed) {
        return forTrustedCatalog(repositoryId, officialRepository, expectedSizeBytes, expectedSha256, signature,
                identityMigrationSignatures, repositoryIdentityMigrationAuthorizations,
                identityMigrationConfirmed, null);
    }

    public static PluginPackageOrigin forTrustedCatalog(
            String repositoryId,
            boolean officialRepository,
            Long expectedSizeBytes,
            String expectedSha256,
            SignatureMetadata signature,
            Map<String, SignatureMetadata> identityMigrationSignatures,
            Map<String, RepositoryIdentityMigrationAuthorization> repositoryIdentityMigrationAuthorizations,
             boolean identityMigrationConfirmed,
             String trustConfirmationSha256) {
         return new PluginPackageOrigin(PluginPackageSource.MARKET_CATALOG, repositoryId, officialRepository, false,
                expectedSizeBytes, expectedSha256, signature, null, null, null, null, identityMigrationSignatures,
                repositoryIdentityMigrationAuthorizations, identityMigrationConfirmed, trustConfirmationSha256);
    }

    /** 是否带至少一项完整性期望。 */
    public boolean hasIntegrityExpectations() {
        return expectedSizeBytes != null || expectedSha256 != null || signature != null;
    }

    public VerificationPolicy verificationPolicy(boolean developmentModeEnabled) {
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            if (signature != null) {
                return VerificationPolicy.officialRepository();
            }
            return VerificationPolicy.localUnsignedAllowed();
        }
        if (signature == null && !officialRepository) {
            return VerificationPolicy.localUnsignedAllowed();
        }
        return officialRepository ? VerificationPolicy.officialRepository() : VerificationPolicy.customRepository();
    }

    public VerificationPolicy installedVerificationPolicy(boolean developmentModeEnabled) {
        if (source == PluginPackageSource.LOCAL_UPLOAD) {
            if (signature != null) {
                return VerificationPolicy.installedOfficial();
            }
            return VerificationPolicy.localUnsignedAllowed();
        }
        if (signature == null && !officialRepository) {
            return VerificationPolicy.localUnsignedAllowed();
        }
        return officialRepository ? VerificationPolicy.installedOfficial() : VerificationPolicy.installedCustom();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean invalidMigrationEntries(Map<String, ?> entries) {
        return entries.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || !entry.getKey().equals(entry.getKey().trim())
                || !hasText(entry.getKey())
                || entry.getValue() == null);
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
