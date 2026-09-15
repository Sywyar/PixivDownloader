package top.sywyar.pixivdownload.sdk.community.candidate;

import com.fasterxml.jackson.databind.JsonNode;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.PluginProjectLocator;
import top.sywyar.pixivdownload.sdk.community.project.BuildModels;
import top.sywyar.pixivdownload.sdk.community.submission.VersionSubmission.BuildProfile;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.stream.StreamSupport;

/** 机器入口只接收有界 JSON 文件；stdout 仅输出结果，不启动插件。 */
public final class CandidateCommand {
    private CandidateCommand() { }

    public static void execute(Path file) throws Exception {
        JsonNode input;
        try (var stream = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            input = CommunityJson.strictTree(stream.readNBytes(SourceCandidate.MAX_BYTES + 1), SourceCandidate.MAX_BYTES);
        }
        Object result = switch (text(input, "command")) {
            case "projects" -> PluginProjectLocator.discover(Path.of(text(input, "gitRoot")));
            case "maven-model" -> BuildModels.maven(input);
            case "path" -> java.util.Map.of("path", top.sywyar.pixivdownload.sdk.community.project.CommunityPaths.resolve(
                    Path.of(text(input, "root")), text(input, "path"), input.path("allowRoot").asBoolean(), input.path("mustExist").asBoolean()).toString());
            case "read" -> {
                try (var stream = Files.newInputStream(Path.of(text(input, "file")), LinkOption.NOFOLLOW_LINKS)) {
                    yield SourceCandidate.read(stream.readNBytes(SourceCandidate.MAX_BYTES + 1));
                }
            }
            case "create" -> {
                JsonNode p = input.get("buildProfile");
                if (!input.path("outputs").isArray() || !input.path("runAttempt").isIntegralNumber()) throw new ContractException("CANDIDATE_INVALID", "");
                yield SourceCandidate.create(Path.of(text(input, "gitRoot")),
                        new BuildProfile(text(p, "id"), text(p, "projectDir"), text(p, "artifactPath")),
                        StreamSupport.stream(input.get("outputs").spliterator(), false).map(JsonNode::textValue).toList(),
                        text(input, "modelVersion"), text(input, "repositoryId"), text(input, "repository"), text(input, "sourceCommit"),
                        text(input, "runId"), input.get("runAttempt").longValue(), Path.of(text(input, "destination")));
            }
            default -> throw new ContractException("CANDIDATE_COMMAND_INVALID", "");
        };
        System.out.println(new String(CommunityJson.encode(result), java.nio.charset.StandardCharsets.UTF_8));
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.path(field).isTextual()) throw new ContractException("CANDIDATE_INVALID", "/" + field);
        return input.get(field).textValue();
    }
}
