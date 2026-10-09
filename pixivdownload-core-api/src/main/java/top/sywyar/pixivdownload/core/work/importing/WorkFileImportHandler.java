package top.sywyar.pixivdownload.core.work.importing;

import top.sywyar.pixivdownload.core.work.model.WorkType;

/**
 * 可选作品类型的登记能力；宿主负责文件核验和包围整个登记的事务。
 * 插件将实现声明为 child context Bean，由宿主按 publication 发布；停用时撤回并等待调用结束。
 * ARTWORK 由宿主提供，不接受替换；其余每种类型只能有一个活动 owner。
 * 实现必须在调用线程使用宿主共享作品库事务，不另开事务或异步登记。
 */
public interface WorkFileImportHandler {
    /**
     * {@return 本实现负责的作品类型}
     */
    WorkType workType();
    /**
     * 在宿主事务内登记完整作品。不得修改源文件，已有记录不得覆盖。
     * @param request 已核验本地文件的导入请求
     * @return 本次创建记录时为 true
     */
    boolean register(WorkFileImportRequest request);
}
