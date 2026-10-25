package io.selene.launcher.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.application.DownloadQueueService;
import io.selene.launcher.core.model.RuntimeInfo;
import io.selene.launcher.core.port.DownloadService;
import io.selene.launcher.core.port.JavaRuntimeManager;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Downloads checksum-verified Eclipse Temurin runtimes without changing system Java. */
public final class AdoptiumJavaRuntimeManager implements JavaRuntimeManager {
    private static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;

    private final DownloadQueueService queue;
    private final Path runtimesDirectory;
    private final HttpClient client;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AdoptiumJavaRuntimeManager(DownloadQueueService queue, Path runtimesDirectory) {
        this.queue = queue;
        this.runtimesDirectory = runtimesDirectory;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }

    @Override
    public CompletableFuture<RuntimeInfo> resolve(int requiredMajorVersion) {
        if (requiredMajorVersion < 8 || requiredMajorVersion > 99) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("不支持的 Java 主版本：" + requiredMajorVersion));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                String os = osName();
                String architecture = architecture();
                Path home = runtimesDirectory.resolve("temurin-" + requiredMajorVersion + "-" + os + "-" + architecture);
                Path executable = findExecutable(home);
                if (executable != null) {
                    return new RuntimeInfo(executable, requiredMajorVersion, "Eclipse Temurin", RuntimeInfo.Source.SELENE_MANAGED);
                }
                if (!os.equals("windows")) {
                    int current = Runtime.version().feature();
                    if (current >= requiredMajorVersion && requiredMajorVersion >= 17) {
                        Path currentJava = Path.of(System.getProperty("java.home"), "bin", "java");
                        return new RuntimeInfo(currentJava, current, System.getProperty("java.vendor", "Java"), RuntimeInfo.Source.SYSTEM);
                    }
                    throw new IOException("当前 Alpha 的自动 Java 安装仅开放 Windows x64/ARM64；当前平台为 " + os);
                }

                RuntimePackage runtimePackage = fetchPackage(requiredMajorVersion, os, architecture);
                Path archive = runtimesDirectory.resolve("downloads").resolve(runtimePackage.fileName());
                String taskId = queue.enqueue(
                        "Java " + requiredMajorVersion + " 运行时",
                        new DownloadService.DownloadRequest(
                                List.of(runtimePackage.uri()), archive, runtimePackage.checksum(),
                                DownloadService.ChecksumAlgorithm.SHA_256, runtimePackage.size()
                        )
                );
                queue.completion(taskId).join();
                extractZip(archive, home);
                executable = findExecutable(home);
                if (executable == null) {
                    throw new IOException("Java 运行时解压完成，但未找到 javaw.exe");
                }
                return new RuntimeInfo(executable, requiredMajorVersion, "Eclipse Temurin", RuntimeInfo.Source.SELENE_MANAGED);
            } catch (IOException exception) {
                throw new CompletionException(exception);
            }
        });
    }

    private RuntimePackage fetchPackage(int major, String os, String architecture) throws IOException {
        RuntimePackage jre = fetchPackageType(major, os, architecture, "jre");
        if (jre != null) return jre;
        RuntimePackage jdk = fetchPackageType(major, os, architecture, "jdk");
        if (jdk != null) return jdk;
        throw new IOException("未找到 Java " + major + " 的 Windows x64/ARM64 JRE 或 JDK 运行时");
    }

    private RuntimePackage fetchPackageType(int major, String os, String architecture, String imageType) throws IOException {
        URI uri = URI.create("https://api.adoptium.net/v3/assets/latest/" + major
                + "/hotspot?architecture=" + URLEncoder.encode(architecture, StandardCharsets.UTF_8)
                + "&image_type=" + imageType + "&os=" + URLEncoder.encode(os, StandardCharsets.UTF_8)
                + "&vendor=eclipse");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json")
                .header("User-Agent", "Selene-Launcher/0.3")
                .GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream input = response.body()) {
                if (response.statusCode() == 404) return null;
                if (response.statusCode() != 200) throw new IOException("Java 运行时目录返回 HTTP " + response.statusCode());
                JsonNode assets = objectMapper.readTree(readLimited(input));
                if (!assets.isArray() || assets.isEmpty()) return null;
                JsonNode packageNode = assets.get(0).path("binary").path("package");
                String link = requiredText(packageNode, "link");
                String checksum = requiredText(packageNode, "checksum");
                String name = requiredText(packageNode, "name");
                if (!name.toLowerCase(Locale.ROOT).endsWith(".zip")) {
                    throw new IOException("运行时包不是受支持的 ZIP：" + name);
                }
                return new RuntimePackage(URI.create(link), checksum, name, packageNode.path("size").asLong(-1));
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Java 运行时目录请求被中断", exception);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_METADATA_BYTES) throw new IOException("Java 运行时目录超过安全限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private void extractZip(Path archive, Path destination) throws IOException {
        Path staging = destination.resolveSibling(destination.getFileName() + ".installing");
        deleteTree(staging);
        Files.createDirectories(staging);
        try (InputStream input = Files.newInputStream(archive); ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                Path output = staging.resolve(entry.getName()).normalize();
                if (!output.startsWith(staging.normalize())) throw new IOException("Java 包包含不安全路径：" + entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(output);
                } else {
                    Files.createDirectories(output.getParent());
                    Files.copy(zip, output, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        deleteTree(destination);
        Files.move(staging, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    private Path findExecutable(Path home) throws IOException {
        if (Files.notExists(home)) return null;
        String name = osName().equals("windows") ? "javaw.exe" : "java";
        try (var paths = Files.walk(home, 5)) {
            return paths.filter(path -> Files.isRegularFile(path) && path.getFileName().toString().equalsIgnoreCase(name)
                            && path.getParent() != null && path.getParent().getFileName().toString().equalsIgnoreCase("bin"))
                    .findFirst().orElse(null);
        }
    }

    private void deleteTree(Path directory) throws IOException {
        if (Files.notExists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }

    private String requiredText(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("Java 运行时目录缺少字段：" + field);
        return value;
    }

    private String osName() {
        String value = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (value.contains("win")) return "windows";
        if (value.contains("mac")) return "mac";
        return "linux";
    }

    private String architecture() {
        String value = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (value.contains("aarch64") || value.contains("arm64")) return "aarch64";
        if (value.equals("x86") || value.equals("i386") || value.equals("i686")) return "x32";
        return "x64";
    }

    private record RuntimePackage(URI uri, String checksum, String fileName, long size) {
    }
}
