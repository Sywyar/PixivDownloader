/** 实例只能接收自己的状态并响应一次；状态为 null 时须撤销请求、计时器和 Worker。 */
export interface PageInteraction<State = string, Response = string> {
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
