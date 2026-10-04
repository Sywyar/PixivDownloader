## 变更内容

<!-- 说明具体问题及修改后的行为，必要时补充安全或兼容边界。 -->

## 影响范围

- [ ] 变更范围与 PR 描述一致，无无关改动
- [ ] 已检查版本化文档、测试和 CHANGELOG 是否需要同步

## 验证

<!-- 在最终提交上从仓库根目录运行。只勾选真实通过的项目；失败或未运行时请保持未勾选并在下方说明。 -->

### 聚焦验证

- [ ] 已运行与本次改动直接相关的测试或检查：`请填写命令`

### 常用本地验证

<!-- 按改动范围记录实际执行结果；未运行或不适用的项目在补充说明中注明。本清单不替代 GitHub required checks。 -->

- [ ] `git diff --check origin/master...HEAD`
- [ ] `mvn -B -ntp -pl pixivdownload-official-plugins -am compile '-Dexec.skip=true'`
- [ ] `mvn -B -ntp test '-Dexec.skip=true' '-Duser.language=en' '-Duser.country=US'`
- [ ] `npm ci`
- [ ] `npm run test:js`
- [ ] `npm run test:web-standards`
- [ ] `npm run test:i18n`
- [ ] `npm run i18n:check`
- [ ] `npm run i18n:generate-static`
- [ ] `git diff --exit-code -- pixivdownload-app/src/main/resources/static/i18n-static`
- [ ] `npm run gate:parity`
- [ ] `npm run gate:signature`
- [ ] `pwsh -NoProfile -File ./scripts/sync-shared-snippets.ps1 -Check`

### 条件验证

- [ ] 未修改 Gate、workflow 或 Ruleset；如有修改，`npm run doctor:github-gate` 已通过
- [ ] 未修改官方外置插件；如有修改，已运行对应插件的显式开发 profile 验证
- [ ] 未改变构建或分发产物；如有修改，已运行对应打包与产物边界验证
- [ ] 未修改界面；如有修改，已在实际页面检查受影响的布局、状态、键盘操作及适用的主题、窄窗口和放大文字

<!-- Compose 桌面界面验证可附“关于 → 平台与构建信息 → 复制信息”的完整结果。
保留插件信息的二级列表；复制时无需先展开插件清单。截图用于补充可见效果。 -->

### GitHub 检查

- [ ] 已核对本地 HEAD、远端分支与当前 PR 的 head SHA 一致
- [ ] 当前 PR 测试合并对象的 required checks 已全部成功，并已核对指定 App 的检查来源：`java-tests`、`javascript-tests`、`signature-guard`、`trusted-gate-contract`、`i18n-check`、`check-shared-snippets`

## 补充说明

<!-- 记录未执行项、失败项、已知限制或明确不适用的原因。 -->

## 关联

<!-- 如有：关联 #123。Issue 在对应修复或功能实际发布后关闭，关联时不要使用自动关闭关键字。 -->
