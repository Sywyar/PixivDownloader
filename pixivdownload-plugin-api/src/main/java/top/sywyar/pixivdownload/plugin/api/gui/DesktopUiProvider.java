package top.sywyar.pixivdownload.plugin.api.gui;

import top.sywyar.pixivdownload.plugin.api.gui.document.DesktopUiNode;

import java.util.Set;

/** 由外置插件提供、在进程生命周期内存活的桌面界面。 */
public interface DesktopUiProvider {
    /**
     * 返回 {@code app.gui-provider} 使用的稳定 provider id。
     *
     * @return 稳定 provider id
     */
    String id();

    /**
     * 返回未配置 id 时此 provider 是否为默认项。
     *
     * @return 是否为默认 provider
     */
    default boolean defaultProvider() { return false; }

    /**
     * 返回宿主构造完整桌面文档时使用的体验档位。
     *
     * @return 桌面体验档位
     */
    default DesktopUiExperienceProfile experienceProfile() {
        return DesktopUiExperienceProfile.CLASSIC;
    }

    /**
     * 返回此 provider 能渲染的稳定声明式节点种类，包括每个已声明种类定义的全部语义变体。
     * provider 必须在启动前声明宿主文档需要的全部种类。
     *
     * @return 不可变的受支持节点种类集合
     */
    Set<DesktopUiNode.Kind> supportedNodeKinds();

    /**
     * 返回此 provider 已实现的稳定语义能力。
     * 集合必须显式列举，避免未来新增能力时旧 provider 被隐式扩展。
     *
     * @return 不可变的受支持语义能力集合
     */
    Set<DesktopUiCapability> supportedCapabilities();

    /**
     * 启动进程生命周期内的桌面界面。
     *
     * @param context 不可变的宿主启动上下文
     * @return 活动界面会话
     * @throws Exception 界面无法启动时抛出
     */
    DesktopUiSession launch(DesktopUiContext context) throws Exception;
}
