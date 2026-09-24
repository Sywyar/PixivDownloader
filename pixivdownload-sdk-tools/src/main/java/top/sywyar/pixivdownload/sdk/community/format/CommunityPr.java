package top.sywyar.pixivdownload.sdk.community.format;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 平台适配器提供的原生 PR 快照；本类型不访问 GitHub，也不把自报数据认证为平台事实。 */
public record CommunityPr(String githubRepositoryId, long number, String authorAccountId,
                          String headRepositoryId, String headSha, String baseSha,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String mergeSha) {
    public void validate() {
        byte[] bytes = CommunityJson.encode(this);
        CommunityJson.validateStructure("pr", CommunityJson.strictTree(bytes, bytes.length));
    }

    /** 生成提交保留原审核 head；平台另行核实保护来源、输入及完整结果树。 */
    public boolean hasGeneratedParents(List<String> parents) {
        if (parents.equals(List.of(headSha))) return true;
        return !headSha.equals(baseSha) && (parents.size() == 2 || parents.size() == 3)
                && parents.get(0).equals(headSha) && parents.get(1).equals(baseSha)
                && parents.stream().allMatch(parent -> parent != null && parent.matches("[a-f0-9]{40}"))
                && parents.stream().distinct().count() == parents.size();
    }

    public void requireHuman(CommunityValues.Account actualAuthor) {
        validate();
        if (actualAuthor == null || !"User".equals(actualAuthor.type()) || !authorAccountId.equals(actualAuthor.id())) {
            throw new ContractException("BINDING_MISMATCH", "/pr/authorAccountId");
        }
    }
}
