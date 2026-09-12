package top.sywyar.pixivdownload.sdk.community.operation;

import top.sywyar.pixivdownload.sdk.community.format.CommunityJson;
import top.sywyar.pixivdownload.sdk.community.format.CommunityPr;
import top.sywyar.pixivdownload.sdk.community.format.CommunityValues.Account;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** 正文只表达角色；批准人的身份来自这份文件所在独立 PR 的平台事实。 */
public record TransferApproval(int schemaVersion, String requestId, Role role) {
    public enum Role { FROM, TO }
    public record Input(CommunityJson.Document document, String path, CommunityPr pr, Account actualAuthor,
                        boolean newlyAdded) { }

    public static TransferApproval read(Input input, OwnershipTransferRequest request, OperationAuthority authority) {
        if (input.document.kind() != CommunityJson.Kind.APPROVAL) throw new ContractException("SCHEMA_INVALID", "");
        var value = input.document.as(TransferApproval.class);
        if (!value.requestId.equals(request.requestId())) throw new ContractException("REQUEST_ID_MISMATCH", "/requestId");
        input.pr.requireHuman(input.actualAuthor);
        if (!input.newlyAdded) throw new ContractException("APPROVAL_REQUIRED", "/approval");
        String rolePath = value.role == Role.FROM ? "from" : "to";
        OperationChecks.path(input.path, "ownership-transfers/" + request.payload().pluginId() + "/" + request.requestId()
                + "/approvals/" + rolePath + "/" + input.actualAuthor.id() + ".json");
        if (!input.pr.githubRepositoryId().equals(authority.proposalPr().githubRepositoryId())) {
            throw new ContractException("BINDING_MISMATCH", "/pr/githubRepositoryId");
        }
        authority.requireRepresentative(value.role == Role.FROM ? request.payload().from() : request.payload().to(),
                input.actualAuthor.id());
        return value;
    }

    /** 跨账号要求不同自然人和不同 PR；同数字账号仍需明确的两个角色文件。 */
    public static void requireApprovals(List<Input> inputs, OwnershipTransferRequest request, OperationAuthority authority) {
        var from = new ArrayList<Input>();
        var to = new ArrayList<Input>();
        var paths = new HashSet<String>();
        for (var input : inputs) {
            if (!paths.add(input.path)) throw ContractException.invalid("DUPLICATE_KEY", "/approvals");
            var approval = read(input, request, authority);
            (approval.role == Role.FROM ? from : to).add(input);
        }
        if (to.isEmpty()) throw new ContractException("APPROVAL_REQUIRED", "/approvals/to");
        if (request.payload().mode() == OwnershipTransferRequest.Mode.RECOVERY) {
            authority.requireApproval(request.requestId(), true);
            return;
        }
        if (from.isEmpty()) throw new ContractException("APPROVAL_REQUIRED", "/approvals/from");
        if (request.payload().from().accountId().equals(request.payload().to().accountId())) return;
        if (from.stream().noneMatch(left -> to.stream().anyMatch(right ->
                !left.actualAuthor.id().equals(right.actualAuthor.id()) && left.pr.number() != right.pr.number()))) {
            throw new ContractException("APPROVAL_REQUIRED", "/approvals");
        }
    }
}
