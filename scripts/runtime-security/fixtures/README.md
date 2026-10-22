# 运行时证书信任回归样本

`emudhra/` 保存 [事件公开样本](https://emudhra.we-have-a-problem.com/chains.zip)中的 12 条 PEM 证书链及原始说明。样本捕获于 2026-10-20。测试固定使用当天 12:00 UTC 验证，关闭 AIA 下载与撤销查询，不依赖网络或当前日期；修复前的对照必须接受其中 6 条 emSign 链，避免把证书过期或夹具损坏当成修复成功。

`emsign-ecc-g3.pem` 是 emSign ECC Root CA - G3 公共根证书，DER 编码的 SHA-256 为 `86a1ecba089c4a8d3bbe2734c612ba341d813e043cf9e8a862cd5c57a36bbe6b`。打包工具按该指纹删除所有匹配别名；运行时验收还检查同一公钥是否仍被信任。

`positive-root.pem` 和 `positive-leaf.pem` 是独立生成的测试 CA 与 localhost 服务器证书，仅用于确认移除操作保留正常证书链。此目录不含私钥，测试 CA 不写入发行运行时。

在 Windows 上使用 JDK 17 或更高版本编译探针，并指定待验运行时：

```powershell
./scripts/runtime-security/test-runtime-trust.ps1 -RuntimeHome ./build/runtime
```

检查使用指定运行时的 Java 执行。任何问题链仍可验证、信任库损坏、正常对照失败或工具退出非零，都会阻断打包和发行产物验收。
