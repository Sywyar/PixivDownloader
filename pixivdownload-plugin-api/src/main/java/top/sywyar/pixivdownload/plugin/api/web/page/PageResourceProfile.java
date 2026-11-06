package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/**
 * 有版本的资源请求；声明不授予权限，只有已发布页面的明确授权才生效。
 * @param id 资源请求身份
 * @param revision 资源声明修订
 * @param sources 按类别声明的资源来源
 */
public record PageResourceProfile(String id, String revision, List<Source> sources) {
    /** 分别编译到对应 CSP 指令的资源类别。 */
    public enum Kind {
        /** 脚本。 */ SCRIPT,
        /** 嵌入 frame。 */ FRAME,
        /** 浏览器网络连接。 */ CONNECT,
        /** 样式。 */ STYLE,
        /** 图片。 */ IMAGE,
        /** 字体。 */ FONT,
        /** 浏览器工作线程。 */ WORKER
    }
    /**
     * origin 只接受同源标记 self、HTTPS origin 或明确申请的 blob Worker。
     * @param kind 资源类别
     * @param origin 同源标记、HTTPS origin 或明确申请的 blob Worker 来源
     */
    public record Source(Kind kind, String origin) { }
}
