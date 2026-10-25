package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.GameVersion;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.infra.AppLog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Installs and repairs Mojang-compatible client metadata, libraries, assets and natives. */
public final class VanillaGameService {
    private static final URI ASSET_BASE = URI.create("https://resources.download.minecraft.net/");
    private static final int ASSET_BATCH_SIZE = 256;
    private static final int AUTOMATIC_RETRIES = 2;

    private final DownloadQueueService queue;
    private final Path instancesDirectory;
    private final Path sharedDirectory;
    private final ObjectMapper objectMapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public VanillaGameService(DownloadQueueService queue, Path instancesDirectory, Path sharedDirectory) {
        this.queue = queue;
        this.instancesDirectory = instancesDirectory;
        this.sharedDirectory = sharedDirectory;
    }

    public CompletableFuture<GameInstance> install(GameVersion version, Consumer<String> statusListener) {
        Consumer<String> status = statusListener == null ? ignored -> { } : statusListener;
        return CompletableFuture.supplyAsync(() -> {
            Path stateFile = instancesDirectory.resolve(safeSegment(version.id())).resolve("install-state.json");
            try {
                writeState(stateFile, "METADATA", "正在获取版本清单", 0, 0, "");
                status.accept("正在获取 " + version.id() + " 安装清单");
                Path metadata = sharedDirectory.resolve("versions").resolve(version.id()).resolve(version.id() + ".json");
                awaitWithRetry(enqueue("版本清单 " + version.id(), request(
                        version.metadataUri(), metadata, version.sha1(), DownloadService.ChecksumAlgorithm.SHA_1, -1
                )), "版本清单", status);
                JsonNode root = objectMapper.readTree(metadata.toFile());
                if (root.hasNonNull("inheritsFrom")) {
                    throw new IOException("该版本依赖父版本 " + root.path("inheritsFrom").asText()
                            + "，请通过加载器安装入口创建，或先安装对应原版");
                }
                return installResolved(version, root, metadata, stateFile, status);
            } catch (Exception failure) {
                Throwable cause = unwrap(failure);
                AppLog.error("安装失败：" + version.id(), cause);
                try {
                    writeState(stateFile, "FAILED", "安装失败", 0, 0, message(cause));
                } catch (IOException stateFailure) {
                    AppLog.warning("无法保存安装失败状态", stateFailure);
                }
                throw new CompletionException(cause);
            }
        });
    }

    public CompletableFuture<GameInstance> repair(GameVersion version, Consumer<String> statusListener) {
        return install(version, statusListener);
    }

    public boolean isInstalled(String versionId) {
        return Files.isRegularFile(instanceFile(versionId));
    }

    public GameInstance loadInstance(String versionId) throws IOException {
        JsonNode node = objectMapper.readTree(instanceFile(versionId).toFile());
        return new GameInstance(
                requiredText(node, "id"), requiredText(node, "name"), requiredText(node, "versionId"),
                Path.of(requiredText(node, "directory")), requiredText(node, "mainClass"),
                node.path("requiredJavaMajor").asInt(21), Instant.parse(requiredText(node, "installedAt"))
        );
    }

