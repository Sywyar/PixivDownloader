package top.sywyar.pixivdownload.sdk.community.directory;

import top.sywyar.pixivdownload.plugin.signature.PluginSupplyChainVerifier;
import top.sywyar.pixivdownload.plugin.signature.SignatureMetadata;
import top.sywyar.pixivdownload.sdk.community.format.ContractException;

import java.net.URI;

/** 本地纯数据入口：整代验证成功后一次切换；实际远端发布由受保护执行器提交这一代。 */
public final class DirectoryGenerations {
    private final String repositoryId;
    private final URI rootUrl;
    private final PluginSupplyChainVerifier verifier;
    private volatile DirectoryGeneration current;

    public DirectoryGenerations(String repositoryId, URI rootUrl, PluginSupplyChainVerifier verifier) {
        this.repositoryId = repositoryId; this.rootUrl = rootUrl; this.verifier = verifier;
    }
    public DirectoryGeneration current() { return current; }

    /** 重启恢复必须先装入上次完整验证的一代；随后才接收远端候选，不能丢失防回滚基线。 */
    public synchronized DirectoryGeneration adopt(DirectoryGeneration.Candidate candidate, SignatureMetadata signature) {
        var root = candidate.root().as(DirectoryGeneration.Root.class);
        if (current != null) {
            if (root.sequence() < current.root().sequence()) throw new ContractException("BASELINE_CHANGED", "/sequence");
            if (root.sequence() == current.root().sequence()) {
                if (candidate.root().sha256().equals(current.candidate().root().sha256())) return current;
                throw new ContractException("BASELINE_CHANGED", "/sequence");
            }
        }
        var verified = DirectoryGeneration.verify(candidate, rootUrl, repositoryId, signature, verifier);
        current = verified;
        return verified;
    }
}
