package top.sywyar.pixivdownload.sdk.community.operation;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.*;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

@DisplayName("操作审计绑定实际批准、合并事实及可取回前后状态")
class OperationAuditTest {
    private static final String HEAD = "ab".repeat(20);

    @Test
    @DisplayName("三类操作记录可回读，每份状态和决定必须有完整字节证据")
    void roundTripsAndRequiresEvidence() throws Exception {
        for (var kind : List.of(CommunityJson.Kind.ROTATION, CommunityJson.Kind.STATUS_REQUEST, CommunityJson.Kind.TRANSFER)) {
            var f = new Fixture(kind);
            var audit = OperationAudit.read(f.create());
            assertThat(audit.actorAccountId()).isEqualTo("101");
            assertThat(audit.prEvidence().get(0).mergeSha()).isNotNull();
            for (String path : List.copyOf(f.evidence.keySet())) {
                var removed = f.evidence.remove(path);
                assertThatThrownBy(f::create).as(path).isInstanceOf(ContractException.class);
                f.evidence.put(path, removed);
            }
        }
    }

    @Test
    @DisplayName("审计不能借用其它请求、操作者、批准、前后状态或未发生的合并")
    void rejectsDetachedFacts() throws Exception {
        var f = new Fixture(CommunityJson.Kind.STATUS_REQUEST);
        var document = f.create();
        for (String field : List.of("requestId", "actorAccountId")) {
            var tree = (ObjectNode) document.value(); tree.put(field, field.equals("requestId") ? "cd".repeat(32) : "999");
            var changed = OperationAudit.read(CommunityJson.parse(CommunityJson.Kind.AUDIT, CommunityJson.encode(tree)));
            assertThatThrownBy(() -> changed.verify(f.request, f.authority, f.before, f.after, List.of(f.pr),
                    f.recovery, f.sequence, f.evidence)).isInstanceOf(ContractException.class);
        }
        var tree = (ObjectNode) document.value();
        ((ObjectNode) tree.get("prEvidence").get(0)).remove("mergeSha");
        assertThatThrownBy(() -> OperationAudit.read(CommunityJson.parse(CommunityJson.Kind.AUDIT, CommunityJson.encode(tree))))
                .isInstanceOf(ContractException.class);
        var approved = f.authority;
        f.authority = new OperationAuthority(f.pr, approved.actualAuthor(), List.of(), null, Set.of("202"));
        assertThatThrownBy(f::create).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.code()).isEqualTo("APPROVAL_REQUIRED"));
        f.authority = new OperationAuthority(f.pr, approved.actualAuthor(), List.of(),
                new OperationAuthority.Approval(approved.approval().requestId(), HEAD, Set.of("202"), false, approved.approval().evidence()), Set.of("202"));
        assertThatThrownBy(f::create).isInstanceOfSatisfying(ContractException.class,
                e -> assertThat(e.code()).isEqualTo("RECOVERY_REVIEW_REQUIRED"));
        f.authority = approved; f.recovery = null;
        assertThatThrownBy(f::create).isInstanceOf(ContractException.class);
    }

    private static final class Fixture {
        final Map<String, Evidence> evidence = new HashMap<>();
        final CommunityJson.Document request;
        final Reference requestRef, before, after;
        final CommunityPr pr = new CommunityPr("300", 7, "101", "400", HEAD, HEAD, "cd".repeat(20));
        final Long sequence;
        OperationAuthority authority;
        List<Reference> recovery;
        Fixture(CommunityJson.Kind kind) throws Exception {
            ObjectNode data;
            try (var in = getClass().getResourceAsStream("/community/v1/vectors/structure/" + kind.definition() + ".json")) {
                data = (ObjectNode) CommunityJson.read(kind, in).value();
            }
            if (kind == CommunityJson.Kind.ROTATION || kind == CommunityJson.Kind.STATUS_REQUEST) {
                ((ObjectNode) data.get("proofs")).remove(kind == CommunityJson.Kind.ROTATION ? "oldKey" : "activeKey");
            }
            var first = CommunityJson.parse(kind, CommunityJson.encode(data));
            String id = CommunityJson.sha256(CommunityJson.canonicalBody(first)); data.put("requestId", id);
            request = CommunityJson.parse(kind, CommunityJson.encode(data));
            requestRef = put("requests/request.json", request.bytes());
            before = put("history/before.json", "before".getBytes(StandardCharsets.UTF_8));
            after = put("history/after.json", "after".getBytes(StandardCharsets.UTF_8));
            var approval = put("evidence/decision.json", "protected decision".getBytes(StandardCharsets.UTF_8));
            recovery = kind == CommunityJson.Kind.TRANSFER ? null : List.of(approval);
            authority = new OperationAuthority(pr, new Account("101", "User"), List.of(),
                    new OperationAuthority.Approval(id, HEAD, Set.of("202"), true, evidence.get(approval.path())), Set.of("202"));
            sequence = kind == CommunityJson.Kind.STATUS_REQUEST ? 2L : null;
        }
        CommunityJson.Document create() {
            return OperationAudit.create(request, requestRef, before, after, authority, List.of(pr), recovery,
                    "2025-01-02T03:04:05Z", sequence, evidence);
        }
        Reference put(String path, byte[] bytes) {
            var ref = Reference.of(path, bytes); evidence.put(path, new Evidence(ref, bytes)); return ref;
        }
    }
}
