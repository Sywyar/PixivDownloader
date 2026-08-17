package top.sywyar.pixivdownload.core.db.pathprefix;

/**
 * 编码存储路径并解析其持久化表示，同时不公开宿主实现。
 */
public interface StoredPathCodec {

    /**
     * 查询并返回对应结果。
     *
     * @param absolutePath 绝对路径
     * @return 方法返回的字符串
     */
    String encode(String absolutePath);

    /**
     * 查询并返回对应结果。
     *
     * @param storedValue 已存储的路径值
     * @return 方法返回的字符串
     */
    String resolve(String storedValue);
}
