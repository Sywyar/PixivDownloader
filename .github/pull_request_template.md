## 变更内容

<!-- 一句话说明本 PR 完成的工作，并补充关键安全或兼容边界。 -->

## 影响范围

- [ ] 变更只包含一个可独立说明的主题
- [ ] 已检查版本化文档、测试和 CHANGELOG 是否需要同步

## 验证

<!-- 在最终提交上从仓库根目录运行。只勾选真实通过的项目；失败或未运行时请保持未勾选并在下方说明。 -->

### 聚焦验证

- [ ] 已运行与本次改动直接相关的测试或检查：`请填写命令`

### Quality Gate 本地等价验证

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

### GitHub 检查

- [ ] 远端分支精确 head SHA 的全部 push workflow 和 check-run 已出现并成功
- [ ] PR merge candidate 的 required checks 已全部成功：`java-tests`、`javascript-tests`、`signature-guard`、`trusted-gate-contract`、`i18n-check`、`check-shared-snippets`

## 补充说明

<!-- 记录未执行项、失败项、已知限制或明确不适用的原因。 -->

## 关联

<!-- 如有：Closes #123 -->
