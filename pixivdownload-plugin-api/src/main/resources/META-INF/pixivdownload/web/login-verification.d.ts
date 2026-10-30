/** `login.verification` 普通 UiSlot 的属性；不提供密码、Cookie 或 session。 */
export interface LoginVerificationProperties {
    readonly verificationToken: string;
    /** key 为 LoginVerification.policyId；value 是该实现拥有的有界挑战文本。 */
    readonly challenges: Readonly<Record<string, string>>;
}
/** 只接受当前挑战的 proof 事件；用户再次提交登录后，由后端验证并创建会话。 */
export type LoginVerificationEvents = {
    proof: {verificationToken: string; policyId: string; proof: string};
};
