package io.selene.launcher.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.application.DownloadQueueService;
import io.selene.launcher.core.port.DownloadService;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Resolves the official authlib-injector artifact used by custom Yggdrasil accounts. */
public final class AuthlibInjectorManager {
    private static final URI MANIFEST = URI.create("https://authlib-injector.yushi.moe/artifact/latest.json");
    private static final int MAX_METADATA = 1024 * 1024;

    private final DownloadQueueService queue;
    private final Path toolsDirectory;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthlibInjectorManager(DownloadQueueService queue, Path toolsDirectory) {
        this.queue = queue;
        this.toolsDirectory = toolsDirectory;
    }

    public CompletableFuture<Path> resolve() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Artifact artifact = fetchArtifact();
                Path target = toolsDirectory.resolve("authlib-injector-" + artifact.version() + ".jar");
                if (Files.isRegularFile(target)) return target;
                String id = queue.enqueue("authlib-injector " + artifact.version(),
                        new DownloadService.DownloadRequest(
                                List.of(artifact.uri()), target, artifact.sha256(),
                                DownloadService.ChecksumAlgorithm.SHA_256, -1
                        ));
                return queue.completion(id).join();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private Artifact fetchArtifact() throws IOException {
        HttpRequest request = HttpRequest.newBuilder(MANIFEST).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json").header("User-Agent", "Selene-Launcher/0.2").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input);
            }
            if (response.statusCode() != 200) throw new IOException("authlib-injector 元数据返回 HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(body);
            String version = root.path("version").asText(root.path("build_number").asText("latest"));
            String url = root.path("download_url").asText("");
            String sha256 = root.path("checksums").path("sha256").asText(root.path("sha256").asText(""));
            if (url.isBlank() || sha256.length() != 64) throw new IOException("authlib-injector 元数据缺少下载地址或 SHA-256");
            return new Artifact(version.replaceAll("[^A-Za-z0-9._-]", "_"), URI.create(url), sha256);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("authlib-injector 元数据请求被中断", failure);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_METADATA) throw new IOException("authlib-injector 元数据超过安全限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private record Artifact(String version, URI uri, String sha256) {
    }
}