    public List<GameInstance> installedInstances() {
        if (Files.notExists(instancesDirectory)) return List.of();
        try (var paths = Files.list(instancesDirectory)) {
            return paths.filter(Files::isDirectory)
                    .map(path -> path.resolve("instance.json"))
                    .filter(Files::isRegularFile)
                    .map(path -> {
                        try {
                            return loadInstance(path.getParent().getFileName().toString());
                        } catch (IOException failure) {
                            AppLog.warning("忽略损坏的实例元数据：" + path, failure);
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .toList();
        } catch (IOException failure) {
            AppLog.warning("无法枚举已安装实例", failure);
            return List.of();
        }
    }

    public Path versionMetadata(String versionId) {
        return sharedDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
    }

    public Path librariesDirectory() {
        return sharedDirectory.resolve("libraries");
    }

    public Path assetsDirectory() {
        return sharedDirectory.resolve("assets");
    }

    public Path instancesDirectory() {
        return instancesDirectory;
    }

    public String baseGameVersion(GameInstance instance) {
        try {
            JsonNode metadata = objectMapper.readTree(instance.directory().resolve("instance.json").toFile());
            return metadata.path("baseGameVersion").asText(instance.versionId());
        } catch (IOException failure) {
            return instance.versionId();
        }
    }

    public String loader(GameInstance instance) {
        try {
            JsonNode metadata = objectMapper.readTree(instance.directory().resolve("instance.json").toFile());
            return metadata.path("loader").asText("vanilla");
        } catch (IOException failure) {
            return "vanilla";
        }
    }

    private GameInstance installResolved(
            GameVersion version,
            JsonNode metadata,
            Path metadataFile,
            Path stateFile,
            Consumer<String> status
    ) throws IOException {
        String id = requiredText(metadata, "id");
        String mainClass = requiredText(metadata, "mainClass");
        int requiredJava = metadata.path("javaVersion").path("majorVersion").asInt(8);
        Path instanceDirectory = instancesDirectory.resolve(safeSegment(id));
        Path gameDirectory = instanceDirectory.resolve("game");
        Path nativeDirectory = instanceDirectory.resolve("natives");
        Files.createDirectories(gameDirectory);
        Files.createDirectories(nativeDirectory);

        writeState(stateFile, "CORE_FILES", "正在规划客户端和依赖库", 0, 0, "");
        status.accept("正在规划客户端与依赖库");
        List<PlannedDownload> coreDownloads = new ArrayList<>();
        JsonNode client = metadata.path("downloads").path("client");
        Path clientJar = sharedDirectory.resolve("versions").resolve(id).resolve(id + ".jar");
        coreDownloads.add(new PlannedDownload("Minecraft " + id + " 客户端", request(client, clientJar)));

        Set<Path> nativeArchives = new HashSet<>();
        for (JsonNode library : metadata.path("libraries")) {
            if (!rulesAllow(library.path("rules"))) continue;
            JsonNode artifact = library.path("downloads").path("artifact");
            if (artifact.isObject()) {
                Path target = librariesDirectory().resolve(requiredText(artifact, "path"));
                coreDownloads.add(new PlannedDownload("依赖库 " + library.path("name").asText(), request(artifact, target)));
            }
            String nativeClassifier = nativeClassifier(library.path("natives"));
            JsonNode nativeArtifact = library.path("downloads").path("classifiers").path(nativeClassifier);
            if (!nativeClassifier.isBlank() && nativeArtifact.isObject()) {
                Path target = librariesDirectory().resolve(requiredText(nativeArtifact, "path"));
                coreDownloads.add(new PlannedDownload("原生库 " + library.path("name").asText(), request(nativeArtifact, target)));
                nativeArchives.add(target);
            }
        }

        JsonNode logging = metadata.path("logging").path("client").path("file");
        if (logging.isObject()) {
            coreDownloads.add(new PlannedDownload("日志配置", request(
                    logging, sharedDirectory.resolve("assets/log_configs").resolve(requiredText(logging, "id"))
            )));
        }
        downloadBatch(coreDownloads, "核心文件", stateFile, status);

        JsonNode assetIndex = metadata.path("assetIndex");
        String assetId = requiredText(assetIndex, "id");
        Path assetIndexFile = assetsDirectory().resolve("indexes").resolve(assetId + ".json");
        writeState(stateFile, "ASSET_INDEX", "正在下载资源索引", 0, 0, "");
        awaitWithRetry(enqueue("资源索引 " + assetId, request(assetIndex, assetIndexFile)), "资源索引", status);
        JsonNode objects = objectMapper.readTree(assetIndexFile.toFile()).path("objects");
        if (!objects.isObject()) throw new IOException("资源索引缺少 objects");

        List<PlannedDownload> assets = new ArrayList<>();
        objects.fields().forEachRemaining(entry -> {
            JsonNode value = entry.getValue();
            String hash = value.path("hash").asText();
            if (hash.length() < 2) return;
            Path target = assetsDirectory().resolve("objects").resolve(hash.substring(0, 2)).resolve(hash);
            assets.add(new PlannedDownload("资源 " + entry.getKey(), request(
                    ASSET_BASE.resolve(hash.substring(0, 2) + "/" + hash), target, hash,
                    DownloadService.ChecksumAlgorithm.SHA_1, value.path("size").asLong(-1)
            )));
        });
        downloadAssets(assets, stateFile, status);

        writeState(stateFile, "NATIVES", "正在解压原生库", 0, nativeArchives.size(), "");
        status.accept("正在解压原生库");
        cleanDirectory(nativeDirectory);
        Files.createDirectories(nativeDirectory);
        for (Path archive : nativeArchives.stream().sorted(Comparator.comparing(Path::toString)).toList()) {
            extractNatives(archive, nativeDirectory);
        }

        Files.copy(metadataFile, instanceDirectory.resolve("version.json"), StandardCopyOption.REPLACE_EXISTING);
        Instant installedAt = Instant.now();
        GameInstance instance = new GameInstance(id, "原版 " + id, id, instanceDirectory, mainClass, requiredJava, installedAt);
        writeInstance(instance, clientJar, assetId);
        writeState(stateFile, "COMPLETED", "安装完成", assets.size(), assets.size(), "");
        status.accept("安装完成，可以启动游戏");
        AppLog.info("实例安装完成：" + id + "，资源 " + assets.size() + " 项");
        return instance;
    }

    private void downloadBatch(
            List<PlannedDownload> downloads,
            String label,
            Path stateFile,
            Consumer<String> status
    ) throws IOException {
        List<DownloadQueueService.EnqueueRequest> requests = downloads.stream()
                .map(item -> new DownloadQueueService.EnqueueRequest(item.name(), item.request()))
                .toList();
        List<String> ids = queue.enqueueAll(requests);
        for (int index = 0; index < ids.size(); index++) {
            String message = label + " " + (index + 1) + "/" + ids.size();
            status.accept("正在下载并校验" + message);
            writeState(stateFile, "CORE_FILES", message, index, ids.size(), "");
            awaitWithRetry(ids.get(index), downloads.get(index).name(), status);
        }
    }

    private void downloadAssets(List<PlannedDownload> assets, Path stateFile, Consumer<String> status) throws IOException {
        int completed = 0;
        for (int start = 0; start < assets.size(); start += ASSET_BATCH_SIZE) {
            List<PlannedDownload> batch = assets.subList(start, Math.min(start + ASSET_BATCH_SIZE, assets.size()));
            List<String> ids = queue.enqueueAll(batch.stream()
                    .map(item -> new DownloadQueueService.EnqueueRequest(item.name(), item.request())).toList());
            status.accept("正在下载资源 " + completed + "/" + assets.size() + "，可在下载页暂停");
            writeState(stateFile, "ASSETS", "正在下载资源", completed, assets.size(), "");
            for (int index = 0; index < ids.size(); index++) {
                awaitWithRetry(ids.get(index), batch.get(index).name(), status);
                completed++;
                if (completed % 32 == 0 || completed == assets.size()) {
                    status.accept("正在下载资源 " + completed + "/" + assets.size());
                    writeState(stateFile, "ASSETS", "正在下载资源", completed, assets.size(), "");
                }
            }
        }
    }

    private void awaitWithRetry(String id, String label, Consumer<String> status) throws IOException {
        Throwable last = null;
        for (int attempt = 0; attempt <= AUTOMATIC_RETRIES; attempt++) {
            try {
                queue.completion(id).join();
                return;
            } catch (CompletionException failure) {
                last = unwrap(failure);
                if (attempt >= AUTOMATIC_RETRIES) break;
                status.accept(label + " 下载失败，正在自动重试 " + (attempt + 1) + "/" + AUTOMATIC_RETRIES);
                AppLog.warning(label + " 下载失败，准备重试", last);
                queue.resume(id);
            }
        }
        throw new IOException(label + " 下载失败（已自动重试 " + AUTOMATIC_RETRIES + " 次）：" + message(last), last);
    }

    private String enqueue(String name, DownloadService.DownloadRequest request) {
        return queue.enqueue(name, request);
    }

    private DownloadService.DownloadRequest request(JsonNode download, Path target) {
        return request(
                URI.create(requiredTextUnchecked(download, "url")), target, download.path("sha1").asText(""),
                download.path("sha1").asText("").isBlank()
                        ? DownloadService.ChecksumAlgorithm.NONE : DownloadService.ChecksumAlgorithm.SHA_1,
                download.path("size").asLong(-1)
        );
    }

    private DownloadService.DownloadRequest request(
            URI uri, Path target, String checksum, DownloadService.ChecksumAlgorithm algorithm, long size
    ) {
        return new DownloadService.DownloadRequest(List.of(uri), target, checksum, algorithm, size);
    }

    private boolean rulesAllow(JsonNode rules) {
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode rule : rules) if (ruleMatches(rule)) allowed = "allow".equals(rule.path("action").asText());
        return allowed;
    }

    private boolean ruleMatches(JsonNode rule) {
        JsonNode os = rule.path("os");
        if (os.isObject()) {
            String name = os.path("name").asText();
            if (!name.isBlank() && !name.equals(currentOs())) return false;
            String arch = os.path("arch").asText();
            if (!arch.isBlank() && !normalizeArch(System.getProperty("os.arch", "")).equals(normalizeArch(arch))) return false;
            String version = os.path("version").asText();
            if (!version.isBlank() && !System.getProperty("os.version", "").matches(version)) return false;
        }
        JsonNode features = rule.path("features");
        return !features.isObject() || features.isEmpty();
    }

    private String nativeClassifier(JsonNode natives) {
        String classifier = natives.path(currentOs()).asText("");
        return classifier.replace("${arch}", normalizeArch(System.getProperty("os.arch", "")).equals("x86") ? "32" : "64");
    }

    private String currentOs() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("win")) return "windows";
        if (value.contains("mac")) return "osx";
        return "linux";
    }

