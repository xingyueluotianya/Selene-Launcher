package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.GameVersion;
import io.selene.launcher.core.model.LoaderVersion;
import io.selene.launcher.core.model.RuntimeInfo;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.core.port.JavaRuntimeManager;
import io.selene.launcher.infra.AppLog;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Runs official Forge/NeoForge installers, then normalizes their generated profile into a Selene instance. */
public final class InstallerLoaderService {
    private static final int MAX_CHECKSUM_BYTES = 4096;
    private static final int MAX_METADATA_BYTES = 4 * 1024 * 1024;
    private static final int AUTOMATIC_RETRIES = 2;
    private static final URI MAVEN_MIRROR = URI.create("https://bmclapi2.bangbang93.com/maven/");
    private static final Pattern MAVEN_VERSION = Pattern.compile("<version>([^<]+)</version>");

    private final VanillaGameService vanilla;
    private final DownloadQueueService queue;
    private final Path instancesDirectory;
    private final Path sharedDirectory;
    private final Path toolsDirectory;
    private final JavaRuntimeManager runtimeManager;
    private final boolean preferMirror;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    public InstallerLoaderService(
            VanillaGameService vanilla,
            DownloadQueueService queue,
            Path instancesDirectory,
            Path sharedDirectory,
            Path toolsDirectory,
            JavaRuntimeManager runtimeManager
    ) {
        this(vanilla, queue, instancesDirectory, sharedDirectory, toolsDirectory, runtimeManager, false);
    }

    public InstallerLoaderService(
            VanillaGameService vanilla,
            DownloadQueueService queue,
            Path instancesDirectory,
            Path sharedDirectory,
            Path toolsDirectory,
            JavaRuntimeManager runtimeManager,
            boolean preferMirror
    ) {
        this.vanilla = vanilla;
        this.queue = queue;
        this.instancesDirectory = instancesDirectory;
        this.sharedDirectory = sharedDirectory;
        this.toolsDirectory = toolsDirectory;
        this.runtimeManager = runtimeManager;
        this.preferMirror = preferMirror;
    }

