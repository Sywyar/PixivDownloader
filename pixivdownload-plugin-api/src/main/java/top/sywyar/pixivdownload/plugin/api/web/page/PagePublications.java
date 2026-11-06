package top.sywyar.pixivdownload.plugin.api.web.page;

import java.util.List;
import java.util.Optional;
import top.sywyar.pixivdownload.plugin.api.operation.OperationBinding;
import top.sywyar.pixivdownload.plugin.api.operation.OperationHook;

/**
 * 正式页面、操作要求及资源权限的同一权威修订。编辑器只保存草稿。
 * 读写失败均明确拒绝；预检不产生挑战或业务副作用，发布不自动重试。
 */
public interface PagePublications {
    /** {@return 当前正式修订；存储未知时抛出不可用错误} */
    long revision();
    /**
     * 读取页面正式存档及可回滚文档。
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
        /** 固定需要重新预检的冲突页面。
         * @param pages 要求不同的未提交页面身份，来自正式快照
         */
        public Conflict(List<String> pages) {
            super("PAGE_OPERATION_CONFLICT");
            this.pages = List.copyOf(pages);
        }
        /** {@return 需要重新选择内容并预检的页面} */
        public List<String> pages() { return pages; }
    }

    /**
     * 编辑器文档只用于恢复编辑；宿主从投影和可信目录重建安全关系。
     * @param page owner 命名空间中的页面身份
     * @param document 用于恢复编辑的文档
     * @param projection 供宿主重建安全关系的呈现投影
     */
    record Change(String page, String document, String projection) { }
    /**
     * 当前正式文档、呈现投影及用于显式回滚的上一文档。
     * @param revision 正式发布修订
     * @param document 当前正式编辑文档
     * @param projection 当前呈现投影
     * @param previousDocument 供显式回滚的上一文档
     */
    record Published(long revision, String document, String projection, String previousDocument) { }
    /**
     * 与可信 provider 和策略绑定的资源权限。
     * @param owner 可信资源提供者身份
     * @param policy 资源关联的策略身份
     * @param profile 需授权的资源声明
     */
    record Permission(String owner, String policy, PageResourceProfile profile) { }
    /**
     * 发布前后该操作的必需策略及所有引用页面，供管理员确认影响。
     * @param operation 操作身份
     * @param before 当前必需策略
     * @param after 候选必需策略
     * @param pages 引用页面
     */
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
    /**
     * 管理员确认使用的可信绑定摘要；不含配置、字段值或凭据，不用于公开页面投影。
     * @param owner 宿主确认的提供者身份
     * @param hook 回调定义身份
     * @param operation 操作身份
     * @param endpoint 端点身份；空值表示整个操作
     * @param phase 执行时机
     * @param order 执行顺序
     * @param completion 后置回调的结果筛选
     * @param fields 允许读取的字段
     */
    record HookAccess(String owner, String hook, String operation, String endpoint, OperationHook.Phase phase,
            int order, OperationHook.Completion completion, List<OperationBinding.Field> fields) {
        /**
         * 固定字段白名单；只展示来源、路径和回调接收名。
         * @param owner 宿主确认的提供者身份
         * @param hook 回调定义身份
         * @param operation 操作身份
         * @param endpoint 端点身份；空值表示整个操作
         * @param phase 执行时机
         * @param order 执行顺序
         * @param completion 后置回调的结果筛选
         * @param fields 允许读取的字段
         */
        public HookAccess { fields = List.copyOf(fields); }
    }
    /**
     * 本次候选页面的绑定前后状态；历史恢复同样取正式状态与实际候选，不读取未选草稿。
     * @param page 页面身份
     * @param before 当前正式绑定
     * @param after 候选绑定
     */
    record HookChange(String page, List<HookAccess> before, List<HookAccess> after) {
        /**
         * 固定本次确认快照。
         * @param page 页面身份
         * @param before 当前正式绑定
         * @param after 候选绑定
         */
        public HookChange { before = List.copyOf(before); after = List.copyOf(after); }
    }
    /**
     * 指纹固定全部输入和依赖；提交时重新预检，不能以客户端提交的清单代替校验。
     * @param revision 预检依据的正式修订
     * @param fingerprint 全部输入与依赖的指纹
     * @param changes 本次提交的文档
     * @param impacts 操作保护变化
     * @param permissions 必须明确授权的资源权限
     * @param hooks 来自正式状态和候选状态的绑定权限摘要
     */
    record Plan(long revision, String fingerprint, List<Change> changes, List<Impact> impacts, List<Permission> permissions,
            List<HookChange> hooks) {
        /**
         * 固定预检结果。
         * @param revision 预检依据的正式修订
         * @param fingerprint 全部输入与依赖的指纹
         * @param changes 本次提交的文档
         * @param impacts 操作保护变化
         * @param permissions 必须明确授权的资源权限
         * @param hooks 来自正式状态和候选状态的绑定权限摘要
         */
        public Plan {
            changes = List.copyOf(changes); impacts = List.copyOf(impacts);
            permissions = List.copyOf(permissions); hooks = List.copyOf(hooks);
        }
    }
}