    private String normalizeArch(String arch) {
        String value = arch.toLowerCase(Locale.ROOT);
        if (value.equals("x86") || value.equals("i386") || value.equals("i686")) return "x86";
        if (value.contains("aarch64") || value.contains("arm64")) return "arm64";
        return "x86_64";
    }

    private void extractNatives(Path archive, Path target) throws IOException {
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith("META-INF/") || name.startsWith("META-INF\\")) continue;
                Path output = target.resolve(name).normalize();
                if (!output.startsWith(target.normalize())) throw new IOException("原生库包含不安全路径：" + name);
                Files.createDirectories(output.getParent());
                Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private void cleanDirectory(Path directory) throws IOException {
        if (Files.notExists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private void writeInstance(GameInstance instance, Path clientJar, String assetIndex) throws IOException {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("schemaVersion", 2);
        node.put("id", instance.id());
        node.put("name", instance.name());
        node.put("versionId", instance.versionId());
        node.put("directory", instance.directory().toString());
        node.put("mainClass", instance.mainClass());
        node.put("requiredJavaMajor", instance.requiredJavaMajor());
        node.put("installedAt", instance.installedAt().toString());
        node.put("clientJar", clientJar.toString());
        node.put("assetIndex", assetIndex);
        atomicWrite(instance.directory().resolve("instance.json"), node);
    }

    private void writeState(Path target, String phase, String detail, int completed, int total, String failure) throws IOException {
        ObjectNode state = objectMapper.createObjectNode();
        state.put("schemaVersion", 1);
        state.put("phase", phase);
        state.put("detail", detail);
        state.put("completed", completed);
        state.put("total", total);
        state.put("failure", failure == null ? "" : failure);
        state.put("updatedAt", Instant.now().toString());
        atomicWrite(target, state);
    }

    private void atomicWrite(Path target, JsonNode node) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        objectMapper.writeValue(temporary.toFile(), node);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Path instanceFile(String versionId) {
        return instancesDirectory.resolve(safeSegment(versionId)).resolve("instance.json");
    }

    private String safeSegment(String value) {
        return value.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private String requiredText(JsonNode parent, String field) throws IOException {
        String value = parent.path(field).asText("");
        if (value.isBlank()) throw new IOException("安装元数据缺少字段：" + field);
        return value;
    }

    private String requiredTextUnchecked(JsonNode parent, String field) {
        String value = parent.path(field).asText("");
        if (value.isBlank()) throw new IllegalArgumentException("下载元数据缺少字段：" + field);
        return value;
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException) && current.getCause() != null) current = current.getCause();
        return current;
    }

    private String message(Throwable failure) {
        if (failure == null) return "未知错误";
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private record PlannedDownload(String name, DownloadService.DownloadRequest request) {
    }
}
