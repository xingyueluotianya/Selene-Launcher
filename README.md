# Selene Launcher

> 面向 Minecraft Java Edition 的现代 Windows 桌面启动器。

Selene Launcher 使用 Java 21 与 JavaFX 构建，覆盖游戏与加载器安装、实例管理、账号、Modrinth 资源、整合包导入、下载恢复、故障诊断和便携式 Windows 分发。
ai支持来源于鱼某人的aimod平台——automods（https://automods.cn）
由于该平台主要以制作mod为目的，所以该项目似乎错误的编译了jar

**当前版本：`0.2.0-beta.9`**

**项目状态：Beta**。主要功能可用，但仍建议先备份重要存档，并在 Windows 10/11 实机充分测试后用于正式环境。

## 功能概览

- **游戏安装**：Mojang 版本目录、离线缓存、原版资源分阶段安装与实例修复。
- **加载器支持**：Fabric、Forge、NeoForge；官方源与镜像源故障转移、版本兼容过滤、哈希校验。
- **下载管理**：持久化队列、暂停/继续/取消、断点续传、失败重试和应用重启恢复。
- **实例管理**：重命名、删除、独立内存/窗口/JVM 参数、游戏日志与本地模组管理。
- **账号支持**：离线档案、Microsoft OAuth 设备码、LittleSkin 与标准 Yggdrasil。
- **资源中心**：按实例筛选 Modrinth 模组、资源包、光影和整合包。
- **整合包导入**：支持 `.mrpack` 与本地 ZIP，包含哈希、路径穿越和解压体积保护。
- **运行时管理**：按游戏元数据自动准备 Java 8/16/17/21/25 等运行时。
- **诊断能力**：轮转日志、安装/下载/启动事件、启动自检和脱敏诊断包。
- **Windows 便携包**：包含 Java 21、JavaFX、EXE 入口和诊断脚本，无需预装 Java。

更完整的实现说明见[架构文档](docs/ARCHITECTURE.md)和[开发日志](docs/DEVELOPMENT_LOG.md)。

## 下载与使用

1. 在 GitHub 仓库右侧 **Releases** 中下载 `Selene-Launcher-0.2.0-beta.9-windows-x64.zip`。
2. 完整解压 ZIP，不要只复制 `SeleneLauncher.exe`。
3. 双击 `SeleneLauncher.exe`。
4. 若窗口无法启动，运行同目录的 `SeleneLauncher-Diagnostic.cmd`，并查看生成的 `SeleneLauncher-startup-check.log` 或 `startup-failure.log`。并在进行排查后交与开发者。

## 基本流程

1. 在“账号中心”添加离线、Microsoft 或 第三方登录 账号。
2. 在“版本安装”选择 Minecraft 版本及原版/Fabric/Forge/NeoForge。
3. 在“下载中心”查看、暂停或恢复安装任务。
4. 在“实例管理”配置游戏、管理模组并启动实例。
5. 在“资源中心”安装 Modrinth 内容，或拖入 `.mrpack`/`.zip`。
6. 遇到问题时从“设置 → 日志与诊断”导出诊断包。

## 数据目录

| 系统 | 默认目录 |
|---|---|
| Windows | `%APPDATA%/SeleneLauncher` |

当前公开交付物以 Windows x64 为主。下载缓存、版本实例、Minecraft 公共文件和托管 Java 均可在首次启动或设置页改到其他磁盘。修改后需重启；启动器不会静默搬运旧文件。

## 从源码构建

### 环境

- JDK 21
- Gradle 8.10 或更高版本
- Git

### 常用任务

```bash
gradle test
gradle build
```

`test` 运行自动化测试；`build` 还会生成普通 JAR、全依赖 JAR、Launch4j Windows EXE，以及包含 Java 21 的 Windows x64 便携 ZIP。最终 ZIP 位于 `dist/`。首次完整构建可能需要下载依赖和固定版本的 Windows Java 运行时。

本仓库同时提供 GitHub Actions 测试工作流；推送或发起 Pull Request 时会在 JDK 21 上执行测试。

## 安全与隐私

- 下载内容在进入实例前校验期望大小及 SHA-1/SHA-256。
- Token 使用本地 AES-GCM 凭据库存储。
- 诊断包明确排除账号文件、凭据密钥和 Token。
- Microsoft 登录需要发行者自行申请客户端 ID，本仓库不包含共享私有凭据。

发现安全问题时，请不要公开提交含漏洞细节、Token、账号文件或完整诊断包的 Issue，按[安全策略](SECURITY.md)私下报告。

## 参与贡献

提交 Issue 或 Pull Request 前请阅读[贡献指南](CONTRIBUTING.md)和[行为准则](CODE_OF_CONDUCT.md)。计划中的功能与限制见[路线图](docs/ROADMAP.md)。

## 许可证

本项目采用 **GNU General Public License v3.0 only（GPL-3.0-only）** 发布。你可以在该许可证条款下使用、研究、修改和再分发本项目；分发修改版本或二进制版本时，请同时履行 GPLv3 对许可证声明及对应源码提供方式的要求。完整条款见 [LICENSE](LICENSE)。

## 免责声明

Selene Launcher 是独立社区项目，与 Mojang Studios、Microsoft、Fabric、Forge、NeoForge 或 Modrinth 无隶属或背书关系。Minecraft 名称及相关资产归其各自权利人所有。使用第三方内容和服务时，请遵守对应条款与许可证。

## 文档

- [架构说明](docs/ARCHITECTURE.md)
- [开发路线图](docs/ROADMAP.md)
- [版本变更记录](CHANGELOG.md)
- [开发日志](docs/DEVELOPMENT_LOG.md)
- [下载源契约](docs/SOURCE_POLICY.md)
- [Windows 打包说明](docs/WINDOWS_PACKAGING.md)
- [GitHub 发布清单](docs/GITHUB_RELEASE.md)
