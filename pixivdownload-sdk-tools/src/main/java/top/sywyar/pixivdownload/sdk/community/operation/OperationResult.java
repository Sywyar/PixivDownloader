package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;
import top.sywyar.pixivdownload.sdk.community.project.CommunityPaths;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** 一次操作的完整候选输出；执行器须原子提交这些写入及审计，重放不得重写当前状态。 */
public record OperationResult(CommunityJson.Document audit, Map<String, Evidence> evidence, Map<String, Reference> writes) {
    public record Outcome(OperationResult result, boolean replayed) { }

    public OperationResult {
        evidence = Map.copyOf(evidence);
        writes = Map.copyOf(writes);
        var record = OperationAudit.read(audit);
        for (var reference : List.of(record.requestRef(), record.beforeRef(), record.afterRef(), record.decisionRef())) {
            CommunityValues.requireEvidence(reference, evidence);
        }
        if (record.recoveryEvidence() != null) {
            for (var reference : record.recoveryEvidence()) CommunityValues.requireEvidence(reference, evidence);
        }
        for (var reference : record.relatedRecords()) CommunityValues.requireEvidence(reference, evidence);
        if (!writes.containsValue(record.afterRef())) throw new ContractException("REVIEW_MISMATCH", "/writes");
        for (var write : writes.entrySet()) {
            CommunityPaths.relative(write.getKey(), false);
            CommunityValues.requireEvidence(write.getValue(), evidence);
            if (!write.getValue().equals(record.afterRef()) && !record.relatedRecords().contains(write.getValue())) {
                throw new ContractException("REVIEW_MISMATCH", "/writes");
            }
        }
    }

    /** 已执行索引只能来自受保护的审计存储；先核对旧请求正文，再返回原输出而不重新批准。 */
    public Outcome replay(CommunityJson.Document request) {
        var record = OperationAudit.read(audit);
        var previous = CommunityJson.parse(request.kind(), CommunityValues.requireEvidence(record.requestRef(), evidence).bytes());
        byte[] body = CommunityJson.canonicalBody(request);
        if (!record.requestId().equals(CommunityJson.sha256(body))
                || !record.requestId().equals(previous.value().get("requestId").textValue())
                || !Arrays.equals(body, CommunityJson.canonicalBody(previous))) {
            throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        }
        return new Outcome(this, true);
    }

    public CommunityJson.Document written(String path, CommunityJson.Kind kind) {
        var reference = writes.get(path);
        if (reference == null) throw new ContractException("PATH_MISMATCH", "/writes");
        return CommunityJson.parse(kind, CommunityValues.requireEvidence(reference, evidence).bytes());
    }
}
