import type {PageInteraction} from './page-interaction.js';
import type {OperationTarget, OperationPreparation} from './operation-verification.js';
/** 页面组件只传递纯值；业务类型由组件自己的公开契约定义。 */
export type PageMode = 'edit' | 'preview' | 'display';
export interface PageComponentContext<Properties> {
    readonly instanceId: string;
    readonly mode: PageMode;
    readonly properties: Properties;
    readonly signal: AbortSignal;
    /** 仅正式页面的有效绑定提供通道；编辑与预览不提交业务操作。 */
    readonly interaction?: PageInteraction;
    t(key: string, fallback?: string): string;
    /** 传给生成的业务客户端；编辑和预览模式拒绝业务操作。 */
    fetch: typeof globalThis.fetch;
    /** 依赖模块沿用本实例 publication，换代后不会复用旧模块 URL。 */
    importModule(path: string): Promise<Record<string, unknown>>;
    onCleanup(dispose: () => void | Promise<void>): void;
    on(target: EventTarget, event: string, handler: EventListener): void;
    interval(handler: () => void, milliseconds: number): void;
    emit(port: string, value: unknown): void;
    onInput(port: string, handler: (value: unknown) => void | Promise<void>): void;
    /** 属性编辑器提交完整配置快照。 */
    change(properties: Properties): void;
}
export declare function createOperationTarget(operation: string,
    onChange?: (state: {token: string; revision: string; challenges: Record<string, string>; ready: boolean; preparing: boolean}) => void,
    prepareRequest?: (signal: AbortSignal) => Promise<OperationPreparation>): OperationTarget;
/** 页面 owner 提供已声明区域和操作实例；正式修订变化时销毁旧实例并重新导航。 */
export declare function attachPage(options: {
    pageId: string;
    root: HTMLElement;
    regions: Readonly<Record<string, HTMLElement>>;
    targets?: Readonly<Record<string, OperationTarget>>;
    title(value: string): void;
    t(key: string, fallback?: string): string;
    onMounted?(view: Readonly<Record<string, unknown>>): void | Promise<void>;
    /** true 表示当前正式修订明确采用默认布局；false 表示页面不可用，后端保护仍生效。 */
    onFallback?(available: boolean): void | Promise<void>;
}): Promise<{destroy(): Promise<void>}>;
export interface PageComponentInstance<Properties> {
    update?(properties: Properties): void | Promise<void>;
    destroy(): void | Promise<void>;
}
export interface PageComponentModule<Properties> {
    mount(root: HTMLElement, context: PageComponentContext<Properties>):
        PageComponentInstance<Properties> | Promise<PageComponentInstance<Properties>>;
    mountProperties?(root: HTMLElement, context: PageComponentContext<Properties>):
        PageComponentInstance<Properties> | Promise<PageComponentInstance<Properties>>;
}
