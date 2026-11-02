package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;
import java.util.Optional;

/**
 * 正式页面、操作要求及资源权限的同一权威修订。编辑器只保存草稿。
 * 读写失败均明确拒绝；预检不产生挑战或业务副作用，发布不自动重试。
 */
public interface PagePublications {
    /** @return 当前正式修订；存储未知时抛出不可用错误 */
    long revision();
    /**
     * @param page owner 命名空间中的页面身份
     * @return 正式存档；提供者缺席时仍可读取并修复，投影不含当前运行期 publication
     */
    Optional<Published> read(String page);
    /**
     * 无副作用预检，重建可信操作要求与资源权限；不生成挑战。
     * @param revision 当前正式修订
     * @param changes 本次一起提交的页面
     * @return 固定输入及依赖身份的候选计划
     * @throws Conflict 未提交的关联页仍持有不同操作要求；可读取其正式内容后显式重建计划
     */
    Plan prepare(long revision, List<Change> changes);
    /**
     * 复核候选，封闭并排空受影响操作，然后提交同一正式修订；失败不自动重试。
     * @param plan 预检返回的完整候选
     * @param approvals 管理员明确批准的精确资源权限
     * @return 提交后的正式修订
     */
    long publish(Plan plan, List<Permission> approvals);

    /** 操作要求冲突的未提交页面；只提供重建候选的线索，不授予发布或跳过校验的权限。 */
    final class Conflict extends IllegalArgumentException {
        /** 正式快照中与本次候选要求不同的页面。 */
        private final List<String> pages;
        /** @param pages 要求不同的未提交页面身份，来自正式快照 */
        public Conflict(List<String> pages) {
            super("PAGE_OPERATION_CONFLICT");
            this.pages = List.copyOf(pages);
        }
        /** @return 需要重新选择内容并预检的页面 */
        public List<String> pages() { return pages; }
    }

    /** 编辑器文档只用于恢复编辑；宿主从投影和可信目录重建安全关系。 */
    record Change(String page, String document, String projection) { }
    /** 当前正式文档、呈现投影及用于显式回滚的上一文档。 */
    record Published(long revision, String document, String projection, String previousDocument) { }
    /** 与可信 provider 和策略绑定的资源权限。 */
    record Permission(String owner, String policy, PageResourceProfile profile) { }
    /** 发布前后该操作的必需策略及所有引用页面，供管理员确认影响。 */
    record Impact(String operation, List<String> before, List<String> after, List<String> pages) {
        /**
         * 固定影响快照。
         * @param operation 操作身份
         * @param before 当前必需策略
         * @param after 候选必需策略
         * @param pages 引用页面
         */
        public Impact { before = List.copyOf(before); after = List.copyOf(after); pages = List.copyOf(pages); }
    }
    /** 指纹固定全部输入和依赖；提交时重新预检，不能以客户端提交的清单代替校验。 */
    record Plan(long revision, String fingerprint, List<Change> changes, List<Impact> impacts, List<Permission> permissions) {
        /**
         * 固定预检结果。
         * @param revision 预检依据的正式修订
         * @param fingerprint 全部输入与依赖的指纹
         * @param changes 本次提交的文档
         * @param impacts 操作保护变化
         * @param permissions 必须明确授权的资源权限
         */
        public Plan { changes = List.copyOf(changes); impacts = List.copyOf(impacts); permissions = List.copyOf(permissions); }
    }
}
