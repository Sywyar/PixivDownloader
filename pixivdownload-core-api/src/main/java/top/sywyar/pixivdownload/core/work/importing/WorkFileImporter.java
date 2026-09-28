package top.sywyar.pixivdownload.core.work.importing;

import java.io.IOException;

/** 核验完整本地作品并登记原文件引用；不复制、移动或修改源文件。 */
public interface WorkFileImporter {
    /**
     * 原子登记元数据与文件引用，已存在（包括软删除）时不覆盖。
     * @param request 元数据及可信调用方批准的源目录
     * @return 本次创建记录时为 true
     * @throws IOException 文件不可用、格式不受支持、导入忙或对应类型 owner 缺席
     * @throws IllegalArgumentException 元数据不完整或不满足作品登记要求
     */
    boolean importFiles(WorkFileImportRequest request) throws IOException;
}
