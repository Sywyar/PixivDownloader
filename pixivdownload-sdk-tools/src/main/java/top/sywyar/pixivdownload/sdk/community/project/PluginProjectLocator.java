package top.sywyar.pixivdownload.sdk.community.project;

import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission.BuildProfile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** 只读取 Git 跟踪的工程标识与调用方已求值的产物清单，不执行构建模型。 */
public final class PluginProjectLocator {
    private PluginProjectLocator() { }

    public record Project(String projectDir, List<String> profiles) {
        public Project { profiles = List.copyOf(profiles); }
    }
    public record Selection(Path project, Path artifact, BuildProfile profile) { }

    public static List<Project> discover(Path gitRoot) throws IOException, InterruptedException {
        Path root = gitRoot.toAbsolutePath().normalize();
        if (!Files.exists(root.resolve(".git"))) throw ContractException.invalid("PROJECT_GIT_REQUIRED", "");
        Process process = new ProcessBuilder("git", "--no-optional-locks", "-c", "core.fsmonitor=false",
                "-C", root.toString(), "ls-files", "--stage", "-z", "--",
                ":(glob)**/" + PluginProjectMarker.FILE_NAME)
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        var projects = new ArrayList<Project>();
        try (var input = process.getInputStream()) {
            var record = new ByteArrayOutputStream();
            int next;
            while ((next = input.read()) != -1) {
                if (next != 0) {
                    // 每条记录只含 6 位模式、完整对象 ID、stage 与已接受的路径预算。
                    if (record.size() >= 6 + 1 + 64 + 1 + 1 + 1
                            + (CommunityPaths.MAX_PATH_UNITS + 1 + PluginProjectMarker.FILE_NAME.length()) * 4) {
                        throw new ContractException("PATH_MISMATCH", "");
                    }
                    record.write(next);
                    continue;
                }
                String line = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(record.toByteArray())).toString();
                record.reset();
                int tab = line.indexOf('\t');
                if (tab < 0 || !line.substring(0, tab).matches("100(?:644|755) [0-9a-f]{40}(?:[0-9a-f]{24})? 0")) {
                    throw ContractException.invalid("PROJECT_MARKER_INVALID", "");
                }
                String marker = line.substring(tab + 1);
                if (!marker.equals(PluginProjectMarker.FILE_NAME) && !marker.endsWith("/" + PluginProjectMarker.FILE_NAME)) {
                    throw ContractException.invalid("PROJECT_MARKER_INVALID", "");
                }
                String projectDir = marker.equals(PluginProjectMarker.FILE_NAME) ? "."
                        : marker.substring(0, marker.length() - PluginProjectMarker.FILE_NAME.length() - 1);
                Path project = CommunityPaths.resolve(root, projectDir, true, true);
                PluginProjectMarker.validate(project);
                var profiles = new ArrayList<String>();
                if (modelFile(project, "pom.xml")) profiles.add("maven-java17-v1");
                if (modelFile(project, "build.gradle") || modelFile(project, "build.gradle.kts")) {
                    profiles.add("gradle-java17-v1");
                }
                if (modelFile(project, "build.sbt")) profiles.add("sbt-java17-v1");
                projects.add(new Project(projectDir, profiles));
            }
            if (record.size() != 0) throw new IOException("truncated Git output");
            if (process.waitFor() != 0) throw new IOException("Git project discovery failed");
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
        return projects.stream().sorted(Comparator.comparing(Project::projectDir)).toList();
    }

    private static boolean modelFile(Path project, String name) throws IOException {
        if (!Files.exists(project.resolve(name), java.nio.file.LinkOption.NOFOLLOW_LINKS)) return false;
        return Files.isRegularFile(CommunityPaths.resolve(project, name, false, true));
    }

    /** 多工程或多个安装候选必须明确选择；sources/Javadoc 等其它输出不受单包选择限制。 */
    public static Selection select(Path root, String projectDir, String profileId, String artifactPath,
                                   List<String> installationOutputs) throws IOException, InterruptedException {
        List<Project> projects = discover(root);
        Project project;
        if (projectDir == null) {
            if (projects.size() != 1) throw ContractException.invalid("PROJECT_SELECTION_REQUIRED", "/buildProfile/projectDir");
            project = projects.get(0);
        } else project = projects.stream().filter(item -> item.projectDir().equals(projectDir)).findFirst()
                .orElseThrow(() -> ContractException.invalid("PROJECT_MARKER_INVALID", "/buildProfile/projectDir"));
        if (!project.profiles().contains(profileId)) throw ContractException.invalid("BUILD_PROFILE_UNSUPPORTED", "/buildProfile/id");
        for (String output : installationOutputs) CommunityPaths.relative(output, false);
        if (artifactPath == null) {
            if (installationOutputs.size() != 1) throw ContractException.invalid("ARTIFACT_SELECTION_REQUIRED", "/buildProfile/artifactPath");
            artifactPath = installationOutputs.get(0);
        }
        if (!installationOutputs.contains(artifactPath)) throw ContractException.invalid("BUILD_OUTPUT_MISMATCH", "/buildProfile/artifactPath");
        Path projectPath = CommunityPaths.resolve(root, project.projectDir(), true, true);
        Path artifact = CommunityPaths.resolve(projectPath, artifactPath, false, true);
        if (!Files.isRegularFile(artifact)) throw new ContractException("PATH_MISMATCH", artifactPath);
        return new Selection(projectPath, artifact, new BuildProfile(profileId, project.projectDir(), artifactPath));
    }
}
