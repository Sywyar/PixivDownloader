package com.example.textmetrics.api;

import java.util.List;

/** 与宿主 SDK 无依赖的业务契约；接口及嵌套值类型由同一个契约包加载。 */
public interface TextMetrics {
    /** 按 Java 字符串的 UTF-16 长度计算各项和总量。 */
    Result analyze(Request request);

    /** 待统计的文本。 */
    record Request(List<String> texts) {}
    /** 各项长度及总长度。 */
    record Result(List<Integer> lengths, int total) {}
}
