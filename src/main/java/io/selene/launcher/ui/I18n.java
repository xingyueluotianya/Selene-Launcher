package io.selene.launcher.ui;

import io.selene.launcher.core.model.AppLanguage;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Labeled;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextInputControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** Lightweight runtime localization for the JavaFX interface. */
public final class I18n {
    private static final Map<String, String> ZH_TO_EN = translations();
    private static final List<Map.Entry<String, String>> ZH_PHRASES = sortedEntries(ZH_TO_EN, true);
    private static final List<Map.Entry<String, String>> EN_PHRASES = sortedEntries(ZH_TO_EN, false);
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();
    private static volatile AppLanguage language = AppLanguage.ENGLISH;

    private I18n() {
    }

    public static AppLanguage language() {
        return language;
    }

    public static void setLanguage(String localeTag) {
        setLanguage(AppLanguage.fromTag(localeTag));
    }

    public static void setLanguage(AppLanguage next) {
        AppLanguage normalized = Objects.requireNonNull(next, "next");
        if (language == normalized) return;
        language = normalized;
        LISTENERS.forEach(Runnable::run);
    }

    public static AutoCloseable onChange(Runnable listener) {
        LISTENERS.add(Objects.requireNonNull(listener, "listener"));
        return () -> LISTENERS.remove(listener);
    }

    public static String text(String value) {
        if (value == null || value.isBlank()) return value;
        String exact = language == AppLanguage.ENGLISH ? ZH_TO_EN.get(value) : reverseExact(value);
        if (exact != null) return exact;
        String translated = value;
        List<Map.Entry<String, String>> phrases = language == AppLanguage.ENGLISH ? ZH_PHRASES : EN_PHRASES;
        for (Map.Entry<String, String> entry : phrases) {
            String source = language == AppLanguage.ENGLISH ? entry.getKey() : entry.getValue();
            String target = language == AppLanguage.ENGLISH ? entry.getValue() : entry.getKey();
            if (!source.isBlank() && translated.contains(source)) translated = translated.replace(source, target);
        }
        return translated;
    }

    public static void localize(Node node) {
        if (node == null) return;
        if (node instanceof Labeled labeled) {
            labeled.setText(text(labeled.getText()));
            if (node.getProperties().putIfAbsent("selene.i18n.text", Boolean.TRUE) == null) {
                labeled.textProperty().addListener((observable, before, after) -> {
                    String translated = text(after);
                    if (!Objects.equals(after, translated)) labeled.setText(translated);
                });
            }
        }
        if (node instanceof TextInputControl input) {
            input.setPromptText(text(input.getPromptText()));
            if (node.getProperties().putIfAbsent("selene.i18n.prompt", Boolean.TRUE) == null) {
                input.promptTextProperty().addListener((observable, before, after) -> {
                    String translated = text(after);
                    if (!Objects.equals(after, translated)) input.setPromptText(translated);
                });
            }
        }
        if (node instanceof TabPane tabs) {
            for (Tab tab : tabs.getTabs()) {
                tab.setText(text(tab.getText()));
                if (tab.getProperties().putIfAbsent("selene.i18n.text", Boolean.TRUE) == null) {
                    tab.textProperty().addListener((observable, before, after) -> {
                        String translated = text(after);
                        if (!Objects.equals(after, translated)) tab.setText(translated);
                    });
                }
                localize(tab.getContent());
            }
        }
        if (node instanceof Parent parent) parent.getChildrenUnmodifiable().forEach(I18n::localize);
    }

    private static String reverseExact(String value) {
        for (Map.Entry<String, String> entry : ZH_TO_EN.entrySet()) {
            if (entry.getValue().equals(value)) return entry.getKey();
        }
        return null;
    }

