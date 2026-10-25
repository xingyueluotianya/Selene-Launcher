package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.GameVersion;
import io.selene.launcher.core.model.LoaderVersion;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.infra.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Installs Fabric Loader from Fabric Meta's official launcher profile JSON. */
public final class FabricLoaderService {
    private static final URI META = URI.create("https://meta.fabricmc.net/");
    private static final URI META_MIRROR = URI.create("https://bmclapi2.bangbang93.com/fabric-meta/");
    private static final URI MAVEN_MIRROR = URI.create("https://bmclapi2.bangbang93.com/maven/");
    private static final int MAX_METADATA = 8 * 1024 * 1024;
    private static final int AUTOMATIC_RETRIES = 2;

    private final VanillaGameService vanilla;
    private final DownloadQueueService queue;
    private final Path instancesDirectory;
    private final Path sharedDirectory;
    private final boolean preferMirror;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    public FabricLoaderService(
            VanillaGameService vanilla,
            DownloadQueueService queue,
            Path instancesDirectory,
            Path sharedDirectory
    ) {
        this(vanilla, queue, instancesDirectory, sharedDirectory, false);
    }

    public FabricLoaderService(
            VanillaGameService vanilla,
            DownloadQueueService queue,
            Path instancesDirectory,
            Path sharedDirectory,
            boolean preferMirror
    ) {
        this.vanilla = vanilla;
        this.queue = queue;
        this.instancesDirectory = instancesDirectory;
        this.sharedDirectory = sharedDirectory;
        this.preferMirror = preferMirror;
    }

