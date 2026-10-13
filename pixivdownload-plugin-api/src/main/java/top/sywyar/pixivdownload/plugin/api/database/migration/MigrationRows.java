package top.sywyar.pixivdownload.plugin.api.database.migration;

import java.util.List;

/**
 * 一行一读的中性游标；列按 schema 声明排列，毫秒时间保持有符号 64 位整数。
 * 行按复合主键列序逐列升序，NULL 在前；数值按数值、TEXT 按 Unicode 标量、BLOB 按无符号字节比较。
 * 文本顺序等价于有效 UTF-8 字节的字典序，不依赖源编码或数据库 locale。
 */
public interface MigrationRows extends AutoCloseable {
    /**
     * 取得下一完整行；读取失败不能当成流结束。
     * @return 是否存在下一行
     * @throws Exception 读取或值校验失败
     */
    boolean next() throws Exception;
    /** {@return 当前行；有效期到下一次 next，调用方不得修改二进制值} */
    List<Object> values();
    /** 释放本游标独占的资源，不关闭所属源会话。 */
    @Override void close() throws Exception;
}
