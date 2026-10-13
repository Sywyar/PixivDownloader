package top.sywyar.pixivdownload.migration;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;

/** 隔离进程复用当前编译插件；不读取开发工作树中的运行数据。 */
public final class MigrationProcessHarness {
    private MigrationProcessHarness() {}
    public static int run(Path directory, Class<?> entry, String label, String... arguments) throws Exception {
        String bootJar = System.getProperty("database.migration.test.boot-jar", "");
        Path development = directory.resolve("development");
        if (!Files.exists(development)) {
            Path repository = Path.of(top.sywyar.pixivdownload.databasesqlite.SqliteDatabasePlugin.class
                    .getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent().getParent();
            for (String module : List.of("pixivdownload-plugin-database-sqlite", "pixivdownload-plugin-database-postgresql",
                    "pixivdownload-plugin-pixiv-batch-downloader-import")) {
                Path compiled = repository.resolve(module).resolve("target/classes");
                Path destination = development.resolve(module).resolve("target/classes");
                if (bootJar.isBlank()) {
                    assertThat(compiled.resolve("plugin.properties")).as("Current compiled plugin required: %s", module).exists();
                    copyClasses(compiled, destination);
                } else {
                    try (var jars = Files.list(compiled.getParent())) {
                        var candidates = jars.filter(path -> path.getFileName().toString().matches(
                                java.util.regex.Pattern.quote(module) + "-[0-9].*\\.jar"))
                                .filter(path -> !path.getFileName().toString().matches(".*-(sources|javadoc|tests)\\.jar")).toList();
                        assertThat(candidates).as("Current processed plugin required: %s", module).hasSize(1);
                        try (var archive = java.nio.file.FileSystems.newFileSystem(candidates.get(0))) {
                            assertThat(archive.getPath("/META-INF/pixivdownload-proguard.properties")).exists();
                            copyClasses(archive.getPath("/"), destination);
                        }
                    }
                }
            }
        }
        Path fixtureClasses = development.resolve("neutral-fixture/target/classes");
        if (!Files.exists(fixtureClasses)) {
            Path compiledTests = Path.of(migrationfixture.NeutralMigrationPlugin.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path packageDirectory = Files.createDirectories(fixtureClasses.resolve("migrationfixture"));
            try (var files = Files.newDirectoryStream(compiledTests.resolve("migrationfixture"), "NeutralMigrationPlugin*.class")) {
                for (Path file : files) Files.copy(file, packageDirectory.resolve(file.getFileName()));
            }
            Files.writeString(fixtureClasses.resolve("plugin.properties"), """
                    plugin.id=neutral-migration
                    plugin.version=1.0.0
                    plugin.class=migrationfixture.NeutralMigrationPlugin
                    plugin.provider=Fixture
                    plugin.description=Native row file migration fixture
                    pixiv.display-namespace=neutral-migration
                    pixiv.display-name-key=fixture.name
                    pixiv.description-key=fixture.summary
                    pixiv.icon-key=database
                    pixiv.color-token=blue
                    pixiv.kind=feature
                    pixiv.lifecycle-policy=process-restart
                    pixiv.execution-mode=host-process-full-trust
                    """, StandardCharsets.UTF_8);
        }
        List<String> command = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Dfile.encoding=UTF-8", "-Duser.language=en", "-Dpixivdownload.plugin-dev.enabled=true",
                "-Dpixivdownload.plugin-dev.root=" + development));
        for (String root : List.of("config", "state", "data", "instance", "plugins")) {
            command.add("-Dpixivdownload." + root + "-dir=" + directory.resolve(root));
        }
        if (bootJar.isBlank()) {
            command.addAll(List.of("-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    entry.getName()));
        } else {
            assertThat(Path.of(bootJar)).isRegularFile();
            command.addAll(List.of("-Dloader.main=" + entry.getName(), "-cp", bootJar,
                    "org.springframework.boot.loader.launch.PropertiesLauncher"));
        }
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true)
                .redirectOutput(directory.resolve(label + ".log").toFile()).start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("CLI should finish").isTrue();
            return process.exitValue();
        } finally {
            if (process.isAlive()) process.destroyForcibly().waitFor();
        }
    }

    private static void copyClasses(Path source, Path destination) throws Exception {
        try (var entries = Files.walk(source)) {
            for (Path entry : entries.toList()) {
                Path copy = destination.resolve(source.relativize(entry).toString()).normalize();
                assertThat(copy.startsWith(destination)).isTrue();
                if (Files.isDirectory(entry)) Files.createDirectories(copy);
                else Files.copy(entry, copy);
            }
        }
    }
}
