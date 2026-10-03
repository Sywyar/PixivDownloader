package top.sywyar.pixivdownload.common;

import top.sywyar.pixivdownload.plugin.runtime.artifact.PluginDevelopmentArtifacts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** 开发进程启动时的源码身份；只用于显示，不参与发行或兼容性判断。 */
public final class DevelopmentBuildInfo {
    private static final Pattern RELEASE_TAG = Pattern.compile(
            "v(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-beta\\.[1-9][0-9]*)?");
    private static final int MAX_OUTPUT_BYTES = 64 * 1024;
    private static final long QUERY_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(4);

    private DevelopmentBuildInfo() {}

    private static final class Startup {
        private static final Snapshot VALUE = read(
                Path.of(System.getProperty("user.dir", ".")),
                Path.of(System.getProperty(PluginDevelopmentArtifacts.ROOT_PROPERTY,
                        System.getProperty("user.dir", ".")))
        );
    }

    public static Snapshot current() {
        return PluginDevelopmentArtifacts.enabled() ? Startup.VALUE : null;
    }

    static Snapshot read(Path directory, Path sourceDirectory) {
        long deadline = System.nanoTime() + QUERY_TIMEOUT_NANOS;
        String revision = git(sourceDirectory, deadline, "rev-parse", "--short=8", "HEAD");
        if (revision == null || !revision.matches("[0-9a-f]{8,40}")) revision = "";
        String branch = revision.isEmpty() ? "" : Objects.requireNonNullElse(git(sourceDirectory, deadline,
                "symbolic-ref", "--quiet", "--short", "HEAD"), "");
        String tags = revision.isEmpty() ? "" : Objects.requireNonNullElse(git(sourceDirectory, deadline,
                "tag", "--merged", "HEAD", "--list", "v*"), "");
        List<String> describe = new ArrayList<>(List.of("describe", "--tags", "--abbrev=0"));
        tags.lines().filter(tag -> RELEASE_TAG.matcher(tag).matches())
                .forEach(tag -> describe.add("--match=" + tag));
        String tag = describe.size() == 3 ? "" : git(sourceDirectory, deadline, describe.toArray(String[]::new));
        String baseVersion = tag != null && RELEASE_TAG.matcher(tag).matches() ? tag.substring(1) : "";
        String status = revision.isEmpty() ? "" : git(sourceDirectory, deadline,
                "status", "--porcelain", "--untracked-files=normal");
        return new Snapshot(directory.toAbsolutePath().normalize().toString(), branch,
                status == null ? "" : revision, baseVersion, status != null && !status.isBlank());
    }

    private static String git(Path directory, long deadline, String... arguments) {
        if (System.nanoTime() >= deadline) return null;
        var command = new ArrayList<>(List.of("git", "-C", directory.toAbsolutePath().toString()));
        command.addAll(List.of(arguments));
        Process process = null;
        try {
            var builder = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD);
            for (String key : List.of("GIT_DIR", "GIT_WORK_TREE", "GIT_COMMON_DIR", "GIT_INDEX_FILE")) {
                builder.environment().remove(key);
            }
            builder.environment().put("GIT_OPTIONAL_LOCKS", "0");
            builder.environment().put("GIT_TERMINAL_PROMPT", "0");
            process = builder.start();
            var input = process.getInputStream();
            var output = CompletableFuture.supplyAsync(() -> {
                try (input) { return input.readNBytes(MAX_OUTPUT_BYTES + 1); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            long remaining = Math.max(1, deadline - System.nanoTime());
            if (!process.waitFor(remaining, TimeUnit.NANOSECONDS) || process.exitValue() != 0) return null;
            byte[] bytes = output.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            if (bytes.length > MAX_OUTPUT_BYTES) return null;
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception unavailable) {
            return null;
        } finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) { }
            }
        }
    }

    public record Snapshot(String directory, String branch, String revision, String baseVersion, boolean dirty) {
        public String displayVersion(String base) {
            if (base == null || base.isBlank()) return "";
            return base + "-dev." + (revision.isEmpty() ? "unknown" : revision) + (dirty ? ".dirty" : "");
        }
    }
}