    public CompletableFuture<List<LoaderVersion>> versions(Loader loader, String gameVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String xml = fetchText(loader.metadataUris(preferMirror), MAX_METADATA_BYTES);
                Matcher matcher = MAVEN_VERSION.matcher(xml);
                List<LoaderVersion> versions = new ArrayList<>();
                while (matcher.find()) {
                    String published = matcher.group(1).trim();
                    String selectable = loader.selectableVersion(gameVersion, published);
                    if (selectable == null) continue;
                    String channel = published.toLowerCase(Locale.ROOT).matches(".*(?:beta|alpha|rc|snapshot).*" )
                            ? "测试版" : "正式版";
                    versions.add(new LoaderVersion(selectable, "正式版".equals(channel), channel));
                }
                versions.sort((left, right) -> compareVersions(right.version(), left.version()));
                if (versions.isEmpty()) throw new IOException(loader.displayName() + " 暂不支持 Minecraft " + gameVersion);
                return List.copyOf(versions);
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<GameInstance> install(
            Loader loader, GameVersion gameVersion, String loaderVersion, Consumer<String> status
    ) {
        if (loaderVersion == null || loaderVersion.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException(loader.displayName() + " 需要填写 Loader 版本"));
        }
        return vanilla.install(gameVersion, status).thenApplyAsync(base -> {
            try {
                String version = loaderVersion.trim();
                List<URI> installerUris = loader.installerUris(gameVersion.id(), version, preferMirror);
                status.accept("正在校验 " + loader.displayName() + " 安装器");
                String sha1 = fetchOptionalSha1(withSuffix(installerUris, ".sha1"), loader.displayName(), version);
                Path installer = toolsDirectory.resolve("installers").resolve(loader.id() + "-" + version + "-installer.jar");
                DownloadService.ChecksumAlgorithm checksum = sha1.isBlank()
                        ? DownloadService.ChecksumAlgorithm.NONE : DownloadService.ChecksumAlgorithm.SHA_1;
                String task = queue.enqueue(loader.displayName() + " " + version + " 安装器",
                        new DownloadService.DownloadRequest(installerUris, installer, sha1, checksum, -1));
                queue.completion(task).join();

                InstallerProfile profile = readInstallerProfile(installer);
                if (!profile.minecraftVersion().isBlank() && !profile.minecraftVersion().equals(gameVersion.id())) {
                    throw new IOException(loader.displayName() + " 安装器目标版本为 " + profile.minecraftVersion()
                            + "，与所选 Minecraft " + gameVersion.id() + " 不一致");
                }
                status.accept("正在准备 " + loader.displayName() + " 安装依赖");
                prefetchInstallerLibraries(loader, installer, status);
                prepareInstallerDirectory();
                status.accept("正在准备 Java " + base.requiredJavaMajor() + " 并运行 " + loader.displayName() + " 官方安装器");
                RuntimeInfo installerRuntime = runtimeManager.resolve(base.requiredJavaMajor()).join();
                runInstaller(installer, installerRuntime.executable());
                Path generated = generatedProfile(profile.versionId());
                JsonNode parent = mapper.readTree(vanilla.versionMetadata(gameVersion.id()).toFile());
                JsonNode child = mapper.readTree(generated.toFile());
                if (!profile.versionId().equals(child.path("id").asText(""))) {
                    throw new IOException(loader.displayName() + " 安装器生成了不匹配的版本配置：" + generated);
                }
                String id = loader.id() + "-" + version + "-" + gameVersion.id();
                ObjectNode merged = merge(parent, child, id);
                Path normalized = sharedDirectory.resolve("versions").resolve(id).resolve(id + ".json");
                atomicWrite(normalized, merged);

                Path directory = instancesDirectory.resolve(id.replaceAll("[^A-Za-z0-9._-]", "_"));
                Files.createDirectories(directory.resolve("game/mods"));
                copyTree(base.directory().resolve("natives"), directory.resolve("natives"));
                Files.copy(normalized, directory.resolve("version.json"), StandardCopyOption.REPLACE_EXISTING);
                GameInstance instance = new GameInstance(
                        id, loader.displayName() + " " + gameVersion.id() + " · " + version, id, directory,
                        merged.path("mainClass").asText(), merged.path("javaVersion").path("majorVersion").asInt(base.requiredJavaMajor()),
                        Instant.now()
                );
                writeInstance(instance, sharedDirectory.resolve("versions").resolve(gameVersion.id()).resolve(gameVersion.id() + ".jar"),
                        merged.path("assetIndex").path("id").asText(gameVersion.id()), loader.id(), gameVersion.id());
                status.accept(loader.displayName() + " 实例安装完成");
                AppLog.info(loader.displayName() + " 实例安装完成：" + id);
                return instance;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private void runInstaller(Path installer, Path managedExecutable) throws IOException {
        Path java = managedExecutable;
        if (managedExecutable.getFileName().toString().equalsIgnoreCase("javaw.exe")) {
            Path console = managedExecutable.resolveSibling("java.exe");
            if (Files.isRegularFile(console)) java = console;
        }
        ProcessBuilder builder = new ProcessBuilder(
                java.toString(), "-Xmx1G", "-jar", installer.toString(), "--installClient", sharedDirectory.toString()
        ).redirectErrorStream(true);
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (output.size() < 4 * 1024 * 1024) output.write(buffer, 0, read);
            }
            int code = process.waitFor();
            if (code != 0) throw new IOException("官方安装器退出代码 " + code + "：\n" + output.toString(StandardCharsets.UTF_8));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("官方安装器执行被中断", failure);
        }
    }

    InstallerProfile readInstallerProfile(Path installer) throws IOException {
        try (ZipFile archive = new ZipFile(installer.toFile())) {
            JsonNode installProfile = readZipJson(archive, "install_profile.json");
            JsonNode version = readZipJson(archive, "version.json");
            String versionId = installProfile.path("version").asText(version.path("id").asText(""));
            if (versionId.isBlank()) throw new IOException("官方安装器缺少版本 ID");
            String minecraft = installProfile.path("minecraft").asText(version.path("inheritsFrom").asText(""));
            return new InstallerProfile(versionId, minecraft);
        }
    }

    private void prefetchInstallerLibraries(Loader loader, Path installer, Consumer<String> status) throws IOException {
        List<InstallerLibrary> libraries = planInstallerLibraries(loader, installer);
        List<String> tasks = libraries.stream()
                .map(library -> queue.enqueue(loader.displayName() + " 依赖 " + library.name(), library.request()))
                .toList();
        for (int index = 0; index < tasks.size(); index++) {
            String task = tasks.get(index);
            Throwable last = null;
            for (int attempt = 0; attempt <= AUTOMATIC_RETRIES; attempt++) {
                status.accept("正在下载 " + loader.displayName() + " 依赖 " + (index + 1) + "/" + tasks.size()
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
            if (last != null) throw new IOException(loader.displayName() + " 依赖下载失败（已自动重试 "
                    + AUTOMATIC_RETRIES + " 次）：" + last.getMessage(), last);
        }
    }

    List<InstallerLibrary> planInstallerLibraries(Loader loader, Path installer) throws IOException {
        Map<Path, InstallerLibrary> planned = new LinkedHashMap<>();
        try (ZipFile archive = new ZipFile(installer.toFile())) {
            collectInstallerLibraries(loader, readZipJson(archive, "install_profile.json").path("libraries"), planned);
            collectInstallerLibraries(loader, readZipJson(archive, "version.json").path("libraries"), planned);
        }
        return List.copyOf(planned.values());
    }

    private void collectInstallerLibraries(
            Loader loader, JsonNode libraries, Map<Path, InstallerLibrary> planned
    ) throws IOException {
        if (!libraries.isArray()) return;
        for (JsonNode library : libraries) {
            String name = library.path("name").asText("");
            if (name.isBlank()) continue;
            MavenCoordinate coordinate = MavenCoordinate.parse(name);
            JsonNode artifact = library.path("downloads").path("artifact");
            String relative = artifact.path("path").asText(coordinate.path());
            URI official;
            String explicitUrl = artifact.path("url").asText("");
            if (!explicitUrl.isBlank()) {
                official = URI.create(explicitUrl);
            } else {
                String repository = library.path("url").asText("");
                official = repository.isBlank()
                        ? loader.dependencyUri(relative)
                        : URI.create(repository.endsWith("/") ? repository : repository + "/").resolve(relative);
            }
            Path target = sharedDirectory.resolve("libraries").resolve(relative).normalize();
            Path libraryRoot = sharedDirectory.resolve("libraries").normalize();
            if (!target.startsWith(libraryRoot)) throw new IOException("加载器依赖包含不安全路径：" + relative);
            String sha1 = artifact.path("sha1").asText("");
            DownloadService.ChecksumAlgorithm checksum = sha1.isBlank()
                    ? DownloadService.ChecksumAlgorithm.NONE : DownloadService.ChecksumAlgorithm.SHA_1;
            DownloadService.DownloadRequest request = new DownloadService.DownloadRequest(
                    loader.dependencyUris(official, relative, preferMirror), target, sha1, checksum,
                    artifact.path("size").asLong(-1)
            );
            planned.putIfAbsent(target, new InstallerLibrary(name, request));
        }
    }

    private JsonNode readZipJson(ZipFile archive, String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null) return mapper.createObjectNode();
        if (entry.getSize() > MAX_METADATA_BYTES) throw new IOException("官方安装器中的 " + name + " 超过安全限制");
        try (InputStream input = archive.getInputStream(entry)) {
            return mapper.readTree(input);
        }
    }

    void prepareInstallerDirectory() throws IOException {
        Files.createDirectories(sharedDirectory);
        Files.createDirectories(sharedDirectory.resolve("versions"));
        Files.createDirectories(sharedDirectory.resolve("libraries"));
        Path profiles = sharedDirectory.resolve("launcher_profiles.json");
        ObjectNode root;
        if (Files.isRegularFile(profiles)) {
            JsonNode existing = mapper.readTree(profiles.toFile());
            if (!existing.isObject()) throw new IOException("launcher_profiles.json 格式无效");
            root = ((ObjectNode) existing).deepCopy();
        } else {
            root = mapper.createObjectNode();
        }
        if (!root.path("profiles").isObject()) root.set("profiles", mapper.createObjectNode());
        atomicWrite(profiles, root);
    }

    private Path generatedProfile(String versionId) throws IOException {
        Path generated = sharedDirectory.resolve("versions").resolve(versionId).resolve(versionId + ".json");
        if (!Files.isRegularFile(generated)) {
            throw new IOException("官方安装器执行完成，但未生成版本配置：" + versionId);
        }
        return generated;
    }

    private ObjectNode merge(JsonNode parent, JsonNode child, String id) {
        ObjectNode result = ((ObjectNode) parent).deepCopy();
        result.put("id", id);
        if (child.hasNonNull("mainClass")) result.set("mainClass", child.path("mainClass"));
        ArrayNode libraries = result.withArray("libraries");
        child.path("libraries").forEach(libraries::add);
        if (child.path("arguments").isObject()) {
            ObjectNode arguments = result.with("arguments");
            append(arguments.withArray("jvm"), child.path("arguments").path("jvm"));
            append(arguments.withArray("game"), child.path("arguments").path("game"));
        }
        String legacy = child.path("minecraftArguments").asText("");
        if (!legacy.isBlank()) {
            String parentLegacy = result.path("minecraftArguments").asText("");
            result.put("minecraftArguments", (parentLegacy + " " + legacy).trim());
        }
        return result;
    }

    private void append(ArrayNode target, JsonNode source) {
        if (source.isArray()) source.forEach(target::add);
    }

    private String fetchOptionalSha1(List<URI> uris, String loader, String version) {
        List<IOException> failures = new ArrayList<>();
        for (URI uri : uris) {
            try {
                String value = fetchText(uri, MAX_CHECKSUM_BYTES).trim().split("\\s+")[0];
                if (!value.matches("[0-9a-fA-F]{40}")) throw new IOException("安装器 SHA-1 格式无效");
                return value;
            } catch (IOException failure) {
                failures.add(failure);
            }
        }
        IOException combined = new IOException(loader + " " + version
                + " 未能取得 SHA-1 旁车文件，将使用 HTTPS 下载");
        failures.forEach(combined::addSuppressed);
        AppLog.warning(combined.getMessage(), combined);
        return "";
    }

    private List<URI> withSuffix(List<URI> uris, String suffix) {
        return uris.stream().map(uri -> URI.create(uri + suffix)).toList();
    }

    private String fetchText(List<URI> uris, int limit) throws IOException {
        List<IOException> failures = new ArrayList<>();
        for (URI uri : uris) {
            try {
                return fetchText(uri, limit);
            } catch (IOException failure) {
                failures.add(new IOException("来源不可用：" + uri + "（" + failure.getMessage() + "）", failure));
            }
        }
        IOException combined = new IOException("官方源与镜像源均不可用");
        failures.forEach(combined::addSuppressed);
        throw combined;
    }

    private String fetchText(URI uri, int limit) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Accept", "text/plain, application/xml").header("User-Agent", "Selene-Launcher/0.3").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] bytes;
            try (InputStream input = response.body()) {
                bytes = input.readNBytes(limit + 1);
            }
            if (bytes.length > limit) throw new IOException("Loader 版本目录响应超过安全限制");
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode(), "Loader 版本目录返回 HTTP " + response.statusCode());
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Loader 版本目录请求被中断", failure);
        }
    }

