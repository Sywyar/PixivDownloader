import type {PageInteraction, PageInteractionProtocol} from './page-interaction';
/** 服务端准备结果；空 token 和空 challenges 表示本次无需交互证明。 */
export interface OperationPreparation {
    readonly token: string;
    readonly revision: string;
    readonly challenges: Readonly<Record<string, string>>;
}
/** 表单 owner 发送业务请求；组件只取得实例通道，不接触凭据和完整请求。 */
export interface OperationTarget {
    readonly operation: string;
    configure(revision: string): void;
    challenge(value: OperationPreparation): void;
    /** 只调用 owner 的准备入口；并发合并，输入重置或销毁后拒绝迟到结果。 */
    prepare(): Promise<void>;
    /** 本地证明已齐备；正式提交仍须服务端校验。 */
    ready(): boolean;
    /** 尚未准备或证明不齐时拒绝取得提交数据。 */
    request(): {token: string; proofs: Record<string, string>};
    reset(): void;
    connect(binding: {instanceId: string; operation: string; policy: string; protocol: PageInteractionProtocol}, signal?: AbortSignal): PageInteraction;
    destroy(): void;
}
