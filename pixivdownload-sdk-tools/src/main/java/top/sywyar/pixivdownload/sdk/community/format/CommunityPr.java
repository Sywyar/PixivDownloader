package top.sywyar.pixivdownload.sdk.community.format;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 平台适配器提供的原生 PR 快照；本类型不访问 GitHub，也不把自报数据认证为平台事实。 */
public record CommunityPr(String githubRepositoryId, long number, String authorAccountId,
                          String headRepositoryId, String headSha, String baseSha,
                          @JsonInclude(JsonInclude.Include.NON_NULL) String mergeSha) {
    public void validate() {
        byte[] bytes = CommunityJson.encode(this);
        CommunityJson.validateStructure("pr", CommunityJson.strictTree(bytes, bytes.length));
    }

    public void requireHuman(CommunityValues.Account actualAuthor) {
        validate();
        if (actualAuthor == null || !"User".equals(actualAuthor.type()) || !authorAccountId.equals(actualAuthor.id())) {
            throw new ContractException("BINDING_MISMATCH", "/pr/authorAccountId");
        }
    }
}