    private int compareVersions(String left, String right) {
        String[] leftParts = left.split("[.-]");
        String[] rightParts = right.split("[.-]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int index = 0; index < length; index++) {
            String a = index < leftParts.length ? leftParts[index] : "0";
            String b = index < rightParts.length ? rightParts[index] : "0";
            int compared;
            if (a.matches("\\d+") && b.matches("\\d+")) compared = Integer.compare(Integer.parseInt(a), Integer.parseInt(b));
            else compared = a.compareToIgnoreCase(b);
            if (compared != 0) return compared;
        }
        return 0;
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

    private void writeInstance(
            GameInstance instance, Path clientJar, String assetIndex, String loader, String gameVersion
    ) throws IOException {
        ObjectNode node = mapper.createObjectNode();
        node.put("schemaVersion", 2);
        node.put("id", instance.id());
        node.put("name", instance.name());
        node.put("versionId", instance.versionId());
        node.put("baseGameVersion", gameVersion);
        node.put("directory", instance.directory().toString());
        node.put("mainClass", instance.mainClass());
        node.put("requiredJavaMajor", instance.requiredJavaMajor());
        node.put("installedAt", instance.installedAt().toString());
        node.put("clientJar", clientJar.toString());
        node.put("assetIndex", assetIndex);
        node.put("loader", loader);
        atomicWrite(instance.directory().resolve("instance.json"), node);
    }

    private void atomicWrite(Path target, JsonNode node) throws IOException {
        Files.createDirectories(target.toAbsolutePath().normalize().getParent());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), node);
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    record InstallerProfile(String versionId, String minecraftVersion) { }
    record InstallerLibrary(String name, DownloadService.DownloadRequest request) { }

