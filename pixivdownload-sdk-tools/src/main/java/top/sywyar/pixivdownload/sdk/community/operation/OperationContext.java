package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Evidence;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Reference;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 已冻结请求、受保护身份和原始证据；只构造纯数据结果，不访问平台或修改文件。 */
public record OperationContext(Evidence request, OperationAuthority authority, List<Reference> recoveryEvidence,
                               String appliedAt, Map<String, Evidence> evidence, Map<String, OperationResult> executed) {
    public OperationContext {
        recoveryEvidence = recoveryEvidence == null ? null : List.copyOf(recoveryEvidence);
        evidence = Map.copyOf(evidence);
        executed = Map.copyOf(executed);
    }

    CommunityJson.Document document(CommunityJson.Kind kind) { return CommunityJson.parse(kind, request.bytes()); }

    OperationResult.Outcome replay(CommunityJson.Document document) {
        var previous = executed.get(document.value().get("requestId").textValue());
        return previous == null ? null : previous.replay(document);
    }

    static Evidence archive(byte[] bytes) {
        return new Evidence(Reference.of("records/" + CommunityJson.sha256(bytes) + ".json", bytes), bytes);
    }

    OperationResult.Outcome finish(CommunityJson.Document document, Evidence before, Evidence after,
                                    Map<String, Evidence> outputs, List<Evidence> additional,
                                    List<CommunityPr> prs, Long sequence) {
        var records = new HashMap<>(evidence);
        put(records, request); put(records, before); put(records, after);
        put(records, authority.approval().evidence());
        for (var representation : authority.representations()) put(records, representation.evidence());
        for (var item : additional) put(records, item);
        var related = new LinkedHashMap<String, Reference>();
        for (var item : additional) related.put(item.reference().path(), item.reference());
        for (var representation : authority.representations()) {
            related.put(representation.evidence().reference().path(), representation.evidence().reference());
        }
        var writes = new LinkedHashMap<String, Reference>();
        outputs.forEach((path, value) -> { put(records, value); writes.put(path, value.reference()); });
        var audit = OperationAudit.create(document, request.reference(), before.reference(), after.reference(), authority,
                prs, recoveryEvidence, appliedAt, sequence,
                related.values().stream().sorted(java.util.Comparator.comparing(Reference::path)).toList(), records);
        return new OperationResult.Outcome(new OperationResult(audit, records, writes), false);
    }

    private static void put(Map<String, Evidence> records, Evidence value) {
        var previous = records.putIfAbsent(value.reference().path(), value);
        if (previous != null && !previous.reference().equals(value.reference())) {
            throw new ContractException("HASH_MISMATCH", value.reference().path());
        }
    }
}
