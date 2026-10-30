/** 普通 Web 槽位实例。业务属性、事件及 region 名称由页面的独立契约约定。 */
export interface UiSlotContext<Properties, Events extends Record<string, unknown>> {
    readonly slotId: string;
    readonly target: string;
    readonly publication: string;
    readonly properties: Readonly<Properties>;
    readonly signal: AbortSignal;
    readonly i18n?: {
        t(key: string, fallback?: string, values?: Record<string, unknown>): string;
        apply(root: Element): void;
    };
    region(name: string): HTMLElement | null;
    emit<Name extends keyof Events>(name: Name, value: Events[Name]): unknown | Promise<unknown>;
    /** 来源 publication 必须仍可接纳；目标路由继续独立鉴权。 */
    fetch: typeof globalThis.fetch;
    loadScript(path: string): Promise<boolean>;
    loadStyle(path: string): void;
    onCleanup(dispose: () => void | Promise<void>): void;
    on(target: EventTarget, event: string, handler: EventListener): void;
    interval(handler: () => void, milliseconds: number): void;
}
export interface UiSlotInstance<Properties> {
    update?(properties: Readonly<Properties>): void | Promise<void>;
    destroy?(): void | Promise<void>;
}
export interface UiSlotConnection<Properties> {
    refresh(): Promise<readonly UiSlotDescription[]>;
    update(properties: Properties): Promise<void>;
    destroy(): Promise<void>;
}
export interface UiSlotDescription {
    readonly slotId: string;
    readonly target: string;
    readonly moduleUrl: string | null;
    readonly publication: string;
}
export interface UiSlotOptions<Properties, Events extends Record<string, unknown>> {
    root?: Document | HTMLElement;
    targetPrefix?: string;
    /** 页面公开清单必须仍由服务端限定可达贡献；缺省为通用受保护清单。 */
    manifestUrl?: string;
    properties?: Properties;
    regions?: Readonly<Record<string, HTMLElement>>;
    i18n?: () => UiSlotContext<Properties, Events>['i18n'];
    onEvent?: <Name extends keyof Events>(name: Name, value: Events[Name]) => unknown | Promise<unknown>;
    onError?: (error: Error) => void;
}
export interface UiSlotRuntime {
    /** 在 moduleUrl 脚本求值时注册；模块本身不执行挂载。 */
    registerModule<Properties, Events extends Record<string, unknown>>(moduleUrl: string,
        mount: (root: HTMLElement, context: UiSlotContext<Properties, Events>) =>
            UiSlotInstance<Properties> | Promise<UiSlotInstance<Properties>>): void;
    /** 每个连接拥有自己的挂载、属性和清理；同一 publication 的模块加载可共享。 */
    connect<Properties, Events extends Record<string, unknown>>(options?: UiSlotOptions<Properties, Events>): UiSlotConnection<Properties>;
    /** 按页面 targetPrefix 复用一个连接；pagehide 后释放。 */
    bootstrap<Properties, Events extends Record<string, unknown>>(options?: UiSlotOptions<Properties, Events>): Promise<readonly UiSlotDescription[]>;
    update<Properties>(targetPrefix: string, properties: Properties): Promise<void> | undefined;
}
declare global { interface Window { PixivUiSlots: UiSlotRuntime; } const PixivUiSlots: UiSlotRuntime; }
