package io.selene.launcher.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.core.model.GameVersion;
import io.selene.launcher.core.model.VersionCatalog;
import io.selene.launcher.core.port.VersionManifestProvider;

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
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Loads Mojang's authoritative Java Edition version manifest with an offline cache fallback. */
public final class MojangVersionManifestProvider implements VersionManifestProvider {
    public static final URI OFFICIAL_MANIFEST = URI.create(
            "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"
    );
    private static final int MAX_MANIFEST_BYTES = 8 * 1024 * 1024;

    private final HttpClient client;
    private final URI manifestUri;
    private final Path cacheFile;
    private final Clock clock;
    private final ObjectMapper objectMapper;
    private final boolean allowHttpForTests;

    public MojangVersionManifestProvider(Path cacheFile) {
        this(
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .followRedirects(HttpClient.Redirect.NORMAL)
                        .build(),
                OFFICIAL_MANIFEST,
                cacheFile,
                Clock.systemUTC(),
                false
        );
    }

    MojangVersionManifestProvider(
            HttpClient client,
            URI manifestUri,
            Path cacheFile,
            Clock clock,
            boolean allowHttpForTests
    ) {
        this.client = Objects.requireNonNull(client, "client");
        this.manifestUri = Objects.requireNonNull(manifestUri, "manifestUri");
        this.cacheFile = Objects.requireNonNull(cacheFile, "cacheFile");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.objectMapper = new ObjectMapper();
        this.allowHttpForTests = allowHttpForTests;
        String scheme = manifestUri.getScheme() == null ? "" : manifestUri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !(allowHttpForTests && scheme.equals("http"))) {
            throw new IllegalArgumentException("Version manifest must use HTTPS");
        }
    }

    @Override
    public CompletableFuture<VersionCatalog> fetchCatalog() {
        HttpRequest request = HttpRequest.newBuilder(manifestUri)
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .header("Accept-Encoding", "identity")
                .header("User-Agent", "Selene-Launcher/0.2")
                .GET()
                .build();

        return client.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream())
                .thenApply(response -> readNetworkResponse(response))
                .handle((bytes, failure) -> {
                    if (failure == null) {
                        VersionCatalog catalog;
                        try {
                            catalog = parse(bytes, VersionCatalog.Origin.NETWORK, clock.instant());
                        } catch (IOException | RuntimeException exception) {
                            return loadCacheOrFail(exception);
                        }
                        try {
                            writeCache(bytes);
                        } catch (IOException cacheWriteFailure) {
                            AppLog.warning("无法更新 Mojang 版本缓存", cacheWriteFailure);
                        }
                        return catalog;
                    }
                    return loadCacheOrFail(failure);
                });
    }

    private byte[] readNetworkResponse(HttpResponse<InputStream> response) {
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) {
                throw new IOException("Mojang version manifest returned HTTP " + response.statusCode());
            }
            return readLimited(input);
        } catch (IOException exception) {
            throw new CompletionException(exception);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[32 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_MANIFEST_BYTES) {
                throw new IOException("Version manifest exceeds safety limit");
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private VersionCatalog loadCacheOrFail(Throwable networkFailure) {
        try {
            byte[] cached = Files.readAllBytes(cacheFile);
            Instant cachedAt = Files.getLastModifiedTime(cacheFile).toInstant();
            return parse(cached, VersionCatalog.Origin.CACHE, cachedAt);
        } catch (IOException | RuntimeException cacheFailure) {
            RuntimeException failure = new ManifestUnavailableException(
                    "Mojang version manifest is unavailable and no valid cache exists",
                    unwrap(networkFailure)
            );
            failure.addSuppressed(cacheFailure);
            throw failure;
        }
    }

    private VersionCatalog parse(byte[] json, VersionCatalog.Origin origin, Instant fetchedAt) throws IOException {
        JsonNode root = objectMapper.readTree(json);
        JsonNode latest = requiredObject(root, "latest");
        JsonNode versionsNode = root.get("versions");
        if (versionsNode == null || !versionsNode.isArray()) {
            throw new IOException("Version manifest has no versions array");
        }

        List<GameVersion> versions = new ArrayList<>();
        for (JsonNode version : versionsNode) {
            String id = requiredText(version, "id");
            String type = requiredText(version, "type");
            Instant releaseTime = Instant.parse(requiredText(version, "releaseTime"));
            URI metadataUri = URI.create(requiredText(version, "url"));
            String metadataScheme = metadataUri.getScheme() == null
                    ? ""
                    : metadataUri.getScheme().toLowerCase(Locale.ROOT);
            if (!metadataScheme.equals("https") && !(allowHttpForTests && metadataScheme.equals("http"))) {
                throw new IOException("Version metadata URI must use HTTPS: " + metadataUri);
            }
            versions.add(new GameVersion(
                    id,
                    GameVersion.Type.fromManifest(type),
                    releaseTime,
                    metadataUri,
                    optionalText(version, "sha1"),
                    version.path("complianceLevel").asInt(0)
            ));
        }
        if (versions.isEmpty()) {
            throw new IOException("Version manifest is empty");
        }

        return new VersionCatalog(
                requiredText(latest, "release"),
                requiredText(latest, "snapshot"),
                versions,
                fetchedAt,
                origin
        );
    }

    private JsonNode requiredObject(JsonNode parent, String field) throws IOException {
        JsonNode value = parent.get(field);
        if (value == null || !value.isObject()) {
            throw new IOException("Version manifest field is missing: " + field);
        }
        return value;
    }

    private String requiredText(JsonNode parent, String field) throws IOException {
        String value = optionalText(parent, field);
        if (value.isBlank()) {
            throw new IOException("Version manifest field is missing: " + field);
        }
        return value;
    }

    private String optionalText(JsonNode parent, String field) {
        JsonNode value = parent.get(field);
        return value == null || !value.isTextual() ? "" : value.asText();
    }

    private void writeCache(byte[] bytes) throws IOException {
        Path parent = cacheFile.toAbsolutePath().normalize().getParent();
        if (parent == null) {
            throw new IOException("Version cache must have a parent directory");
        }
        Files.createDirectories(parent);
        Path temporary = cacheFile.resolveSibling(cacheFile.getFileName() + ".tmp");
        Files.write(temporary, bytes);
        try {
            Files.move(temporary, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(temporary, cacheFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private Throwable unwrap(Throwable failure) {
        Throwable current = failure == null ? new IOException("Unknown network failure") : failure;
        while ((current instanceof CompletionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    public static final class ManifestUnavailableException extends RuntimeException {
        public ManifestUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
