/** 页面组件只传递纯值；业务类型由组件自己的公开契约定义。 */
export type PageMode = 'edit' | 'preview' | 'display';
export interface PageComponentContext<Properties> {
    readonly instanceId: string;
    readonly mode: PageMode;
    readonly properties: Properties;
    readonly signal: AbortSignal;
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
