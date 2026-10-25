package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Imports Modrinth packs and safe local ZIP packs into an existing instance. */
public final class ModpackImportService {
    private static final long MAX_INDEX_BYTES = 16L * 1024 * 1024;
    private static final long MAX_EXTRACTED_BYTES = 2L * 1024 * 1024 * 1024;
    private static final int MAX_ENTRIES = 100_000;

    private final DownloadQueueService queue;
    private final ObjectMapper mapper = new ObjectMapper();

    public ModpackImportService(DownloadQueueService queue) {
        this.queue = queue;
    }

    public CompletableFuture<Integer> importArchive(Path archive, GameInstance instance, Consumer<String> status) {
        return CompletableFuture.supplyAsync(() -> {
            try (ZipFile zip = new ZipFile(archive.toFile())) {
                if (zip.getEntry("modrinth.index.json") != null) return importModrinth(zip, archive, instance, status);
                if (zip.getEntry("manifest.json") != null) return importLocalZip(zip, archive, instance, status, true);
                return importLocalZip(zip, archive, instance, status, false);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<Integer> importMrpack(Path archive, GameInstance instance, Consumer<String> status) {
        return importArchive(archive, instance, status);
    }

    public boolean accepts(Path path) {
        if (path == null || !Files.isRegularFile(path)) return false;
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".mrpack") || name.endsWith(".zip");
    }

    private int importModrinth(
            ZipFile zip, Path archive, GameInstance instance, Consumer<String> status
    ) throws IOException {
        ZipEntry indexEntry = zip.getEntry("modrinth.index.json");
        if (indexEntry == null || indexEntry.getSize() > MAX_INDEX_BYTES) {
            throw new IOException("整合包缺少有效的 modrinth.index.json");
        }
        JsonNode index;
        try (InputStream input = zip.getInputStream(indexEntry)) {
            index = mapper.readTree(input);
        }
        validateCompatibility(index, instance);
        Path game = instance.directory().resolve("game");
        Files.createDirectories(game);
        List<PlannedFile> planned = plan(index.path("files"), game);
        List<String> ids = queue.enqueueAll(planned.stream()
                .map(file -> new DownloadQueueService.EnqueueRequest("整合包文件 " + file.relative(), file.request())).toList());
        for (int indexNumber = 0; indexNumber < ids.size(); indexNumber++) {
            status.accept("正在下载整合包文件 " + (indexNumber + 1) + "/" + ids.size());
            try {
                queue.completion(ids.get(indexNumber)).join();
            } catch (CompletionException failure) {
                throw new IOException("整合包文件下载失败：" + planned.get(indexNumber).relative(), failure.getCause());
            }
        }
        status.accept("正在应用整合包覆盖文件");
        int overrides = extractPrefix(zip, game, "overrides/") + extractPrefix(zip, game, "client-overrides/");
        AppLog.info("Modrinth 整合包导入完成：" + archive + " -> " + instance.id());
        return planned.size() + overrides;
    }

    private int importLocalZip(
            ZipFile zip, Path archive, GameInstance instance, Consumer<String> status, boolean curseForgeManifest
    ) throws IOException {
        Path game = instance.directory().resolve("game");
        Files.createDirectories(game);
        status.accept("正在安全解压本地整合包…");
        int extracted;
        if (curseForgeManifest) {
            JsonNode manifest;
            try (InputStream input = zip.getInputStream(zip.getEntry("manifest.json"))) {
                manifest = mapper.readTree(input);
            }
            if (manifest.path("files").isArray() && !manifest.path("files").isEmpty()) {
                throw new IOException("该 CurseForge ZIP 包含需通过 CurseForge API 解析的远程文件；请导出包含完整 overrides 的本地包，或改用 .mrpack");
            }
            String overrides = manifest.path("overrides").asText("overrides");
            extracted = extractPrefix(zip, game, normalizePrefix(overrides));
        } else {
            extracted = extractGeneric(zip, game);
        }
        if (extracted == 0) throw new IOException("ZIP 中没有可导入的客户端文件");
        AppLog.info("本地 ZIP 整合包导入完成：" + archive + " -> " + instance.id() + "，文件 " + extracted);
        return extracted;
    }

    private void validateCompatibility(JsonNode index, GameInstance instance) throws IOException {
        String requiredGame = index.path("dependencies").path("minecraft").asText("");
        if (!requiredGame.isBlank()) {
            JsonNode metadata = mapper.readTree(instance.directory().resolve("instance.json").toFile());
            String actual = metadata.path("baseGameVersion").asText(instance.versionId());
            if (!requiredGame.equals(actual)) {
                throw new IOException("整合包需要 Minecraft " + requiredGame + "，当前实例是 " + actual);
            }
        }
    }

    private List<PlannedFile> plan(JsonNode files, Path game) throws IOException {
        if (!files.isArray()) throw new IOException("整合包索引缺少 files");
        List<PlannedFile> result = new ArrayList<>();
        for (JsonNode file : files) {
            String environment = file.path("env").path("client").asText("required");
            if (environment.equals("unsupported")) continue;
            String relative = file.path("path").asText("");
            Path target = safeTarget(game, relative);
            List<URI> downloads = new ArrayList<>();
            file.path("downloads").forEach(url -> downloads.add(URI.create(url.asText())));
            if (downloads.isEmpty()) throw new IOException("整合包文件没有下载地址：" + relative);
            String sha1 = file.path("hashes").path("sha1").asText("");
            if (sha1.length() != 40) throw new IOException("整合包文件缺少 SHA-1：" + relative);
            result.add(new PlannedFile(relative, new DownloadService.DownloadRequest(
                    downloads, target, sha1, DownloadService.ChecksumAlgorithm.SHA_1,
                    file.path("fileSize").asLong(-1)
            )));
        }
        return result;
    }

    private int extractPrefix(ZipFile zip, Path game, String prefix) throws IOException {
        int extracted = 0;
        long total = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory() || !entry.getName().startsWith(prefix)) continue;
            String relative = entry.getName().substring(prefix.length());
            if (relative.isBlank()) continue;
            total = extractEntry(zip, entry, safeTarget(game, relative), total);
            if (++extracted > MAX_ENTRIES) throw new IOException("整合包文件数量超过安全限制");
        }
        return extracted;
    }

    private int extractGeneric(ZipFile zip, Path game) throws IOException {
        int extracted = 0;
        long total = 0;
        var entries = zip.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (entry.isDirectory()) continue;
            String relative = stripGamePrefix(entry.getName());
            if (relative.isBlank() || ignoredArchiveMetadata(relative)) continue;
            total = extractEntry(zip, entry, safeTarget(game, relative), total);
            if (++extracted > MAX_ENTRIES) throw new IOException("整合包文件数量超过安全限制");
        }
        return extracted;
    }

