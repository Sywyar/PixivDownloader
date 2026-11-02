# 首次配置

首次配置用于创建 PixivDownloader 本程序的管理员账号，并设置后端访问外部服务时使用的 HTTP 代理。Pixiv 登录 Cookie 在进入下载页后另行保存。

完成[安装与启动](/zh-cn/installation)后，按运行环境选择初始化方式：

| 运行环境 | 初始化入口 | 初始化后操作 |
| --- | --- | --- |
| 本机桌面 | 桌面首次引导，或本机浏览器访问 `http://localhost:6999/setup.html` | 打开下载页并登录 |
| 无桌面服务器（`--no-gui`） | 先运行 `--setup` | 再以 `--no-gui` 启动 |
| Docker | 先运行 `docker compose run --rm app --setup` | 再运行 `docker compose up -d` |

## 桌面或本机浏览器初始化

1. 启动程序并等待后端就绪。若提示缺少必需插件或数据库不可用，先按[插件管理](/zh-cn/plugin-management)修复，重新初始化账号不会解决插件或数据库故障。
2. 设置管理员用户名与密码。密码至少 8 位，建议 12 位以上；使用 8–11 位密码时需二次确认。
3. 按实际网络填写 HTTP 代理主机与端口（如本机代理 `127.0.0.1` 与 `7890`）；可直连目标服务时可关闭代理。
4. 保存后访问 `http://localhost:6999/pixiv-batch.html`，使用刚创建的管理员账号登录。

桌面与浏览器初始化界面固定使用自用模式（`solo`）。`setup.html` 仅允许本机回环访问；远程服务器或容器请使用下方 CLI 初始化。后续修改常规配置请在桌面「设置」页保存，代理与各插件网络路径的适用范围见[网络访问与第三方服务](/zh-cn/network-access)。

## 命令行初始化（服务器 / 无头模式）

进入完整解压后的发行目录，确认同一数据目录没有正在运行的实例。将下方 `APP.jar` 替换为实际主程序 JAR 名称：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --mode=solo
```

按提示输入管理员用户名、密码和代理设置。账号状态写入 `state/setup_config.json`，代理设置写入 `config/config.yaml`。完成后启动无头服务：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --no-gui
```

自动化脚本可一次性传入全部参数（请将密码与代理替换为实际值；命令行参数会出现在 Shell 历史和进程列表中）：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --setup --username=admin --password=REPLACE_WITH_A_UNIQUE_PASSWORD --mode=solo --proxy-enabled=true --proxy-host=127.0.0.1 --proxy-port=7890
```

无需代理时使用 `--proxy-enabled=false`。CLI 仍支持 `--mode=solo` 或 `--mode=multi`；访客只读分享优先使用[进阶参考中的访客邀请](/zh-cn/usage-guide#访客邀请)。

## Docker 初始化

按[Docker 安装说明](/zh-cn/installation#方式三：docker-部署)准备好镜像与挂载目录后，在 `docker-compose.yml` 所在目录执行：

```bash
docker compose run --rm app --setup --mode=solo
docker compose up -d
docker compose logs -f app
```

容器内 `127.0.0.1` 指向容器自身；复用宿主机代理时，将代理主机填写为 `host.docker.internal` 并确认宿主代理允许该连接。未完成 `--setup` 就启动无头服务会以退出码 `78` 退出。

## 确认结果与修改密码

用管理员账号登录下载页，确认工作台正常加载，再按[第一次下载](/zh-cn/first-download)测试下载。若保存或启动失败，保留报错并检查 `log/latest.log`、目录写权限、端口占用与数据库状态，不要靠反复删除初始化文件排障。

停止正在运行的实例后，可在发行目录修改或重置管理员密码：

```text
java -Dfile.encoding=UTF-8 -jar APP.jar --change-password
java -Dfile.encoding=UTF-8 -jar APP.jar --reset-password
```

`--change-password` 需验证原密码，`--reset-password` 用于忘记密码时本机强制重置。执行成功后现有登录会话全部失效，作品文件与数据库不受影响。仅在确实需要重建初始化状态时，才在停止程序并备份后移走 `state/setup_config.json` 重新执行 `--setup`。
