# Selene Launcher 开发日志

本文记录各阶段已完成工作与验证结果。早期发行名称仅作为版本历史保留。

## 2026-03-28 · Beta 9 加载器下载链路修复

### 实现

- 修正 NeoForge 版本分支过滤：Minecraft `1.21` 仅匹配 `21.0.x`，`1.21.1` 仅匹配 `21.1.x`，避免选中其他补丁版本的安装器。
- Fabric Meta、Fabric Maven、Forge Maven 和 NeoForge Maven 增加官方源与 BMCLAPI 镜像故障转移；“优先镜像源”设置正式接入加载器下载链路。
- Forge/NeoForge 在运行官方 Installer 前，先解析并按 SHA-1 预下载安装清单依赖，降低安装器内部访问单一 Maven 失败的概率。
- Fabric 依赖增加自动重试；下载队列再次加入同目标任务时会更新候选源，旧版保存的单一官方地址不会阻止新镜像策略生效。
- 版本提升至 `0.2.0-beta.9`。

### 验证

- 新增 NeoForge 分支隔离、三种 Loader 官方源/镜像顺序、Installer 清单依赖规划和旧下载任务来源更新回归测试。
- `mc_build`：通过；Java 编译、自动化测试、全依赖 JAR、Launch4j EXE、内置 Java 21 与 Windows 便携 ZIP 均构建成功。

## 2026-03-28 · Beta 6 Windows 启动链路修复

### 实现

- 增加 Java 版本、JavaFX/Jackson 运行依赖、CSS 与图标资源的启动前逻辑自检。
- 启动期异常不再随 GUI 进程静默消失：显示明确错误，并写入 `%APPDATA%/SeleneLauncher/logs/startup-failure.log`。
- 修复旧设置中非法存储路径可在创建界面前触发未处理异常的问题；现在记录警告并安全回退默认路径。
- 便携包新增 `SeleneLauncher-Diagnostic.cmd` 与 `SeleneLauncher.jar`，EXE 无法显示窗口时可独立验证内置 Java、类路径及应用资源并生成检查日志。
- 版本提升至 `0.2.0-beta.6`。

### 验证

- `mc_build`：通过；43 项测试全部成功，JAR、全依赖 JAR、Launch4j EXE 与 Windows 便携 ZIP 均构建成功。
- 成品目录启动逻辑自检通过：`Selene Launcher startup check passed.`。
- Windows ZIP 完整性检查通过：392 个条目，无损坏项，包含 EXE、诊断脚本、全依赖应用 JAR、Java 21 `java.exe`/`javaw.exe`、JVM 与 Windows JavaFX 原生库。
- Windows ZIP SHA-256：`9be536130d017c98b9a13ed1247d1fdd02d0105e6bec190c6ba7f6bf23dffdad`。
- 当前构建环境不能替代 Windows 10/11 图形桌面实机，最终仍需在 Windows 上双击 EXE 验证窗口显示及显卡驱动兼容性。

## 2026-03-28 · Beta 5 实例与游戏管理

### 实现

- 新增独立“实例管理”页面，集中显示原版、Fabric、Forge 与 NeoForge 已安装实例，可选择、重命名、删除和打开游戏目录。
- 新增每实例设置：最小/最大内存、窗口分辨率、全屏、自定义 Java、JVM 参数、游戏参数与快速进入服务器地址。
- 游戏启动接入每实例参数，实时输出到界面并继续保存 `logs/latest.log`；可主动结束游戏进程。
- 新增本地模组管理：多选导入 `.jar`、启用/禁用、删除、状态与大小显示、打开 `mods` 文件夹。
- 导航将“实例管理”与“版本安装”分离，并补齐新增界面的 English / 简体中文文本。
- 版本提升至 `0.2.0-beta.5`。

### 验证

- 新增实例设置、重命名、模组导入/启停/删除与安全删除自动化测试。
- `mc_build`：通过；30 项测试、JAR、全依赖 JAR、Launch4j EXE 与 Windows 便携 ZIP 全部成功。
- Windows ZIP 完整性检查通过：390 个条目，包含 `SeleneLauncher.exe`、Java 21 `runtime/bin/javaw.exe` 与 Windows JavaFX 运行库；SHA-256 为 `ca136309bb410b759d7a0ebe2a822eac1d4f566f66e7eb45ec27a17aed1a72e0`。

## 2026-03-28 · Beta 4 品牌与宣传片

### 实现

- 产品显示名统一为 `Selene Launcher`，Windows 入口统一为 `SeleneLauncher.exe`。
- 版本提升至 `0.2.0-beta.4`，便携包改为 `Selene-Launcher-0.2.0-beta.4-windows-x64.zip`。
- 默认数据目录统一为 `SeleneLauncher` / `selene-launcher`；启动时按顺序迁移旧 `LunaLauncher` 和更早的 `Selene` 数据目录，避免升级丢失配置。
- 界面标题、首次启动向导、日志、诊断包、HTTP User-Agent、游戏启动占位符和 Selene Shader Graph 品牌全部同步。
- 新增约 72 秒 1920×1080 矢量风格宣传片工程：11 个 SVG 关键画面、故事板、可重复渲染脚本、H.264/AAC 成片与 ZIP 源文件包。

### 验证

- `mc_build`：通过；Java 编译、测试、JAR、全依赖 JAR、Launch4j EXE 与 Windows 便携 ZIP 全部成功。
- Windows ZIP 完整性检查通过，确认包含 `SeleneLauncher.exe`、`runtime/bin/javaw.exe` 和 Windows JavaFX 控件库，且不含旧 `LunaLauncher.exe`。
- 宣传片探测结果：72.6 秒、1920×1080、30fps、H.264 + AAC；首屏、资源中心、视觉工坊和结尾画面抽查通过。
- 启动器 ZIP SHA-256：`cb9f41abc41abaf7c9c152df1d9af6d74aa7393276bc6af8bd60d69c6c977dba`。
- 宣传片 ZIP SHA-256：`37ce402de018574c994116ae857a16fe8cdfeaf7b02aefb1548c5654cfb1b96a`。

## 历史摘要

- `0.2.0-beta.3`：自定义下载、实例、Minecraft 公共文件和 Java 运行时目录。
- `0.2.0-beta.2`：Loader 版本点击选择、整合包拖拽导入、资源预览、全局视觉效果和窗口工作区适配。
- `0.2.0-beta.1`：原版与 Loader 安装、下载恢复、账号、资源中心、游戏启动和诊断主链路。
- `0.1.x`：JavaFX 桌面壳层、Windows 便携分发与早期视觉工坊。
