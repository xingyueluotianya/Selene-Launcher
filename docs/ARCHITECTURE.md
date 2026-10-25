# Selene Launcher 架构说明

## 技术基线

- Java 21、JavaFX 21、Gradle Kotlin DSL。
- Jackson：设置、队列、安装状态、实例、账号元数据与主题 JSON。
- JUnit 5 + AssertJ：领域、网络、持久化、凭据与诊断测试。

## 分层

```text
io.selene.launcher
├── ui                 JavaFX 页面与交互适配
├── application        下载、安装、认证、资源、诊断和进程用例
├── core
│   ├── model          不依赖框架的领域数据
│   └── port           下载、设置、运行时等边界
└── infra              网络、文件、日志、凭据和 Provider 实现
```

依赖方向保持为 `ui/infra -> application -> core`。UI 不直接请求 Mojang、Fabric、Modrinth 或 Adoptium API。

## 数据与日志

默认根目录为 Windows `%APPDATA%/SeleneLauncher`、macOS `~/Library/Application Support/SeleneLauncher`、Linux `~/.local/share/selene-launcher`；首次使用新品牌版本时会尝试迁移旧 `LunaLauncher` 目录。

- `config/`：设置、队列、主题、账号非敏感元数据及本地凭据密钥。
- `logs/`：启动器轮转日志。
- `diagnostics/`：用户主动导出的诊断 ZIP。
- 可自定义下载目录：在线整合包、加载器 Installer 与 authlib-injector。
- 可自定义实例目录：实例、Natives、游戏目录、实例游戏日志和安装状态。
- 可自定义 Minecraft 公共目录：跨实例共享的 Versions、Libraries 与 Assets。
- 可自定义运行时目录：按 Java 主版本托管的游戏运行时。

配置与日志体积小并固定保留在系统配置目录，保证外置磁盘离线时仍能定位设置与错误；四类大型目录由 `AppPaths.fromSettings` 解析，首次启动和设置保存时执行创建及可写探测。路径修改在重启后统一注入安装、资源、Java 和启动链路，不在后台静默搬运已有数据。

`AppLog` 使用 4 MiB × 5 轮转文件，覆盖启动、下载、安装、启动和未捕获异常。`DiagnosticService` 只打包安全配置、启动器日志、实例日志和系统摘要，明确排除 `accounts.json`、`credential.key` 与 Token。

## 下载和安装

`HttpDownloadService` 负责 HTTPS、`.part`、Range、候选源、大小及 SHA-1/SHA-256 校验和原子发布。`DownloadQueueService` 负责持久化状态机、暂停/恢复/取消、重启恢复、同目标任务复用和观察者通知。

`VanillaGameService` 把安装拆为：

1. 版本详情。
2. 客户端、Libraries、Natives 和日志配置。
3. Asset Index。
4. 每批 256 个 Asset Object。
5. Natives 安全解压与实例提交。

每个阶段原子更新 `install-state.json`；单文件失败自动重试两次。重复执行安装会复用已验证目标与队列任务，因此也是实例修复流程。失败时状态文件保留阶段、进度和错误摘要。

## 加载器

- `FabricLoaderService` 使用 Fabric Meta 的游戏版本 Loader 目录和 Profile JSON，把 Fabric Libraries 和 Arguments 合并进原版元数据并复用原版 Assets/Natives。
- `InstallerLoaderService` 从 Forge/NeoForge 官方 Maven 元数据筛选与目标游戏匹配的 Loader 版本，对 Installer 的 `.sha1` 和 JAR 做校验，使用目标实例所需 Java 执行官方 `--installClient`，再把生成的继承型 Profile 归一化为可直接启动的 Selene 实例。
- 每个加载器实例保存 `baseGameVersion` 与 `loader`，资源中心据此选择兼容版本。

## 账号

`AccountService` 支持：

- 离线 UUID。
- Microsoft OAuth 设备码 → Xbox Live → XSTS → Minecraft Services 所有权与角色链路。
- 标准 Yggdrasil `authenticate`，预置 LittleSkin。

`LocalCredentialVault` 使用随机 256 位密钥与 AES-GCM 密封 Token；密钥文件在 POSIX 上限制为仅当前用户读写，在 Windows 上继承应用数据目录 ACL。Yggdrasil 启动由 `AuthlibInjectorManager` 获取官方发布元数据、校验 SHA-256 并加入 `-javaagent`。

Microsoft 客户端 ID 由发行者自行申请，工程不借用其他启动器身份，也不硬编码第三方私有凭据。

## 资源中心与整合包

`ModrinthService` 使用 Modrinth v2 按实例游戏版本/加载器搜索兼容项目，返回图标、分类、版本、环境、热度和更新时间等预览字段，下载主文件并验证 SHA-1。目标目录按项目类型映射到 `mods/`、`resourcepacks/` 或 `shaderpacks/`；在线 `.mrpack` 可直接进入导入链路。

`ModpackImportService` 自动识别 `.mrpack`、Modrinth ZIP、无远程依赖的 CurseForge ZIP 与普通本地整合包 ZIP。Modrinth 包执行客户端环境、目标游戏版本、SHA-1、目标路径和覆盖文件校验；本地 ZIP 执行 Zip Slip、绝对路径、文件数量和解压总量防护。需要 CurseForge 私有 API 解析远程文件的包会明确拒绝，不硬编码共享 API Key。

## 游戏进程

`GameProcessService` 解析 Mojang JVM/Game Arguments、OS Rules、Classpath、日志配置和占位符；加载器缺失 `downloads.artifact` 时从 Maven 坐标推导类路径。进程输出写入实例 `logs/latest.log`，启动器日志只记录实例、账号类型和 Java 路径，不记录 Token 或完整命令行。

## 视觉系统

视觉工坊运行受限参数图，不执行任意用户 GPU 代码。预览 Canvas 不再参与父容器首选尺寸计算，高宽设置明确上下限；页面隐藏后停止预览计时器。`GlobalVisualLayer` 使用同一主题参数绘制整个应用的光晕、流光和星尘背景，让预设在概览、游戏库、资源中心和设置等场景持续生效。

## Windows 分发

Launch4j 生成 GUI 子系统 `SeleneLauncher.exe`。构建优先复用上一版已经验证的 Java 21 Runtime，首次构建没有旧包时才下载固定 URL 并核验固定 SHA-256。便携 ZIP 包含 EXE、Windows JavaFX、依赖和相对 `runtime/`。启动窗口使用 Windows 可视工作区尺寸，避开任务栏，并只在启动后的短时间内置顶以取得前景焦点。正式公开发行仍需要商业代码签名和 Windows 实机冒烟测试。
