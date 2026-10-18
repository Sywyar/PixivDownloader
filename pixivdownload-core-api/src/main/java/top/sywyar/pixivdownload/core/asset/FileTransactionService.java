package top.sywyar.pixivdownload.core.asset;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Map;

/**
 * 核心拥有的本地文件与主库记录事务；由宿主必需基础设施提供，不依赖可选插件。
 * 调用方负责目标文件的业务归属、访问授权及并发隔离，不能修改外部只读引用。
 * 主库不可用时拒绝变更；插件停止前必须排空正在执行的调用，宿主不保留回调。
 */
public interface FileTransactionService {

    /**
     * 发布暂存文件并删除旧文件，再在同一主库事务中执行记录更新。
     * 调用必须处于主库事务之外，回调同步执行且不得另开独立事务。
     * 提交前失败恢复旧文件；提交结果不明或恢复失败时保留耐久备份，由宿主启动恢复。
     * 提交后的备份清理失败不撤销已提交结果。源文件仍归调用方清理。
     *
     * @param stagedByTarget 目标文件到已完成暂存文件的映射，不允许重复目标或源与目标相同
     * @param deletions 需要删除的普通文件，缺失文件忽略，不能与发布目标重叠
     * @param commitRecord 文件变更后在主库事务中执行的业务记录更新
     * @throws IOException 文件校验、变更或恢复失败
     */
    void changeFiles(Map<Path, Path> stagedByTarget, Collection<Path> deletions,
                     Runnable commitRecord) throws IOException;
}
