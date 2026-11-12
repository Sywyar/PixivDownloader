import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.PKIXCertPathBuilderResult;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class RuntimeTrustTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    // 固定到公开样本捕获日，避免过期让拒绝测试产生假阳性。
    private static final Date VALIDATION_DATE = Date.from(Instant.parse("2026-10-20T12:00:00Z"));

    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !List.of("Cleanup", "Runtime").contains(args[0])) {
            throw new IllegalArgumentException("Expected Cleanup|Runtime, fixture directory and work directory");
        }
        System.out.println("Verifying " + args[0] + ": Java " + System.getProperty("java.version")
                + "; java.home=" + System.getProperty("java.home"));
        Path fixtures = Path.of(args[1]);
        verifyProblemFixtures(fixtures);
        if (args[0].equals("Cleanup")) {
            checkCleanup(fixtures, Path.of(args[2]));
        } else {
            checkRuntime(fixtures);
        }
    }

    private static void checkRuntime(Path fixtures) throws Exception {
        KeyStore defaults = KeyStore.getInstance(Path.of(System.getProperty("java.home"),
                "lib", "security", "cacerts").toFile(), PASSWORD);
        X509Certificate root = certificates(fixtures.resolve("emsign-ecc-g3.pem")).get(0);
        for (String alias : Collections.list(defaults.aliases())) {
            require(!Arrays.equals(defaults.getCertificate(alias).getPublicKey().getEncoded(),
                    root.getPublicKey().getEncoded()), "Distrusted root key remains: " + alias);
        }
        checkProblemChains(defaults, fixtures);
        require(readBlocklist(Path.of(System.getProperty("java.home"), "lib", "security", "blocked.certs"))
                .keySet().containsAll(intermediateFingerprints(fixtures)), "Runtime blocklist is missing compromised intermediates");
        // 真实默认 TrustManager 必须仍有信任锚，不能以清空信任库冒充修复。
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        X509TrustManager manager = (X509TrustManager) factory.getTrustManagers()[0];
        require(manager.getAcceptedIssuers().length > 0, "Default TLS trust store is empty");
        System.out.println("PASS: bundled runtime rejects all 12 compromised chains: " + System.getProperty("java.home"));
    }

    private static void checkCleanup(Path fixtures, Path work) throws Exception {
        X509Certificate compromisedRoot = certificates(fixtures.resolve("emsign-ecc-g3.pem")).get(0);
        X509Certificate positiveRoot = certificates(fixtures.resolve("positive-root.pem")).get(0);
        List<X509Certificate> positive = certificates(fixtures.resolve("positive-leaf.pem"));
        for (String type : List.of("JKS", "PKCS12")) {
            KeyStore store = KeyStore.getInstance(type);
            store.load(null, PASSWORD);
            store.setCertificateEntry("arbitrary-alias", compromisedRoot);
            store.setCertificateEntry("duplicate-alias", compromisedRoot);
            // 名称像 emSign 的无关证书必须保留。
            store.setCertificateEntry("emsigneccrootcag3", positiveRoot);
            // 已修复的 JDK 可能内置中间证书禁用列表，正常链承担可建立信任的对照。
            require(trustedPath(store, positive) != null, "Positive chain rejected before sanitization");
            Path path = work.resolve(type + ".cacerts");
            try (var output = Files.newOutputStream(path)) { store.store(output, PASSWORD); }
            RemoveDistrustedCa.remove(path);
            KeyStore filtered = KeyStore.getInstance(path.toFile(), PASSWORD);
            require(filtered.size() == 1 && Arrays.equals(positiveRoot.getEncoded(),
                    filtered.getCertificate("emsigneccrootcag3").getEncoded()), "Unrelated trust anchor changed");
            checkProblemChains(filtered, fixtures);
            require(trustedPath(filtered, positive) != null, "Positive chain rejected after sanitization");
            byte[] once = Files.readAllBytes(path);
            RemoveDistrustedCa.remove(path);
            require(Arrays.equals(once, Files.readAllBytes(path)), "Already clean store was rewritten");
        }
        Path corrupt = work.resolve("corrupt.cacerts");
        Files.write(corrupt, new byte[] {1, 2, 3});
        try {
            RemoveDistrustedCa.remove(corrupt);
            throw new AssertionError("Corrupt store accepted");
        } catch (java.security.KeyStoreException expected) {
            require(Arrays.equals(new byte[] {1, 2, 3}, Files.readAllBytes(corrupt)), "Corrupt input changed");
        }
        checkBlocklistUpdate(fixtures, work);
        System.out.println("PASS: isolated cleanup: root removal, normal trust preservation and intermediate blocklist merge");
    }

    private static Set<String> intermediateFingerprints(Path fixtures) throws Exception {
        Set<String> fingerprints = new HashSet<>();
        for (X509Certificate certificate : certificates(fixtures.resolve("emudhra-intermediates.pem"))) {
            certificate.checkValidity(VALIDATION_DATE);
            require(certificate.getBasicConstraints() >= 0, "Incident certificate is not a CA");
            fingerprints.add(HexFormat.of().withUpperCase().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        }
        require(fingerprints.size() == 16, "Expected 8 compromised intermediate CAs and their cross-signs");
        return fingerprints;
    }

    private static Properties readBlocklist(Path path) throws Exception {
        Properties blocked = new Properties();
        try (var input = Files.newInputStream(path)) { blocked.load(input); }
        require("SHA-256".equals(blocked.getProperty("Algorithm")), "Unexpected blocklist algorithm");
        return blocked;
    }

    private static void checkBlocklistUpdate(Path fixtures, Path work) throws Exception {
        Set<String> expected = intermediateFingerprints(fixtures);
        String unrelated = "A".repeat(64);
        Path path = work.resolve("blocked.certs");
        String original = "Algorithm=SHA-256\r\n# Preserve existing entries\r\n" + unrelated + "\r\n"
                + expected.iterator().next();
        Files.writeString(path, original, StandardCharsets.US_ASCII);
        RemoveDistrustedCa.blockIntermediates(path);
        Properties merged = readBlocklist(path);
        require(merged.keySet().containsAll(expected) && merged.containsKey(unrelated)
                && merged.size() == expected.size() + 2, "Incorrect intermediate blocklist merge");
        require(Files.readString(path, StandardCharsets.US_ASCII).startsWith(original), "Existing blocklist bytes changed");
        byte[] once = Files.readAllBytes(path);
        RemoveDistrustedCa.blockIntermediates(path);
        require(Arrays.equals(once, Files.readAllBytes(path)), "Complete blocklist was rewritten");

        Path missing = work.resolve("new-blocked.certs");
        RemoveDistrustedCa.blockIntermediates(missing);
        require(readBlocklist(missing).keySet().containsAll(expected), "Missing blocklist was not created");
        Files.writeString(path, "Algorithm=SHA-256\\", StandardCharsets.US_ASCII);
        RemoveDistrustedCa.blockIntermediates(path);
        require(readBlocklist(path).keySet().containsAll(expected), "Trailing continuation broke blocklist merge");

        for (String invalid : List.of("", "Algorithm=SHA-1\n", "Algorithm=SHA-256\ninvalid-entry\n")) {
            Files.writeString(path, invalid, StandardCharsets.US_ASCII);
            try {
                RemoveDistrustedCa.blockIntermediates(path);
                throw new AssertionError("Invalid blocklist accepted");
            } catch (IOException expectedFailure) {
                require(Files.readString(path, StandardCharsets.US_ASCII).equals(invalid), "Invalid blocklist was modified");
            }
        }
    }

    private static List<Path> problemChains(Path fixtures) throws Exception {
        List<Path> chains;
        try (var files = Files.list(fixtures.resolve("emudhra"))) {
            chains = files.filter(path -> path.toString().endsWith(".pem")).sorted().toList();
        }
        require(chains.size() == 12, "Expected all 12 published incident chains");
        return chains;
    }

    private static void verifyProblemFixtures(Path fixtures) throws Exception {
        X509Certificate emsign = certificates(fixtures.resolve("emsign-ecc-g3.pem")).getFirst();
        X509Certificate aaa = certificates(fixtures.resolve("aaa-certificate-services.pem")).getFirst();
        for (Path path : problemChains(fixtures)) {
            List<X509Certificate> chain = certificates(path);
            require(chain.size() >= 2, "Incomplete incident chain: " + path.getFileName());
            X509Certificate root = path.getFileName().toString().contains("-aaa-") ? aaa : emsign;
            for (int i = 0; i < chain.size(); i++) {
                X509Certificate certificate = chain.get(i);
                X509Certificate issuer = i + 1 < chain.size() ? chain.get(i + 1) : root;
                certificate.checkValidity(VALIDATION_DATE);
                require(certificate.getIssuerX500Principal().equals(issuer.getSubjectX500Principal())
                        && issuer.getBasicConstraints() >= 0, "Invalid incident issuer: " + path.getFileName());
                // 直接验签确认样本未损坏，不要求已修复的 JDK 重新信任问题证书。
                certificate.verify(issuer.getPublicKey());
            }
        }
        System.out.println("PASS: all 12 incident fixtures have valid dates and signatures");
    }

    private static void checkProblemChains(KeyStore store, Path fixtures) throws Exception {
        List<String> accepted = new ArrayList<>();
        for (Path path : problemChains(fixtures)) {
            PKIXCertPathBuilderResult result = trustedPath(store, certificates(path));
            if (result != null) {
                accepted.add(path.getFileName() + "; trust anchor: "
                        + result.getTrustAnchor().getTrustedCert().getSubjectX500Principal());
            }
        }
        require(accepted.isEmpty(), "Unexpected trust in " + accepted.size() + " incident chains:\n"
                + String.join("\n", accepted));
    }

    private static PKIXCertPathBuilderResult trustedPath(KeyStore store, List<X509Certificate> chain) throws Exception {
        for (X509Certificate certificate : chain) { certificate.checkValidity(VALIDATION_DATE); }
        X509CertSelector leaf = new X509CertSelector();
        leaf.setCertificate(chain.get(0));
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(store, leaf);
        parameters.setDate(VALIDATION_DATE);
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
        try {
            return (PKIXCertPathBuilderResult) CertPathBuilder.getInstance("PKIX").build(parameters);
        } catch (CertPathBuilderException untrusted) {
            return null;
        }
    }

    private static List<X509Certificate> certificates(Path path) throws Exception {
        try (var input = Files.newInputStream(path)) {
            List<X509Certificate> result = new ArrayList<>();
            for (var certificate : CertificateFactory.getInstance("X.509").generateCertificates(input)) {
                result.add((X509Certificate) certificate);
            }
            return result;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) { throw new AssertionError(message); }
    }
}
