import type {PageInteraction, PageInteractionProtocol} from './page-interaction';
/** 表单 owner 发送业务请求；组件只取得实例通道，不接触凭据和完整请求。 */
export interface OperationTarget {
    readonly operation: string;
    configure(revision: string): void;
    challenge(value: {token: string; revision: string; challenges: Readonly<Record<string, string>>}): void;
    request(): {token: string; proofs: Record<string, string>};
    reset(): void;
    connect(binding: {instanceId: string; operation: string; policy: string; protocol: PageInteractionProtocol}, signal?: AbortSignal): PageInteraction;
    destroy(): void;
}