    private long extractEntry(ZipFile zip, ZipEntry entry, Path output, long total) throws IOException {
        long announced = Math.max(0, entry.getSize());
        if (total + announced > MAX_EXTRACTED_BYTES) throw new IOException("整合包解压总大小超过安全限制");
        Files.createDirectories(output.getParent());
        long copied;
        try (InputStream input = zip.getInputStream(entry)) {
            copied = Files.copy(input, output, StandardCopyOption.REPLACE_EXISTING);
        }
        total += copied;
        if (total > MAX_EXTRACTED_BYTES) {
            Files.deleteIfExists(output);
            throw new IOException("整合包解压总大小超过安全限制");
        }
        return total;
    }

    private Path safeTarget(Path game, String relative) throws IOException {
        String clean = relative.replace('\\', '/');
        if (clean.startsWith("/") || clean.matches("^[A-Za-z]:.*")) throw new IOException("整合包包含绝对路径：" + relative);
        Path target = game.resolve(clean).normalize();
        if (clean.isBlank() || !target.startsWith(game.normalize())) throw new IOException("整合包包含不安全路径：" + relative);
        return target;
    }

    private String stripGamePrefix(String name) {
        String value = name.replace('\\', '/');
        for (String prefix : List.of(".minecraft/", "minecraft/", "game/", "overrides/", "client-overrides/")) {
            if (value.startsWith(prefix)) return value.substring(prefix.length());
        }
        return value;
    }

    private boolean ignoredArchiveMetadata(String relative) {
        String lower = relative.toLowerCase(java.util.Locale.ROOT);
        return lower.equals("manifest.json") || lower.equals("modrinth.index.json")
                || lower.startsWith("meta-inf/") || lower.startsWith("__macosx/") || lower.endsWith(".ds_store")
                || lower.equals("instance.json") || lower.equals("install-state.json");
    }

    private String normalizePrefix(String prefix) {
        String value = prefix == null ? "" : prefix.replace('\\', '/');
        while (value.startsWith("/")) value = value.substring(1);
        return value.endsWith("/") ? value : value + "/";
    }

    private record PlannedFile(String relative, DownloadService.DownloadRequest request) {
    }
}
