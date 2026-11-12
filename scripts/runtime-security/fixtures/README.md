# 运行时证书信任回归样本

`emudhra/` 保存 [事件公开样本](https://emudhra.we-have-a-problem.com/chains.zip)中的 12 条 PEM 证书链及原始说明。样本捕获于 2026-10-20。测试固定使用当天 12:00 UTC 验证，关闭 AIA 下载与撤销查询，不依赖网络或当前日期。每条样本先检查有效期、签发关系和逐级签名，再验证目标运行时拒绝该链，避免把证书过期或样本损坏当成修复成功。

`emsign-ecc-g3.pem` 是 emSign ECC Root CA - G3 公共根证书，DER 编码的 SHA-256 为 `86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b`。打包工具按该指纹删除所有匹配别名；运行时验收还检查同一公钥是否仍被信任。

`aaa-certificate-services.pem` 是 AAA Certificate Services 公共根证书，用于核对交叉签名样本。其 DER SHA-256 为 `d7a7a0fb5d7e2731d771e9484ebcdef71d5f0c3e0a2948782bc83ee0ea699ef4`，与事件发布的[根证书记录](https://emudhra.we-have-a-problem.com/roots.json)一致。该文件不写入发行运行时。

`emudhra-intermediates.pem` 保存[公开清单](https://emudhra.we-have-a-problem.com/impacted-intermediates.json)中的 16 张中间证书，对应 8 个泄露密钥及其交叉签名版本。测试从证书计算 SHA-256，独立检查打包工具的禁用名单。打包时将这些指纹合并到随包运行时的 `lib/security/blocked.certs`，保留原有禁用项和 AAA 根；已有文件格式异常或算法不支持时拒绝打包。构建无需下载名单，证书样本不进入发行包。

`positive-root.pem` 和 `positive-leaf.pem` 是独立生成的测试 CA 与 localhost 服务器证书，用于确认清理前后均能建立正常信任链。已修复的 JDK 可能通过内置禁用列表拒绝问题中间证书，因此对照不要求它重新接受问题链。此目录不含私钥，测试 CA 不写入发行运行时。

在 Windows 上使用 JDK 25 或更高版本编译探针，并指定待验运行时：

```powershell
./scripts/runtime-security/test-runtime-trust.ps1 -RuntimeHome ./build/runtime
```

默认依次在两个独立 Java 进程中执行以下检查，均使用指定运行时：

- `Cleanup`：临时信任库只含目标 emSign 根和独立正常根，检查精确删除、无关根保留、重复别名、正常链、幂等及损坏输入；另用临时文件验证禁用名单的创建、合并、原有内容保留、幂等及异常输入拒绝。不修改目标运行时。
- `Runtime`：读取目标运行时的完整 `cacerts`，检查目标根公钥缺席、全部问题链被拒绝及 16 张中间证书的禁用记录。仍受信任的链会连同其实际信任根一起报告，包括 AAA 交叉签名路径。此检查在新进程中运行，验证实际 Java 是否执行随包禁用策略。

排查时可以用 `-Check Cleanup` 或 `-Check Runtime` 单独运行一项。清理测试通过只证明删除逻辑正确；打包和发行产物验收始终使用默认的完整检查，任一项失败都会阻断。
