# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog EN-us](https://keepachangelog.com/en/1.1.0/).

该格式基于 [Keep a Changelog ZH-cn](https://keepachangelog.com/zh-CN/1.1.0/).

## [v1.6.1] - 2026-04-28

### Features
- 收藏夹新增自定义下载目录，支持 `{collection_name}` 模板变量与相对/绝对路径，批量下载指定收藏夹时按该目录保存
- 启动期数据库结构检查按差异类型分流：仅回填工具支持的列（author_id / R18 / is_ai / description）会自动回填，其他差异（新增表/列/索引等）仅提示不阻塞启动
- 批量下载新增自定义文件名格式，提供 11 个模板变量（作品 ID/标题/作者/时间戳/页码/AI/R18 分级等），前端即时预览与去重，后端二次验证
- 批量下载队列展示 Ugoira 动图三阶段精细化进度（ZIP 下载 / 帧解压 / ffmpeg 转换）、图片下载字节进度，以及下载后收藏与收藏夹操作结果彩色标注
- 收藏夹编辑界面对自定义下载目录提供实时预览，按名称即时展开模板变量并区分相对/绝对路径

### Refactoring
- GUI 托盘菜单与状态面板从单一入口拆分为批量下载、下载监控、本地画廊三个独立入口
- 内置 FFmpeg 文件从应用根目录迁移至 tools/ffmpeg/ 子目录

## [v1.6.0] - 2026-04-28

### Features
- 新增中英双语多语言基础能力，后端支持按 `?lang`、`pixiv_lang` Cookie、`Accept-Language` 与 `app.language` 解析语言，并将接口异常、参数校验、下载流程、SSE 与日志文案接入本地化资源
- Web 页面与 userscript 接入多语言切换，覆盖批量下载、图库、作品详情、监控页和脚本文案，支持离线 fallback、语言切换组件、aria-label/placeholder/title 翻译以及跨脚本语言同步
- 桌面 GUI 适配多语言运行时，状态、配置、工具、关于、托盘菜单和维护工具文案改为中英双语资源
- 批量下载设置新增收藏夹选择器，solo 模式与 multi 管理员可将下载完成的作品自动加入指定收藏夹，普通用户传入的收藏夹目标会被清理以避免越权写入
- 工具面板新增 JSON 到 SQLite 迁移入口，并完善数据回填与文件夹巡检流程
- userscript 新增 PromptGuard，跨脚本和 All-in-One 模块去重首次启动提示与未登录弹窗，避免重复弹窗和重复打开登录页

### Performance
- 新增聚合下载 SSE 端点，批量下载页、监控页和 userscript 改为共享单条 SSE 连接，并按用户 UUID 隔离事件，修复高并发批量下载受浏览器连接上限影响而卡死或超时的问题
- 优化前端多语言渲染观察器，避免翻译文本变更触发无效回调和整页重复扫描

### Bug Fixes
- 修复 i18n 改造后 `IllegalArgumentException` 未统一映射导致部分接口错误返回 500 的问题
- 修复多语言改造遗留的部分异常映射、英文字段文案和页面动态文案覆盖遗漏

### Tests
- 适配本地化消息依赖后的单元测试，并补充 SSE、配置生成、下载收藏夹写入和迁移相关回归覆盖

## [v1.5.3] - 2026-04-22

### Features
- 作品详情页支持收起已展开的多图区域，灯箱打开时会自动并行加载全部图片；userscript 后端不可用提示补充远程部署的 `@connect` 配置说明
- 作品详情页的标签和作者信息可直接跳转到图库并自动预填筛选条件
- 作品详情页新增 Pixiv 原作品链接与作者主页外链，并优化作者信息区布局
- 图库标签与作者新增“必须有 / 不能有 / 或者有”三态筛选，README.md 与 README_en.md 同步补充说明
- 图库新增标签与作者筛选摘要栏，支持一键清除，并在作者视图中调整筛选时自动切回全部作品视图

### Bug Fixes
- 详情页跳转图库时改为优先携带 `tagId`，应用一次性导航筛选后自动清理 URL 参数，避免标签命中错误或刷新后重复套用
- 修正图库跨维度组合筛选逻辑，将“必须标签 + 可选作者”与“必须作者 + 可选标签”分别计算；重置筛选时同步清空标签与作者搜索框，并补充回归测试

### Documentation
- 新增 CHANGELOG.md，按 Keep a Changelog 结构整理版本变更

## [v1.5.2] - 2026-04-21

### Features
- 新增画廊作者视图与筛选功能，优化前端缩略图加载

## [v1.5.1] - 2026-04-21

### Bug Fixes
- 测试文件未跟随变动导致构建失败

### Refactoring
- 简化 GuiLauncher 启动后端流程

### Features
- 搜索代理请求限流与图库补充翻页
- 新增数据库 Schema 检查与 GuiLauncher 启动流程重构
- 改进 Tools 面板回填工具的用户体验
- 新增图库标签筛选功能
- 将 isR18 布尔字段迁移为支持 R-18G 的 xRestrict 整数字段

### Documentation
- 更新 README.md 和 README_en.md 新增画廊介绍

## [v1.5.0] - 2026-04-20

### Bug Fixes
- 修复滚动时筛选弹窗与页码面板错位

### Features
- 支持在作品页抓取当前作品和相关作品
- 新增收藏夹的图库与作品详情页
- 将多合一脚本生成并入 Maven 构建流程
- 集成工具面板并托管后端生命周期

### Documentation
- 重写中英文 README 以匹配 GUI 与新版分发流程

### Bug Fixes
- 支持 timed-delete 清理已移动作品目录

## [v1.4.7] - 2026-04-20

### Bug Fixes
- 固定多合一脚本使用 document-end 启动

### Features
- 在发布流程中产出多合一 userscript 并补充安装说明
- 生成并内置 Pixiv All-in-One 多合一脚本
- 将托管安装位置切换到软件目录并优先定位程序目录
- 注入发布版本号并在 GUI 展示应用版本
- 支持桌面端单实例启动并唤醒已有窗口
- 持久化作品描述与标签并补全历史元数据

## [v1.4.6] - 2026-04-19

### Miscellaneous
- 重命名 Pixiv 批量导入作品下载器.user.js 为 Pixiv URL 批量导入作品下载器.user.js

### Features
- 实现 Pixiv 作者信息持久化、监控页作者筛选排序与作者回填工具
- 优化 monitor.html 的轮询机制
- 为下载历史增加实际目录校验
- 优化批量导入流程并调整 Windows 安装包为管理员安装
- 支持管理员打包已完成作品并完善多人模式鉴权

### Bug Fixes
- 修复未公开的 index 导致错误重定向至 login
- 管理运行时文件目录并支持旧位置自动迁移

## [v1.4.5] - 2026-04-17

### Build
- 生成多语言 MSI 并拆分 FFmpeg 变体
- 支持按需安装 FFmpeg 并重构 Windows 打包产物

### Bug Fixes
- 安全传递 WiX define 参数

## [v1.4.4] - 2026-04-17

### Bug Fixes
- 配置文件缺失的配置项没有自动补齐

### Build
- 删除 packaging/wix 目录

### Documentation
- 更新 README.md 和 README_en.md

## [v1.4.3] - 2026-04-16

### Bug Fixes
- 修复配置 HTTPS 后的硬编码导致的错误
- 修复 windows-installer 中的 jar 版本号永远为 0.0.1
- SSL 证书无法通过 GUI 配置

### Improvements
- 通过 GuiLauncher 启动的程序将记录近 5 次的日志到 log 和 log/html 文件下

## [v1.4.2] - 2026-04-15

### Build
- 回滚到使用单语言 msi 程序

### Features
- 新增油猴脚本在线分发及 Windows 多语言 MSI 打包

## [v1.4.1] - 2026-04-15

### Features
- 新增 --intro 启动参数支持及首页 canvas 检测跳转

## [v1.4.0] - 2026-04-15

### Bug Fixes
- 修复 GUI 图标加载可靠性、托盘菜单中文渲染及配置联动问题

### Features
- 新增 GUI 桌面管家：
  - 添加 GuiLauncher 入口，支持 GUI / headless 双模式
  - 添加系统托盘 + 状态/配置/关于 三标签页
  - ConfigFileEditor 支持行内编辑 config.yaml，保留注释和格式
  - ConfigFieldRegistry 作为配置字段唯一事实源
  - 添加 GuiStatusController 提供 /api/gui/status 和 /api/gui/restart 接口

### Build
- 尝试使用 Jpackage 打包项目
- 尝试使用 GraalVM Native Image 打包项目

## [v1.3.2] - 2026-04-13

### Bug Fixes
- 修复 setup.html 可访问范围太宽

## [v1.3.1] - 2026-04-13

### Features
- 新增 SSL 配置

## [v1.3.0] - 2026-04-13

### Features
- 新增产品介绍页 intro.html / intro-canary.html
- 下载队列新增删除按钮和跳转原链接按钮

## [v1.2.0] - 2026-04-09

### Features
- 登录限速防护 + Ugoira 服务解耦 + 监控页自适应轮询：
  - 新增 LoginRateLimitService：基于 IP 的分钟级滑动窗口，防止暴力破解登录端点
  - SetupProperties 绑定 setup.login-rate-limit-minute（默认 10，0 = 不限制）
  - SetupController 在登录前调用 isAllowed()，超限返回 429
  - AppConfigGenerator 生成配置模板中增加对应注释项
  - 将 Ugoira 处理逻辑从 DownloadService 抽离至独立的 UgoiraService
  - PixivBookmarkService 用 BookmarkRequest record 替换 Map<String, Object>
  - monitor.html 活跃下载同步改为自适应 setTimeout 轮询
    - 有活跃下载或完成后 10s 内：1s 间隔；否则 8s；后台标签 30s

## [v1.1.0] - 2026-04-07

### Miscellaneous
- 重命名 Pixiv 作品图片下载器(本地浏览器下载).user.js 为 Pixiv 作品图片下载器(Local download).user.js
- 重命名 Pixiv 作品图片下载器.user.js 为 Pixiv 作品图片下载器(本地浏览器下载).user.js

### Bug Fixes
- 修复 release 中的脚本的中文名称不兼容
- 修复 unknown flag: --clobber
- 修复 ReleaseAsset.name already exists
- 修复 v1.1.0 tag 已存在但 workflow 仍尝试创建的问题
- 修复 workflow 创建 release 前先清理残留，避免 asset name 冲突
- 修复 workflow 无法正常工作

### Features
- 新增当前页面的批量下载脚本，可以抓取当前页面的所有作品下载

## [v1.0.9] - 2026-04-06

### Bug Fixes
- 修复搜索模式无法搜索中文的问题

## [v1.0.8] - 2026-04-06

### Features
- pixiv-batch.html 新增搜索模式

### Bug Fixes
- 修复图片分类工具加载 webp 大图时报错
- 修复图片分类工具切换文件夹空目录的问题

## [v1.0.7] - 2026-04-02

### Bug Fixes
- 优化图片分类工具回滚机制
- 修复因疏忽导致图片分类工具无法正确获取标题和作品年龄限制

## [v1.0.6] - 2026-04-02

### Features
- 下载时可以勾选收藏作品，后端将使用 cookie 信息通过 Pixiv 的收藏 API 自动收藏

## [v1.0.5] - 2026-04-01

### Features
- 多人模式下新增图片限额，当一个作品的总图片超出图片限额的数量后将按多个配额计算

## [v1.0.4] - 2026-04-01

### Features
- 多人模式下的速率限制，可在配置文件中更改具体限制，配置文件向下兼容，自动补充新的配置文件内容

### Miscellaneous
- 重构项目

## [v1.0.3] - 2026-03-29

### Bug Fixes
- 修复错误

## [v1.0.2] - 2026-03-27

### Bug Fixes
- 错误提交了一个不是必须的依赖选项
- 修复 bug
- 规范化代码

## [v1.0.1] - 2026-03-26

### Features
- 新增开源协议说明

### Bug Fixes
- 修复了一些安全问题

## [v1.0.0] - 2026-03-26

Initial release.
