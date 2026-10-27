package top.sywyar.pixivdownload.sdk.community.project;

/** 候选构建的工程和产物定位，不包含投稿或审核状态。 */
public record BuildProfile(String id, String projectDir, String artifactPath) {
    public void validate() {
        CommunityPaths.relative(projectDir, true);
        CommunityPaths.relative(artifactPath, false);
    }
}
