/** 实例只能接收自己的状态并响应一次；状态为 null 时须撤销请求、计时器和 Worker。 */
export interface PageInteraction<State = string, Response = string> {
    /** 让表单 owner 准备挑战；同一表单复用本次挑战，组件不取得表单数据或请求地址。 */
    prepare(): Promise<void>;
    /** 当前挑战失效时清空所属表单的证明，旧挑战不能清空新状态。 */
    invalidate(id: string): boolean;
    onState(listener: (state: {readonly id: string; readonly revision: string; readonly value: State} | null) => void): () => void;
    respond(id: string, value: Response): boolean;
    destroy(): void;
}
export interface PageInteractionProtocol {
    readonly id: string;
    readonly version: number;
    readonly stateSchema: string;
    readonly responseSchema: string;
}
export interface PageParticipant {
    readonly policy: string;
    readonly protocol: PageInteractionProtocol;
}