    public CompletableFuture<GameInstance> install(
            GameVersion gameVersion, String loaderVersion, Consumer<String> status
    ) {
        return vanilla.install(gameVersion, status).thenApplyAsync(base -> {
            try {
                status.accept("正在获取 Fabric Loader 版本");
                String resolvedLoader = loaderVersion == null || loaderVersion.isBlank()
                        ? latestStableLoader() : loaderVersion.trim();
                JsonNode profile = fetchJson("v2/versions/loader/" + gameVersion.id() + "/"
                        + resolvedLoader + "/profile/json");
                String id = "fabric-loader-" + resolvedLoader + "-" + gameVersion.id();
                ObjectNode merged = mergeMetadata(mapper.readTree(vanilla.versionMetadata(gameVersion.id()).toFile()), profile, id);
                Path metadata = sharedDirectory.resolve("versions").resolve(id).resolve(id + ".json");
                atomicWrite(metadata, merged);
                status.accept("正在下载 Fabric 依赖库");
                downloadLibraries(profile.path("libraries"), status);

                Path directory = instancesDirectory.resolve(id.replaceAll("[^A-Za-z0-9._-]", "_"));
                Files.createDirectories(directory.resolve("game/mods"));
                copyTree(base.directory().resolve("natives"), directory.resolve("natives"));
                Files.copy(metadata, directory.resolve("version.json"), StandardCopyOption.REPLACE_EXISTING);
                GameInstance instance = new GameInstance(
                        id, "Fabric " + gameVersion.id() + " · " + resolvedLoader, id, directory,
                        merged.path("mainClass").asText(), merged.path("javaVersion").path("majorVersion").asInt(base.requiredJavaMajor()),
                        Instant.now()
                );
                writeInstance(instance, sharedDirectory.resolve("versions").resolve(gameVersion.id()).resolve(gameVersion.id() + ".jar"),
                        merged.path("assetIndex").path("id").asText(gameVersion.id()), gameVersion.id());
                status.accept("Fabric 实例安装完成");
                AppLog.info("Fabric 实例安装完成：" + id);
                return instance;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<List<LoaderVersion>> versions(String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode loaders = fetchJson("v2/versions/loader/" + gameVersion);
                if (!loaders.isArray()) throw new IOException("Fabric Meta 未返回版本列表");
                List<LoaderVersion> versions = new ArrayList<>();
                for (JsonNode item : loaders) {
                    JsonNode loader = item.path("loader");
                    String version = loader.path("version").asText("");
                    if (version.isBlank()) continue;
                    boolean stable = loader.path("stable").asBoolean(false);
                    versions.add(new LoaderVersion(version, stable, stable ? "稳定版" : "测试版"));
                }
                if (versions.isEmpty()) throw new IOException("Fabric 暂不支持 Minecraft " + gameVersion);
                return List.copyOf(versions);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public String latestStableLoader() throws IOException {
        JsonNode loaders = fetchJson("v2/versions/loader");
        for (JsonNode item : loaders) {
            if (item.path("stable").asBoolean(false)) return required(item, "version");
        }
        if (loaders.isArray() && !loaders.isEmpty()) return required(loaders.get(0), "version");
        throw new IOException("Fabric Meta 未返回可用 Loader");
    }

    private ObjectNode mergeMetadata(JsonNode base, JsonNode profile, String id) {
        ObjectNode merged = ((ObjectNode) base).deepCopy();
        merged.put("id", id);
        merged.put("mainClass", profile.path("mainClass").asText(base.path("mainClass").asText()));
        ArrayNode libraries = merged.withArray("libraries");
        profile.path("libraries").forEach(libraries::add);
        JsonNode profileArguments = profile.path("arguments");
        if (profileArguments.isObject()) {
            ObjectNode arguments = merged.with("arguments");
            append(arguments.withArray("jvm"), profileArguments.path("jvm"));
            append(arguments.withArray("game"), profileArguments.path("game"));
        }
        return merged;
    }

    private void append(ArrayNode target, JsonNode source) {
        if (source.isArray()) source.forEach(target::add);
    }

    private void downloadLibraries(JsonNode libraries, Consumer<String> status) throws IOException {
        List<LibraryDownload> downloads = planLibraries(libraries);
        List<String> tasks = downloads.stream()
                .map(download -> queue.enqueue("Fabric 依赖 " + download.name(), download.request()))
                .toList();
        for (int index = 0; index < tasks.size(); index++) {
            String task = tasks.get(index);
            Throwable last = null;
            for (int attempt = 0; attempt <= AUTOMATIC_RETRIES; attempt++) {
                status.accept("正在下载 Fabric 依赖 " + (index + 1) + "/" + tasks.size()
                        + (attempt == 0 ? "" : " · 重试 " + attempt + "/" + AUTOMATIC_RETRIES));
                try {
                    queue.completion(task).join();
                    last = null;
                    break;
                } catch (CompletionException failure) {
                    last = failure.getCause() == null ? failure : failure.getCause();
                    if (attempt < AUTOMATIC_RETRIES) queue.resume(task);
                }
            }
            if (last != null) throw new IOException("Fabric 依赖下载失败（已自动重试 "
                    + AUTOMATIC_RETRIES + " 次）：" + last.getMessage(), last);
        }
    }

    List<LibraryDownload> planLibraries(JsonNode libraries) throws IOException {
        Map<Path, LibraryDownload> downloads = new LinkedHashMap<>();
        for (JsonNode library : libraries) {
            if (!rulesAllow(library.path("rules"))) continue;
            String name = library.path("name").asText("");
            if (name.isBlank()) continue;
            MavenCoordinate coordinate = MavenCoordinate.parse(name);
            JsonNode artifact = library.path("downloads").path("artifact");
            String relative = artifact.path("path").asText(coordinate.path());
            String explicitUrl = artifact.path("url").asText("");
            URI uri;
            if (!explicitUrl.isBlank()) {
                uri = URI.create(explicitUrl);
            } else {
                String repository = library.path("url").asText("https://maven.fabricmc.net/");
                if (!repository.endsWith("/")) repository += "/";
                uri = URI.create(repository).resolve(relative);
            }
            List<URI> candidates = artifactCandidates(uri, relative);
            Path target = vanilla.librariesDirectory().resolve(relative).normalize();
            if (!target.startsWith(vanilla.librariesDirectory().normalize())) {
                throw new IOException("Fabric 依赖包含不安全路径：" + relative);
            }
            String sha1 = artifact.path("sha1").asText("");
            DownloadService.ChecksumAlgorithm checksum = sha1.isBlank()
                    ? DownloadService.ChecksumAlgorithm.NONE : DownloadService.ChecksumAlgorithm.SHA_1;
            LibraryDownload planned = new LibraryDownload(name, new DownloadService.DownloadRequest(
                    candidates, target, sha1, checksum, artifact.path("size").asLong(-1)
            ));
            downloads.putIfAbsent(target, planned);
        }
        return List.copyOf(downloads.values());
    }

    private List<URI> artifactCandidates(URI official, String relative) {
        if (!"maven.fabricmc.net".equalsIgnoreCase(official.getHost())) return List.of(official);
        URI mirror = MAVEN_MIRROR.resolve(relative);
        return preferMirror ? List.of(mirror, official) : List.of(official, mirror);
    }

    private boolean rulesAllow(JsonNode rules) {
        if (!rules.isArray() || rules.isEmpty()) return true;
        boolean allowed = false;
        for (JsonNode rule : rules) {
            if (ruleMatches(rule)) allowed = "allow".equals(rule.path("action").asText());
        }
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

    private void copyTree(Path source, Path target) throws IOException {
        if (Files.notExists(source)) {
            Files.createDirectories(target);
            return;
        }
        try (var stream = Files.walk(source)) {
            for (Path path : stream.toList()) {
                Path destination = target.resolve(source.relativize(path)).normalize();
                if (Files.isDirectory(path)) Files.createDirectories(destination);
                else {
                    Files.createDirectories(destination.getParent());
                    Files.copy(path, destination, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private JsonNode fetchJson(String relative) throws IOException {
        URI official = META.resolve(relative);
        URI mirror = META_MIRROR.resolve(relative);
        List<URI> candidates = preferMirror ? List.of(mirror, official) : List.of(official, mirror);
        List<IOException> failures = new ArrayList<>();
        for (URI candidate : candidates) {
            try {
                return fetchJson(candidate);
            } catch (IOException failure) {
                failures.add(new IOException("来源不可用：" + candidate + "（" + failure.getMessage() + "）", failure));
            }
        }
        IOException combined = new IOException("Fabric 官方源与镜像源均不可用");
        failures.forEach(combined::addSuppressed);
        throw combined;
    }

    private JsonNode fetchJson(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json").header("User-Agent", "Selene-Launcher/0.3").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > MAX_METADATA) throw new IOException("Fabric 元数据超过安全限制");
                    output.write(buffer, 0, read);
                }
                body = output.toByteArray();
            }
            if (response.statusCode() != 200) throw new IOException("Fabric Meta 返回 HTTP " + response.statusCode());
            return mapper.readTree(body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Fabric Meta 请求被中断", failure);
        }
    }

    private void writeInstance(GameInstance instance, Path clientJar, String assetIndex, String baseGameVersion) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 2);
        node.put("id", instance.id());
        node.put("name", instance.name());
        node.put("versionId", instance.versionId());
        node.put("baseGameVersion", baseGameVersion);
        node.put("directory", instance.directory().toString());
        node.put("mainClass", instance.mainClass());
        node.put("requiredJavaMajor", instance.requiredJavaMajor());
        node.put("installedAt", instance.installedAt().toString());
        node.put("clientJar", clientJar.toString());
        node.put("assetIndex", assetIndex);
        node.put("loader", "fabric");
        atomicWrite(instance.directory().resolve("instance.json"), node);
    }

    private void atomicWrite(Path target, JsonNode value) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temp.toFile(), value);
        try {
            Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String required(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("Fabric 元数据缺少字段：" + field);
        return value;
    }

    record LibraryDownload(String name, DownloadService.DownloadRequest request) { }

    private record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {
        static MavenCoordinate parse(String value) throws IOException {
            String[] extensionParts = value.split("@", 2);
            String extension = extensionParts.length == 2 ? extensionParts[1] : "jar";
            String[] parts = extensionParts[0].split(":");
            if (parts.length < 3 || parts.length > 4) throw new IOException("无法解析 Maven 坐标：" + value);
            return new MavenCoordinate(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : "", extension);
        }

        String path() {
            String file = artifact + "-" + version + (classifier.isBlank() ? "" : "-" + classifier) + "." + extension;
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
        }
    }
}
