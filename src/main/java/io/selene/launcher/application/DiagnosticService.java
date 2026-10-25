package io.selene.launcher.application;

import io.selene.launcher.infra.AppLog;
import io.selene.launcher.infra.AppPaths;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Exports a privacy-conscious diagnostic archive for launcher and game failures. */
public final class DiagnosticService {
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("uuuuMMdd-HHmmss")
            .withLocale(Locale.ROOT).withZone(ZoneOffset.UTC);
    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    private static final Set<String> SAFE_CONFIGS = Set.of(
            "settings.json", "download-queue.json", "visual-theme.json", "install-sessions.json"
    );

    private final AppPaths paths;

    public DiagnosticService(AppPaths paths) {
        this.paths = paths;
    }

    public Path export() throws IOException {
        Path diagnostics = paths.root().resolve("diagnostics");
        Files.createDirectories(diagnostics);
        Path target = diagnostics.resolve("selene-diagnostics-" + FILE_TIME.format(Instant.now()) + ".zip");
        try (OutputStream file = Files.newOutputStream(target); ZipOutputStream zip = new ZipOutputStream(file)) {
            writeSystemInfo(zip);
            addTree(zip, paths.logsDirectory(), "launcher-logs", 2);
            addSafeConfigs(zip);
            addInstanceLogs(zip);
        }
        AppLog.info("已导出诊断包：" + target);
        return target;
    }

    private void writeSystemInfo(ZipOutputStream zip) throws IOException {
        String info = """
                Selene Launcher 诊断信息
                generatedAt=%s
                os.name=%s
                os.version=%s
                os.arch=%s
                java.version=%s
                java.vendor=%s
                java.home=%s
                user.language=%s
                launcher.data=%s
                storage.downloads=%s
                storage.instances=%s
                storage.minecraft=%s
                storage.runtimes=%s
                processors=%s
                memory.max=%s
                """.formatted(
                Instant.now(), property("os.name"), property("os.version"), property("os.arch"),
                property("java.version"), property("java.vendor"), property("java.home"),
                property("user.language"), paths.root(), paths.downloadsDirectory(), paths.instancesDirectory(),
                paths.sharedMinecraftDirectory(), paths.runtimesDirectory(), Runtime.getRuntime().availableProcessors(),
                Runtime.getRuntime().maxMemory()
        );
        put(zip, "system-info.txt", info.getBytes(StandardCharsets.UTF_8));
    }

    private void addSafeConfigs(ZipOutputStream zip) throws IOException {
        for (String name : SAFE_CONFIGS) {
            addFile(zip, paths.configDirectory().resolve(name), "config/" + name);
        }
    }

    private void addInstanceLogs(ZipOutputStream zip) throws IOException {
        Path instances = paths.instancesDirectory();
        if (Files.notExists(instances)) return;
        try (var stream = Files.list(instances)) {
            for (Path instance : stream.filter(Files::isDirectory).toList()) {
                String safeName = safeEntry(instance.getFileName().toString());
                addTree(zip, instance.resolve("logs"), "instances/" + safeName + "/logs", 2);
                addFile(zip, instance.resolve("instance.json"), "instances/" + safeName + "/instance.json");
                addFile(zip, instance.resolve("install-state.json"), "instances/" + safeName + "/install-state.json");
            }
        }
    }

    private void addTree(ZipOutputStream zip, Path directory, String prefix, int depth) throws IOException {
        if (Files.notExists(directory)) return;
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(directory, depth)) {
            stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.toString().toLowerCase(Locale.ROOT)))
                    .forEach(files::add);
        }
        for (Path file : files) {
            addFile(zip, file, prefix + "/" + safeEntry(directory.relativize(file).toString()));
        }
    }

    private void addFile(ZipOutputStream zip, Path file, String entry) throws IOException {
        if (!Files.isRegularFile(file)) return;
        long size = Files.size(file);
        if (size > MAX_FILE_BYTES) {
            put(zip, entry + ".omitted.txt", ("文件超过诊断包限制，原始大小：" + size).getBytes(StandardCharsets.UTF_8));
            return;
        }
        put(zip, entry, Files.readAllBytes(file));
    }

    private void put(ZipOutputStream zip, String name, byte[] content) throws IOException {
        ZipEntry entry = new ZipEntry(name.replace('\\', '/'));
        entry.setTime(System.currentTimeMillis());
        zip.putNextEntry(entry);
        zip.write(content);
        zip.closeEntry();
    }

    private String property(String name) {
        return System.getProperty(name, "unknown").replace('\n', ' ').replace('\r', ' ');
    }

    private String safeEntry(String value) {
        return value.replace('\\', '/').replace("../", "_").replace("..\\", "_");
    }
}
