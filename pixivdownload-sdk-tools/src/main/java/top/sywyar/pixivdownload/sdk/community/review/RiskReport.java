package top.sywyar.pixivdownload.sdk.community.review;

import com.fasterxml.jackson.annotation.JsonInclude;
import top.sywyar.pixivdownload.plugin.runtime.descriptor.PluginRiskDeclaration;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;
import top.sywyar.pixivdownload.sdk.community.submission.ControlledCatalogs;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** 确定观测与阻断项分别保存；扫描未完成始终保留原始失败状态。 */
public record RiskReport(int schemaVersion, Status status, String scannerVersion, String rulesSha256,
                         String runId, long runAttempt, String headSha, String sourceCommit, String packageSha256,
                         List<Observation> observations, List<Finding> findings,
                         @JsonInclude(JsonInclude.Include.NON_NULL) String failureReason) {
    public enum Status { COMPLETE, INCOMPLETE }
    public enum Origin { PLUGIN, DEPENDENCY, BUILD, HOST }
    public enum Kind { DECLARATION_MISSING, POLICY_VIOLATION }
    public RiskReport { observations = List.copyOf(observations); findings = List.copyOf(findings); }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Location(String archivePath, String className, String methodName, String methodDescriptor,
                           Long bytecodeOffset, String sourcePath, Long sourceLine) {
        void validate() {
            CommunityPaths.relative(archivePath, false);
            boolean bytecode = className != null && methodName != null && methodDescriptor != null && bytecodeOffset != null;
            boolean source = sourcePath != null && sourceLine != null;
            if (!bytecode && !source || !bytecode && (className != null || methodName != null || methodDescriptor != null || bytecodeOffset != null)
                    || !source && (sourcePath != null || sourceLine != null)) {
                throw new ContractException("REVIEW_MISMATCH", "/observations/location");
            }
            if (source) CommunityPaths.relative(sourcePath, false);
        }
    }
    public record Observation(String observationId, String signal, Origin origin, Location location, List<Reference> evidence) {
        public Observation { evidence = List.copyOf(evidence); }
    }
    public record Finding(String findingId, String ruleId, @JsonInclude(JsonInclude.Include.NON_NULL) String signal,
                          Kind kind, List<String> observationIds, List<Reference> evidence) {
        public Finding { observationIds = List.copyOf(observationIds); evidence = List.copyOf(evidence); }
    }

    public static RiskReport read(Evidence document, int maximumBytes) {
        return CommunityJson.decode("riskReport", document.bytes(), maximumBytes, RiskReport.class);
    }

    /** 只接受有具体位置及可取回证据的观测；依赖存在与构建行为不自动归因给插件。 */
    public void validate(PluginRiskDeclaration declaration, Map<String, Evidence> evidence) {
        CommunityValues.unique(observations, Observation::observationId, "/observations");
        CommunityValues.unique(findings, Finding::findingId, "/findings");
        var byId = new HashMap<String, Observation>();
        var expectedMissing = new HashSet<String>();
        for (var observation : observations) {
            ControlledCatalogs.require(ControlledCatalogs.RISK_SIGNALS, observation.signal, "/observations/signal");
            observation.location.validate();
            observation.evidence.forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
            byId.put(observation.observationId, observation);
            if (observation.origin == Origin.PLUGIN && !declaration.signals().contains(observation.signal)) {
                expectedMissing.add(observation.observationId);
            }
        }
        var actualMissing = new HashSet<String>();
        for (var finding : findings) {
            if (finding.signal != null) ControlledCatalogs.require(ControlledCatalogs.RISK_SIGNALS, finding.signal, "/findings/signal");
            finding.evidence.forEach(ref -> CommunityValues.requireEvidence(ref, evidence));
            for (var id : finding.observationIds) {
                var observation = byId.get(id);
                if (observation == null) throw new ContractException("REVIEW_MISMATCH", "/findings/observationIds");
                if (finding.kind == Kind.DECLARATION_MISSING) {
                    if (observation.origin != Origin.PLUGIN || !observation.signal.equals(finding.signal)) {
                        throw new ContractException("REVIEW_MISMATCH", "/findings/signal");
                    }
                    actualMissing.add(id);
                }
            }
        }
        if (!expectedMissing.equals(actualMissing)) throw new ContractException("REVIEW_MISMATCH", "/findings");
    }
}
