import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertPathBuilder;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertStore;
import java.security.cert.CertificateFactory;
import java.security.cert.CollectionCertStoreParameters;
import java.security.cert.PKIXBuilderParameters;
import java.security.cert.X509CertSelector;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

public final class RuntimeTrustTest {
    private static final char[] PASSWORD = "changeit".toCharArray();
    // 固定到公开样本捕获日，避免过期让拒绝测试产生假阳性。
    private static final Date VALIDATION_DATE = Date.from(Instant.parse("2026-10-20T12:00:00Z"));

    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[1]);
        if (args[0].equals("self-test")) {
            selfTest(fixtures, Path.of(args[2]));
        } else if (args[0].equals("runtime")) {
            KeyStore defaults = KeyStore.getInstance(Path.of(System.getProperty("java.home"),
                    "lib", "security", "cacerts").toFile(), PASSWORD);
            X509Certificate root = certificates(fixtures.resolve("emsign-ecc-g3.pem")).get(0);
            for (String alias : Collections.list(defaults.aliases())) {
                require(!Arrays.equals(defaults.getCertificate(alias).getPublicKey().getEncoded(),
                        root.getPublicKey().getEncoded()), "Distrusted root key remains: " + alias);
            }
            checkProblemChains(defaults, fixtures, false);
            // 真实默认 TrustManager 必须仍有信任锚，不能以清空信任库冒充修复。
            TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            factory.init((KeyStore) null);
            X509TrustManager manager = (X509TrustManager) factory.getTrustManagers()[0];
            require(manager.getAcceptedIssuers().length > 0, "Default TLS trust store is empty");
            System.out.println("PASS: bundled runtime rejects all 12 compromised chains: " + System.getProperty("java.home"));
        } else {
            throw new IllegalArgumentException("Unknown test mode");
        }
    }

    private static void selfTest(Path fixtures, Path work) throws Exception {
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
            checkProblemChains(store, fixtures, true);
            require(trusted(store, positive), "Positive chain rejected before sanitization");
            Path path = work.resolve(type + ".cacerts");
            try (var output = Files.newOutputStream(path)) { store.store(output, PASSWORD); }
            RemoveDistrustedCa.remove(path);
            KeyStore filtered = KeyStore.getInstance(path.toFile(), PASSWORD);
            require(filtered.size() == 1 && Arrays.equals(positiveRoot.getEncoded(),
                    filtered.getCertificate("emsigneccrootcag3").getEncoded()), "Unrelated trust anchor changed");
            checkProblemChains(filtered, fixtures, false);
            require(trusted(filtered, positive), "Positive chain rejected after sanitization");
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
        System.out.println("PASS: JKS/PKCS12, duplicate aliases, positive chain, idempotence and corrupt input");
    }

    private static void checkProblemChains(KeyStore store, Path fixtures, boolean before) throws Exception {
        List<Path> chains;
        try (var files = Files.list(fixtures.resolve("emudhra"))) {
            chains = files.filter(path -> path.toString().endsWith(".pem")).sorted().toList();
        }
        require(chains.size() == 12, "Expected all 12 published incident chains");
        for (Path path : chains) {
            boolean expected = before && !path.getFileName().toString().contains("-aaa-");
            require(trusted(store, certificates(path)) == expected, "Unexpected trust: " + path.getFileName());
        }
    }

    private static boolean trusted(KeyStore store, List<X509Certificate> chain) throws Exception {
        for (X509Certificate certificate : chain) { certificate.checkValidity(VALIDATION_DATE); }
        X509CertSelector leaf = new X509CertSelector();
        leaf.setCertificate(chain.get(0));
        PKIXBuilderParameters parameters = new PKIXBuilderParameters(store, leaf);
        parameters.setDate(VALIDATION_DATE);
        parameters.setRevocationEnabled(false);
        parameters.addCertStore(CertStore.getInstance("Collection", new CollectionCertStoreParameters(chain)));
        try {
            CertPathBuilder.getInstance("PKIX").build(parameters);
            return true;
        } catch (CertPathBuilderException untrusted) {
            return false;
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