    private record MavenCoordinate(String group, String artifact, String version, String classifier, String extension) {
        static MavenCoordinate parse(String value) throws IOException {
            String[] extensionParts = value.split("@", 2);
            String extension = extensionParts.length == 2 ? extensionParts[1] : "jar";
            String[] parts = extensionParts[0].split(":");
            if (parts.length < 3 || parts.length > 4) throw new IOException("无法解析加载器 Maven 坐标：" + value);
            return new MavenCoordinate(parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : "", extension);
        }

        String path() {
            String file = artifact + "-" + version + (classifier.isBlank() ? "" : "-" + classifier) + "." + extension;
            return group.replace('.', '/') + "/" + artifact + "/" + version + "/" + file;
        }
    }

    private static final class HttpStatusException extends IOException {
        private HttpStatusException(int statusCode, String message) {
            super(message + "（" + statusCode + "）");
        }
    }

    public enum Loader {
        FORGE("forge", "Forge",
                "https://maven.minecraftforge.net/net/minecraftforge/forge/",
                "https://bmclapi2.bangbang93.com/maven/net/minecraftforge/forge/"),
        NEOFORGE("neoforge", "NeoForge",
                "https://maven.neoforged.net/releases/net/neoforged/neoforge/",
                "https://bmclapi2.bangbang93.com/maven/net/neoforged/neoforge/");

