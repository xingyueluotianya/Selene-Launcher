# GitHub 发布清单

## 首次创建仓库

建议仓库名称使用 `selene-launcher`，简介可填写：

> 面向 Minecraft Java Edition 的现代 Windows 桌面启动器，支持 Fabric、Forge、NeoForge、Modrinth、实例管理和可恢复下载。

建议 Topics：

```text
minecraft minecraft-launcher java javafx fabric forge neoforge modrinth windows
```

创建仓库后建议完成：

- 在 About 中填写简介、网站（如有）和 Topics。
- 启用 Issues、Discussions（需要时）和 Private vulnerability reporting。
- 将默认分支保护为 Pull Request 合并，并要求 `test` 检查通过。
- 在 `build.gradle.kts` 中把 Launch4j 的 `supportUrl` 改为真实仓库地址。
- 确认 GitHub 已识别根目录 `LICENSE` 为 `GPL-3.0-only`。

## 推送前检查

- `git status` 中没有日志、密钥、本地账号配置和构建目录。
- 自动化测试通过。
- README 的当前版本与 `build.gradle.kts` 一致。
- `CHANGELOG.md` 已记录面向用户的重要变化。
- 不上传 `build/`、`dist/`、诊断包或本地 Gradle 缓存到源码分支。
- 不提交 Microsoft 客户端密钥、CurseForge API Key、账号 Token 或 `credential.key`。

## 创建 Release

1. 创建与版本一致的标签，例如 `v0.2.0-beta.9`。
2. Release 标题建议为 `Selene Launcher 0.2.0 Beta 9`。
3. 从 `CHANGELOG.md` 复制该版本的新增、修复和已知限制。
4. 上传完整 Windows x64 便携 ZIP，而不是单独上传 EXE。
5. 附带 ZIP 的 SHA-256。
6. 标记为 Pre-release。

推荐 Release 说明结构：

```markdown
## 主要变化
- ...

## 下载
下载 `Selene-Launcher-0.2.0-beta.9-windows-x64.zip`，完整解压后运行 `SeleneLauncher.exe`。

## 已知限制
- 当前仅重点验证 Windows x64。
- EXE 尚未商业代码签名，可能触发 SmartScreen。
- 仍建议备份重要存档。

## 校验
SHA-256: `<填入实际值>`
```

## 发布后检查

- 在干净 Windows 10/11 x64 环境下载 Release 资产并解压。
- 验证 EXE、诊断脚本、版本安装、镜像切换和游戏启动。
- 检查 Release 下载链接、文件名和 SHA-256。
- 若发现阻断问题，撤下有问题的资产并发布修订版，不覆盖相同版本标签下含义不同的文件。