    private static List<Map.Entry<String, String>> sortedEntries(Map<String, String> source, boolean chineseLength) {
        List<Map.Entry<String, String>> entries = new ArrayList<>(source.entrySet());
        entries.sort(Comparator.comparingInt((Map.Entry<String, String> entry) ->
                chineseLength ? entry.getKey().length() : entry.getValue().length()).reversed());
        return List.copyOf(entries);
    }

    private static Map<String, String> translations() {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("Selene Launcher 无法完成操作", "Selene Launcher could not complete the operation");
        values.put("Selene Launcher 无法初始化存储目录", "Selene Launcher could not initialize storage");
        values.put("概览", "Home");
        values.put("游戏库", "Game Library");
        values.put("资源中心", "Resource Center");
        values.put("服务器", "Servers");
        values.put("下载", "Downloads");
        values.put("视觉工坊", "Visual Studio");
        values.put("设置", "Settings");
        values.put("账号中心", "Account Center");
        values.put("下载中心", "Download Center");
        values.put("服务正常", "Services Online");
        values.put("下一站，月海。你好，旅行者。", "Next stop, the lunar sea. Welcome, traveler.");
        values.put("Selene Launcher 将轻量体验、完整功能与可塑视觉融合在一起。", "Selene Launcher brings together a lightweight experience, complete features, and expressive visuals.");
        values.put("选择并安装你的第一个游戏实例", "Choose and install your first game instance");
        values.put("Selene Launcher 会规划客户端、依赖库、资源文件与启动参数，并执行完整性校验。", "Selene Launcher plans the client, libraries, assets, and launch arguments, then verifies their integrity.");
        values.put("安装游戏", "Install Game");
        values.put("发现模组与整合包", "Discover mods and modpacks");
        values.put("快速开服", "Quick Server");
        values.put("创建或接管服务器", "Create or manage a server");
        values.put("迁移实例", "Migrate Instance");
        values.put("导入已有游戏目录", "Import an existing game directory");
        values.put("本地服与远程服务器管理", "Manage local and remote servers");
        values.put("此模块将在后续阶段接入真实数据。", "Live data for this module will be added in a later release.");
        values.put("调整下载策略与大型文件位置；版本、公共资源和 Java 均可放到其他磁盘。", "Configure downloads and large-file locations. Instances, shared assets, and Java can live on another drive.");
        values.put("界面语言", "Interface Language");
        values.put("语言切换会立即生效并随设置保存", "Language changes apply immediately and are saved with your settings");
        values.put("下载策略", "Download Policy");
        values.put("并发下载数（建议 4）", "Concurrent downloads (4 recommended)");
        values.put("并发下载数", "Concurrent downloads");
        values.put("网络不稳定时优先尝试镜像源", "Prefer mirrors on unstable networks");
        values.put("启动时播放开场动效", "Play intro animation at startup");
        values.put("存储位置", "Storage Locations");
        values.put("运行环境", "Runtime Environment");
        values.put("桌面应用固定携带 Java 21。游戏运行时按版本元数据独立管理：支持 Java 8、16、17、21、25，以及官方元数据声明并可从 Temurin 获取的其他主版本；JRE 不可用时自动回退 JDK，不修改系统环境变量。", "The desktop app bundles Java 21. Game runtimes are managed independently from version metadata, supporting Java 8, 16, 17, 21, 25, and other Temurin majors declared by official metadata. It falls back to a JDK when no JRE is available and never changes system environment variables.");
        values.put("日志与诊断", "Logs & Diagnostics");
        values.put("启动器日志会自动轮转保存。遇到安装或启动失败时，可导出诊断包后发送给开发者；账号令牌不会打包。", "Launcher logs rotate automatically. If installation or launch fails, export a diagnostics bundle for support; account tokens are never included.");
        values.put("导出诊断包", "Export Diagnostics");
        values.put("保存设置", "Save Settings");
        values.put("已保存；路径修改将在重启 Selene Launcher 后生效", "Saved. Storage path changes take effect after restarting Selene Launcher.");
        values.put("保存失败", "Save failed");
        values.put("导出失败", "Export failed");
        values.put("大型文件可放到 D 盘或其他磁盘。修改后需重启启动器；不会自动移动旧目录中的实例。", "Large files can be stored on drive D: or another disk. Restart after changing paths; existing instances are not moved automatically.");
        values.put("下载与缓存", "Downloads & Cache");
        values.put("整合包、加载器工具等下载文件", "Downloaded modpacks, loader tools, and other files");
        values.put("版本实例", "Game Instances");
        values.put("每个游戏版本的 mods、存档、日志和配置", "Mods, saves, logs, and configuration for each instance");
        values.put("公共游戏文件", "Shared Game Files");
        values.put("跨实例共享的 versions、libraries 和 assets", "Versions, libraries, and assets shared across instances");
        values.put("Java 运行时", "Java Runtimes");
        values.put("为不同 Minecraft 版本自动下载的 Java", "Java runtimes downloaded for different Minecraft versions");
        values.put("请选择文件夹", "Choose a folder");
        values.put("浏览…", "Browse…");
        values.put("请选择登录方式", "Choose a sign-in method");
        values.put("支持离线档案、Microsoft 正版设备码登录，以及 LittleSkin 等标准 Yggdrasil 外置登录服务。", "Supports offline profiles, Microsoft device-code sign-in, and standard Yggdrasil providers such as LittleSkin.");
        values.put("离线档案", "Offline Profile");
        values.put("Microsoft 正版", "Microsoft Account");
        values.put("外置登录", "External Login");
        values.put("使用离线档案", "Use Offline Profile");
        values.put("离线档案已启用", "Offline profile enabled");
        values.put("离线档案不会向 Mojang 验证所有权，适合单人游戏和关闭正版验证的服务器。", "Offline profiles do not verify ownership with Mojang and are intended for single-player or offline-mode servers.");
        values.put("档案名", "Profile Name");
        values.put("尚未申请设备码", "No device code requested");
        values.put("获取设备码并登录", "Get Device Code & Sign In");
        values.put("正在申请 Microsoft 设备码…", "Requesting a Microsoft device code…");
        values.put("请在浏览器完成授权，Selene Launcher 正在等待…", "Complete authorization in your browser. Selene Launcher is waiting…");
        values.put("正版登录使用 Microsoft OAuth 设备码流程。你需要为自己的发行版申请允许设备码流程的客户端 ID；Selene Launcher 不会借用其他启动器的身份。", "Microsoft sign-in uses the OAuth device-code flow. Your distribution needs its own client ID with device-code support; Selene Launcher never borrows another launcher's identity.");
        values.put("客户端 ID", "Client ID");
        values.put("邮箱或用户名", "Email or username");
        values.put("密码", "Password");
        values.put("登录外置服务器", "Sign In to Provider");
        values.put("预置 LittleSkin，也可填写其他兼容标准 Yggdrasil API 的 HTTPS 地址。账号密码仅用于本次认证，不会写入配置。", "LittleSkin is preconfigured. You can enter another HTTPS endpoint compatible with the standard Yggdrasil API. Credentials are used only for this authentication and are never saved.");
        values.put("API 地址", "API URL");
        values.put("账号", "Account");
        values.put("当前账号：", "Current account: ");
        values.put("下载队列为空", "Download queue is empty");
        values.put("共", "Total");
        values.put("项", "items");
        values.put("活动", "Active");
        values.put("暂停", "Paused");
        values.put("个版本", "versions");
        values.put("个实例", "instances");
        values.put("个兼容项目", "compatible projects");
        values.put("个文件", "files");
        values.put("安装文件统一进入持久化队列；暂停会保留断点，继续时自动发起 Range 请求。", "Installation files use a persistent queue. Pausing keeps progress, and resuming automatically uses range requests.");
        values.put("清理已结束任务", "Clear Finished Tasks");
        values.put("还没有下载任务，安装游戏后会在这里显示", "No downloads yet. New game installations will appear here.");
        values.put("继续", "Resume");
        values.put("暂停", "Pause");
        values.put("取消", "Cancel");
        values.put("等待中", "Queued");
        values.put("正在连接", "Connecting");
        values.put("下载中", "Downloading");
        values.put("正在校验", "Verifying");
        values.put("已暂停", "Paused");
        values.put("已完成", "Completed");
        values.put("失败", "Failed");
        values.put("已取消", "Cancelled");
        values.put("尚未同步版本清单", "Version catalog has not been synced");
        values.put("正在准备 Mojang 官方版本源…", "Preparing the official Mojang version source…");
        values.put("选择版本后可安装原版游戏", "Select a version to install vanilla Minecraft");
        values.put("刷新清单", "Refresh Catalog");
        values.put("安装所选版本", "Install Selected Version");
        values.put("启动游戏", "Launch Game");
        values.put("查看下载队列", "View Download Queue");
        values.put("选择加载器后自动读取版本", "Select a loader to fetch available versions");
        values.put("刷新 Loader", "Refresh Loader");
        values.put("浏览 Mojang 官方版本，安装经过校验的原版实例，并使用离线档案启动。", "Browse official Mojang versions, install verified instances, and launch with your selected account.");
        values.put("搜索版本，例如 1.21.5", "Search versions, e.g. 1.21.5");
        values.put("全部版本", "All Versions");
        values.put("正式版", "Release");
        values.put("快照版", "Snapshot");
        values.put("历史版本", "Historical");
        values.put("没有符合条件的版本", "No matching versions");
        values.put("原版", "Vanilla");
        values.put("点击选择 Loader 版本", "Choose a loader version");
        values.put("启动账号", "Launch Account");
        values.put("Loader 状态", "Loader Status");
        values.put("正在连接 Mojang 官方版本清单…", "Connecting to the official Mojang version catalog…");
        values.put("同步失败，可稍后重试", "Sync failed. Try again later.");
        values.put("正在创建安装计划…", "Creating the installation plan…");
        values.put("安装未完成，可在下载中心继续失败或暂停的任务", "Installation did not finish. Resume failed or paused tasks in Downloads.");
        values.put("最新正式版", "Latest release");
        values.put("最新快照", "Latest snapshot");
        values.put("Mojang 官方网络数据", "Official Mojang network data");
        values.put("本地缓存（网络暂不可用）", "Local cache (network unavailable)");
        values.put("原版无需 Loader", "Vanilla does not require a loader");
        values.put("请先选择 Minecraft 版本", "Select a Minecraft version first");
        values.put("版本目录读取失败，可点击刷新", "Could not load the version catalog. Click refresh to retry.");
        values.put("可选", "Available");
        values.put("已安装", "Installed");
        values.put("选择实例后可按兼容版本搜索和安装", "Select an instance to search for compatible projects");
        values.put("按实例版本与加载器搜索兼容资源；查看中文分类、预览和热度，或直接拖入 ZIP / .mrpack 安装。", "Search compatible projects by instance version and loader, view categories, previews and popularity, or drop a ZIP / .mrpack to install.");
        values.put("选择已安装实例", "Select an installed instance");
        values.put("搜索中文名、英文名、作者或关键词", "Search by name, author, or keyword");
        values.put("搜索兼容资源", "Search Compatible Projects");
        values.put("输入关键词搜索，或把 ZIP / .mrpack 拖到上方导入区", "Enter a keyword, or drop a ZIP / .mrpack into the import area above");
        values.put("选择 ZIP / .mrpack", "Choose ZIP / .mrpack");
        values.put("拖入本地整合包即可安装", "Drop a local modpack to install");
        values.put("支持 .mrpack、Modrinth ZIP 和包含完整本地文件的普通 ZIP；将导入到当前选择的实例", "Supports .mrpack, Modrinth ZIP, and regular ZIP archives with complete local files; imports into the selected instance.");
        values.put("请先在游戏库安装并选择一个实例", "Install and select an instance in Game Library first");
        values.put("正在按实例兼容性搜索 Modrinth…", "Searching Modrinth for compatible projects…");
        values.put("搜索失败", "Search failed");
        values.put("安装整合包", "Install Modpack");
        values.put("安装到实例", "Install to Instance");
        values.put("作者", "Author");
        values.put("中文分类", "Categories");
        values.put("兼容", "Compatible");
        values.put("客户端", "Client");
        values.put("服务端", "Server");
        values.put("必需", "Required");
        values.put("可选", "Optional");
        values.put("不支持", "Unsupported");
        values.put("未知", "Unknown");
        values.put("次下载", "downloads");
        values.put("人关注", "followers");
        values.put("更新", "Updated");
        values.put("整合包", "Modpack");
        values.put("资源包", "Resource Pack");
        values.put("光影", "Shader");
        values.put("模组", "Mod");
        values.put("图形化调整通用效果节点，实时生成可移植的 GLSL 风格程序；安全运行层会映射到 JavaFX。", "Adjust universal effect nodes visually and generate a portable GLSL-style program in real time, safely mapped to JavaFX.");
        values.put("预设输入", "Preset Input");
        values.put("光晕节点", "Glow Node");
        values.put("流速节点", "Flow Node");
        values.put("星尘节点", "Stardust Node");
        values.put("玻璃节点", "Glass Node");
        values.put("强调色", "Accent Color");
        values.put("编译效果图", "Compile Preview");
        values.put("保存并应用", "Save & Apply");
        values.put("生成的 Selene Universal Shader Language", "Generated Selene Universal Shader Language");
        values.put("月海极光", "Lunar Aurora");
        values.put("星云脉冲", "Nebula Pulse");
        values.put("日蚀矩阵", "Eclipse Matrix");
        values.put("深海霓虹", "Abyss Neon");
        values.put("欢迎使用 Selene Launcher", "Welcome to Selene Launcher");
        values.put("先确认基础偏好，之后可随时在设置中修改", "Confirm a few basics first. You can change them later in Settings.");
        values.put("进入 Selene Launcher", "Enter Selene Launcher");
        values.put("启动器配置和日志（体积很小）将保存在：", "Launcher configuration and logs (very small) will be stored in:");
        values.put("大型文件保存位置", "Large-file locations");
        values.put("Selene Launcher 会自行管理游戏所需 Java；不会修改系统 Java 或环境变量。", "Selene Launcher manages the Java runtimes needed by the game and never changes system Java or environment variables.");
        values.put("晚上好，旅行者", "GOOD EVENING, TRAVELER");
        values.put("游戏资料库", "GAME LIBRARY");
        values.put("传输控制", "TRANSFER CONTROL");
        values.put("身份与会话", "IDENTITY & SESSION");
        values.put("偏好设置", "PREFERENCES");
        values.put("MODRINTH 生态", "MODRINTH ECOSYSTEM");
        values.put("SELENE 视觉节点", "SELENE SHADER GRAPH");
        values.put("实时安全预览", "LIVE GPU-SAFE PREVIEW");
        values.put("JAVA 版", "JAVA EDITION");
        values.put("实例管理", "Instances");
        values.put("版本安装", "Install Versions");
        values.put("选择已安装游戏，调整独立启动参数，查看实时日志，并管理该实例的本地模组。", "Select an installed game, tune its launch options, view live logs, and manage local mods.");
        values.put("选择实例后可以启动、设置或管理模组", "Select an instance to launch it, configure it, or manage mods");
        values.put("尚未选择实例", "No instance selected");
        values.put("在左侧选择已安装游戏，或前往版本安装页面创建实例", "Select an installed game on the left, or create one from Install Versions");
        values.put("已安装游戏", "Installed Games");
        values.put("还没有实例，请先安装游戏", "No instances yet. Install a game first.");
        values.put("刷新实例", "Refresh Instances");
        values.put("安装新游戏", "Install New Game");
        values.put("结束游戏", "Stop Game");
        values.put("打开游戏文件夹", "Open Game Folder");
        values.put("游戏设置", "Game Settings");
        values.put("模组管理", "Mod Management");
        values.put("启动日志", "Launch Log");
        values.put("实例显示名称", "Instance display name");
        values.put("实例名称", "Instance Name");
        values.put("内存范围（MiB）", "Memory Range (MiB)");
        values.put("窗口分辨率", "Window Resolution");
        values.put("全屏启动", "Launch in Fullscreen");
        values.put("自定义 Java", "Custom Java");
        values.put("留空则自动下载并选择兼容 Java", "Leave blank to download and select compatible Java automatically");
        values.put("额外 JVM 参数", "Extra JVM Arguments");
        values.put("额外游戏参数", "Extra Game Arguments");
        values.put("快速进入服务器", "Quick Join Server");
        values.put("保存实例设置", "Save Instance Settings");
        values.put("删除实例", "Delete Instance");
        values.put("每个实例独立保存内存、窗口、自定义 Java 与附加参数；公共游戏文件不会因删除实例而移除。", "Memory, window, custom Java, and extra arguments are stored per instance. Deleting an instance does not remove shared game files.");
        values.put("导入 .jar 模组后可随时启用、禁用或删除。禁用只会重命名文件，不会破坏模组内容。", "Import .jar mods, then enable, disable, or delete them at any time. Disabling only renames the file and never changes its contents.");
        values.put("该实例还没有本地模组", "This instance has no local mods");
        values.put("导入 .jar 模组", "Import .jar Mods");
        values.put("启用 / 禁用", "Enable / Disable");
        values.put("删除模组", "Delete Mod");
        values.put("打开 mods 文件夹", "Open Mods Folder");
        values.put("清空显示", "Clear View");
        values.put("打开日志文件", "Open Log File");
        values.put("实例设置已保存", "Instance settings saved");
        values.put("游戏已退出", "Game exited");
        values.put("实例已删除", "Instance deleted");
        values.put("已启用", "Enabled");
        values.put("已禁用", "Disabled");
        values.put("删除模组", "Delete Mod");
        values.put("确定永久删除模组文件？", "Permanently delete this mod file?");
        values.put("选择模组文件", "Choose Mod Files");
        values.put("该实例还没有启动日志。", "This instance does not have a launch log yet.");
        values.put("安装失败自动诊断", "Installation Failure Diagnosis");
        values.put("游戏退出自动诊断", "Game Exit Diagnosis");
        values.put("模组错误自动修复", "Automatic Mod Repair");
        values.put("启动失败后可扫描缺失依赖和错误版本", "After a failed launch, scan for missing dependencies and incorrect mod versions");
        values.put("扫描模组问题", "Scan Mod Problems");
        values.put("一键修复所选", "Repair Selected");
        values.put("一键下载依赖", "Download Dependency");
        values.put("一键替换版本", "Replace Version");
        values.put("尚未扫描，或日志中没有模组依赖问题", "Not scanned yet, or no mod dependency problems were found in the log");
        values.put("缺失依赖", "Missing Dependency");
        values.put("版本错误", "Incorrect Version");
        values.put("未找到", "Not Found");
        values.put("没有找到可用模组", "No compatible mod found");
        values.put("正在分析启动日志并查询兼容模组…", "Analyzing the launch log and searching for compatible mods…");
        values.put("日志中没有识别到缺失依赖或模组版本错误", "No missing dependency or incorrect mod version was identified in the log");
        values.put("自动修复失败", "Automatic repair failed");
        values.put("游戏已正常退出", "Game exited normally");
        return Map.copyOf(values);
    }
}
