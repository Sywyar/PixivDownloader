package top.sywyar.pixivdownload.schedule;

import java.util.List;

/** 给定页码返回该页作品 ID（按页内顺序）；空 / null 表示无更多结果。 */
@FunctionalInterface
public interface PageSupplier {
    List<String> get(int page) throws Exception;
}
