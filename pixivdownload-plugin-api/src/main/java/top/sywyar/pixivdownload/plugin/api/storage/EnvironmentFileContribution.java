package top.sywyar.pixivdownload.plugin.api.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * 插件自有环境文件的离线迁移合同；实际 owner 和根目录由宿主赋予。
 * 声明必须稳定且不访问运行目录。宿主停止业务写入后才调用复制方法；不得启动任务或保存会话。
 * 不支持迁移的文件不声明，程序、缓存、锁和信任授权不得进入本合同。
 * @since 1.4
 */
public interface EnvironmentFileContribution {
    /** owner 专属的运行期根。 */
    enum Root {
        /** 已保存运行状态根。 */ STATE,
        /** 耐久数据与资源根。 */ DATA
    }
    /** 用户可选择的内容类别。 */
    enum Kind {
        /** 必须加密的保存状态。 */ STATE,
        /** 用户资源文件。 */ RESOURCE,
        /** 必须加密且显式选择的凭据。 */ CREDENTIALS
    }

    /**
     * 归档中的逻辑标识由宿主绑定当前 owner，不能指定其它插件的根。
     * @param id owner 内唯一的稳定标识
     * @param root 所属运行期根
     * @param directory 根下固定相对目录，空串表示根
     * @param pattern 完整相对文件名的正则表达式
     * @param depth 扫描目录深度，1 至 16
     * @param kind 内容类别
     * @param schemaVersion 内容格式正整数版本
     * @param maxFileBytes 单文件输入与输出字节上限，仍受宿主累计限制
     * @param requiresDatabase 是否要求数据库同时恢复
     */
    record Definition(String id, Root root, String directory, String pattern, int depth, Kind kind,
                      int schemaVersion, long maxFileBytes, boolean requiresDatabase) {}

    /**
     * 返回不读取运行目录的稳定声明。
     * @return 文件选择和校验合同
     */
    Definition definition();

    /**
     * 验证并输出可恢复的内容，导出和导入均调用。两种方向必须幂等；活动执行转为暂停。
     * existing 为导入时目标的只读内容，导出或目标缺失时为 null；不得关闭参数流。
     * includeCredentials=false 时必须剔除内嵌凭据。输出仍受宿主累计预算约束。
     * @param relativeName 声明目录下的完整相对文件名
     * @param source 本次待迁移内容
     * @param existing 当前目标内容，导出或目标缺失时为 null
     * @param output 已验证、可安全恢复的内容输出
     * @param includeCredentials 是否允许包含内嵌凭据
     * @throws IOException 内容无效、格式不支持或读写失败
     */
    void copy(String relativeName, InputStream source, InputStream existing, OutputStream output,
              boolean includeCredentials) throws IOException;
}
