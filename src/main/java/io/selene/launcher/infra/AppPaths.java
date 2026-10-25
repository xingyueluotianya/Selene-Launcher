package io.selene.launcher.infra;

import io.selene.launcher.core.model.LauncherSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Small launcher state stays in the system profile; large game data can live on any writable drive. */
public record AppPaths(Path root, Path downloads, Path instances, Path sharedMinecraft, Path runtimes) {
    public AppPaths(Path root) {
        this(root, root.resolve("downloads"), root.resolve("instances"), root.resolve("minecraft"), root.resolve("runtimes"));
    }

    public AppPaths {
        root = normalize(root);
        downloads = normalize(downloads);
        instances = normalize(instances);
        sharedMinecraft = normalize(sharedMinecraft);
        runtimes = normalize(runtimes);
    }

    public static AppPaths systemDefault() {
        Path root = systemRoot();
        return new AppPaths(root);
    }

    public static AppPaths fromSettings(Path root, LauncherSettings settings) {
        AppPaths defaults = new AppPaths(root);
        return new AppPaths(
                root,
                configured(settings.downloadsDirectory(), defaults.downloadsDirectory()),
                configured(settings.instancesDirectory(), defaults.instancesDirectory()),
                configured(settings.sharedMinecraftDirectory(), defaults.sharedMinecraftDirectory()),
                configured(settings.runtimesDirectory(), defaults.runtimesDirectory())
        );
    }

    private static Path systemRoot() {
        String os = System.getProperty("os.name", "unknown").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        Path root;
        Path previousRoot;
        Path oldestRoot;
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path base = appData == null || appData.isBlank() ? home.resolve("AppData/Roaming") : Path.of(appData);
            root = base.resolve("SeleneLauncher");
            previousRoot = base.resolve("LunaLauncher");
            oldestRoot = base.resolve("Selene");
        } else if (os.contains("mac")) {
            Path base = home.resolve("Library/Application Support");
            root = base.resolve("SeleneLauncher");
            previousRoot = base.resolve("LunaLauncher");
            oldestRoot = base.resolve("Selene");
        } else {
            Path base = home.resolve(".local/share");
            root = base.resolve("selene-launcher");
            previousRoot = base.resolve("luna-launcher");
            oldestRoot = base.resolve("selene");
        }
        migrateLegacy(previousRoot, root);
        migrateLegacy(oldestRoot, root);
        return root;
    }

    private static void migrateLegacy(Path legacy, Path target) {
        if (Files.exists(target) || Files.notExists(legacy)) return;
        try {
            Files.createDirectories(target.getParent());
            Files.move(legacy, target);
        } catch (IOException exception) {
            AppLog.warning("无法自动迁移旧版数据目录", exception);
        }
    }

    public void prepareStorage() throws IOException {
        Map<String, Path> paths = new LinkedHashMap<>();
        paths.put("下载目录", downloadsDirectory());
        paths.put("版本实例目录", instancesDirectory());
        paths.put("Minecraft 公共目录", sharedMinecraftDirectory());
        paths.put("Java 运行时目录", runtimesDirectory());
        for (Map.Entry<String, Path> item : paths.entrySet()) ensureWritable(item.getKey(), item.getValue());
    }

    private void ensureWritable(String name, Path directory) throws IOException {
        try {
            Files.createDirectories(directory);
            Path probe = Files.createTempFile(directory, ".selene-write-test-", ".tmp");
            Files.deleteIfExists(probe);
        } catch (IOException | SecurityException failure) {
            throw new IOException(name + "不可写：" + directory, failure);
        }
    }

    public Path configDirectory() {
        return root.resolve("config");
    }

    public Path downloadsDirectory() {
        return downloads;
    }

    public Path instancesDirectory() {
        return instances;
    }

    public Path sharedMinecraftDirectory() {
        return sharedMinecraft;
    }

    public Path runtimesDirectory() {
        return runtimes;
    }

    public Path toolsDirectory() {
        return downloads.resolve("launcher-tools");
    }

    public Path cacheDirectory() {
        return root.resolve("cache");
    }

    public Path logsDirectory() {
        return root.resolve("logs");
    }

    private static Path configured(String value, Path fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Path.of(value);
        } catch (InvalidPathException failure) {
            AppLog.warning("设置中存在无效存储路径，将使用默认目录：" + value, failure);
            return fallback;
        }
    }

    private static Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }
}
