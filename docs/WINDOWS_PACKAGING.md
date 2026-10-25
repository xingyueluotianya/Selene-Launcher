# Selene Launcher Windows 应用打包说明

## 交付形态

Windows x64 交付物为自包含便携包：

```text
Selene Launcher/
├── SeleneLauncher.exe
├── SeleneLauncher.jar   可供启动自检复用的全依赖应用包
├── SeleneLauncher-Diagnostic.cmd
├── lib/                 应用依赖与 Windows JavaFX 原生库
├── runtime/             桌面应用专用 Java 21 JRE
└── README.txt
```

用户应完整解压 ZIP，再运行 `SeleneLauncher.exe`。EXE、`lib/` 和 `runtime/` 必须保持相对位置不变。

桌面运行时与游戏下载的 Java 运行时不同：前者固定随发行包携带，后者按原版元数据要求下载到用户数据目录。

## 构建流程

`windowsPortableZip` 依次执行：

1. 编译 Selene Launcher 并运行全部单元测试。
2. 由 Launch4j 生成 Windows GUI 子系统 EXE，并嵌入应用主 JAR。
3. 下载固定版本 Eclipse Temurin Java 21 Windows x64 JRE。
4. 对 JRE ZIP 执行 SHA-256 校验，失败则删除缓存并终止构建。
5. 组装 EXE、依赖和运行时，输出 `dist/Selene-Launcher-<version>-windows-x64.zip`。

当前桌面运行时：`Eclipse Temurin JRE 21.0.12.1+1 Windows x64`。

## 为什么不是单独一个 EXE

EXE 是桌面入口，JavaFX 和 Java 运行时仍需原生 DLL、模块与许可证。完整目录打包可保持免安装、无需系统 Java且不请求管理员权限，避免每次启动都自解压大量文件。

## 发布前检查

- `mc_build` 与全部单元测试通过。
- EXE 是 Windows GUI PE。
- ZIP 完整性测试无错误。
- 包内存在 `runtime/bin/javaw.exe`、`SeleneLauncher.jar` 和 Windows JavaFX JAR。
- `SeleneLauncher-Diagnostic.cmd` 的无界面启动逻辑自检通过。
- 在干净的 Windows 10/11 x64 机器测试首次启动、中文路径、安装、暂停恢复和游戏启动。
- 正式版使用可信证书签名 EXE 和安装器。

## 当前限制

构建环境无法替代真实 Windows 图形桌面。Beta 已验证编译、测试、文件结构、校验和、PE 格式与 JVM 类路径，仍需 Windows 实机确认显卡驱动、DPI、SmartScreen、中文路径和游戏进程表现。
