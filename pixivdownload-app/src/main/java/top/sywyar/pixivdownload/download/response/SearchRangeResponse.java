package top.sywyar.pixivdownload.download.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

/**
 * 作品批量获取（按页码范围 startPage~endPage 抓取）的聚合响应。
 * items 的运行时元素类型为 {@link SearchResponse.SearchItem} 或
 * 小说搜索结果项，
 * 由调用的范围端点决定，Jackson 按运行时类型序列化。
 */
@Getter
@AllArgsConstructor
public class SearchRangeResponse {
    private final List<?> items;
    private final int total;
    private final int startPage;
    /** 实际抓取到的结束页（受 multi 限额与总页数约束后的真实值） */
    private final int endPage;
    /** 用户请求的页数 endPage-startPage+1（未受限前） */
    private final int requestedPages;
    /** 受 multi-mode.limit-page 约束后允许抓取的页数 */
    private final int acceptedPages;
    /** 实际成功抓取的页数 */
    private final int fetchedPages;
    /** 当前生效的每次抓取页数上限（0 表示不限制） */
    private final int limitPage;
}