        private final String id;
        private final String displayName;
        private final String repository;
        private final String mirrorRepository;

        Loader(String id, String displayName, String repository, String mirrorRepository) {
            this.id = id;
            this.displayName = displayName;
            this.repository = repository;
            this.mirrorRepository = mirrorRepository;
        }

        public String id() { return id; }
        public String displayName() { return displayName; }

        String selectableVersion(String gameVersion, String publishedVersion) {
            if (this == FORGE) {
                String prefix = gameVersion + "-";
                return publishedVersion.startsWith(prefix) ? publishedVersion.substring(prefix.length()) : null;
            }
            String gameSeries = neoforgeSeries(gameVersion);
            return publishedVersion.startsWith(gameSeries + ".") || publishedVersion.equals(gameSeries)
                    ? publishedVersion : null;
        }

        private String neoforgeSeries(String gameVersion) {
            String[] parts = gameVersion.split("\\.");
            if (parts.length >= 2 && "1".equals(parts[0])) {
                return parts[1] + "." + (parts.length >= 3 ? parts[2] : "0");
            }
            return gameVersion;
        }

        List<URI> metadataUris(boolean preferMirror) {
            return repositoryUris("maven-metadata.xml", preferMirror);
        }

        List<URI> installerUris(String gameVersion, String loaderVersion, boolean preferMirror) {
            String relative;
            if (this == FORGE) {
                String combined = gameVersion + "-" + loaderVersion;
                relative = combined + "/forge-" + combined + "-installer.jar";
            } else {
                relative = loaderVersion + "/neoforge-" + loaderVersion + "-installer.jar";
            }
            return repositoryUris(relative, preferMirror);
        }

        URI installerUri(String gameVersion, String loaderVersion) {
            return installerUris(gameVersion, loaderVersion, false).getFirst();
        }

        URI dependencyUri(String relative) {
            URI root = this == FORGE
                    ? URI.create("https://maven.minecraftforge.net/")
                    : URI.create("https://maven.neoforged.net/releases/");
            return root.resolve(relative);
        }

        List<URI> dependencyUris(URI official, String relative, boolean preferMirror) {
            String host = official.getHost() == null ? "" : official.getHost().toLowerCase(Locale.ROOT);
            boolean mirroredMaven = host.equals("maven.minecraftforge.net")
                    || host.equals("maven.neoforged.net")
                    || host.equals("maven.fabricmc.net")
                    || host.equals("repo1.maven.org")
                    || host.equals("repo.maven.apache.org");
            if (!mirroredMaven) return List.of(official);
            URI mirror = MAVEN_MIRROR.resolve(relative);
            if (mirror.equals(official)) return List.of(official);
            return preferMirror ? List.of(mirror, official) : List.of(official, mirror);
        }

        private List<URI> repositoryUris(String relative, boolean preferMirror) {
            URI official = URI.create(repository + relative);
            if (mirrorRepository.isBlank()) return List.of(official);
            URI mirror = URI.create(mirrorRepository + relative);
            return preferMirror ? List.of(mirror, official) : List.of(official, mirror);
        }
    }
}
