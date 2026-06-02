package top.sywyar.pixivdownload.push;

/**
 * 推送消息的严重级别。属于<b>与通道无关</b>的语义信息：由各 {@link PushChannel} 在渲染时自行映射到通道自身的
 * 表现形式（如颜色、Bark 的 level、是否加 emoji 等）。核心模型只保留级别本身，不烤进任何展示文案。
 */
public enum PushLevel {
    INFO,
    WARNING,
    ERROR
}
