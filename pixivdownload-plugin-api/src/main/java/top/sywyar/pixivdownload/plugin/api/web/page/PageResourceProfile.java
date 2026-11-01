package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;

/** 有版本的资源请求；声明不授予权限，只有已发布页面的明确授权才生效。 */
public record PageResourceProfile(String id, String revision, List<Source> sources) {
    /** 分别编译到对应 CSP 指令的资源类别。 */
    public enum Kind {
        /** 脚本。 */ SCRIPT,
        /** 嵌入 frame。 */ FRAME,
        /** 浏览器网络连接。 */ CONNECT,
        /** 样式。 */ STYLE,
        /** 图片。 */ IMAGE,
        /** 字体。 */ FONT,
        /** Worker。 */ WORKER
    }
    /** origin 只接受同源标记 self、HTTPS origin 或明确申请的 blob Worker。 */
    public record Source(Kind kind, String origin) { }
}
