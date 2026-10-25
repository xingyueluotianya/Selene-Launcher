package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.port.DownloadService;

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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/** Minimal Modrinth resource provider for search and verified primary-file installation. */
public final class ModrinthService {
    private static final URI API = URI.create("https://api.modrinth.com/v2/");
    private static final int MAX_RESPONSE = 8 * 1024 * 1024;

    private final DownloadQueueService queue;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final ObjectMapper mapper = new ObjectMapper();

    public ModrinthService(DownloadQueueService queue) {
        this.queue = queue;
    }

    public CompletableFuture<List<Project>> search(String query, ProjectType type) {
        return search(query, type, "", "");
    }

    public CompletableFuture<List<Project>> search(
            String query, ProjectType type, String gameVersion, String loader
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                List<String> facets = new ArrayList<>();
                facets.add("[\"project_type:" + type.apiName() + "\"]");
                if (gameVersion != null && !gameVersion.isBlank()) facets.add("[\"versions:" + gameVersion + "\"]");
                if (type == ProjectType.MOD && loader != null && !loader.isBlank() && !loader.equals("vanilla")) {
                    facets.add("[\"categories:" + loader + "\"]");
                }
                URI uri = API.resolve("search?limit=30&query=" + encode(query == null ? "" : query)
                        + "&index=downloads&facets=" + encode("[" + String.join(",", facets) + "]"));
                JsonNode root = get(uri);
                List<Project> projects = new ArrayList<>();
                for (JsonNode hit : root.path("hits")) {
                    projects.add(new Project(
                            hit.path("project_id").asText(), hit.path("slug").asText(),
                            hit.path("title").asText(), hit.path("description").asText(),
                            hit.path("author").asText(), hit.path("downloads").asLong(), hit.path("follows").asLong(),
                            hit.path("icon_url").asText(""), strings(hit.path("categories")), strings(hit.path("versions")),
                            hit.path("date_modified").asText(""), hit.path("client_side").asText("unknown"),
                            hit.path("server_side").asText("unknown"), type
                    ));
                }
                return projects;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<Path> downloadModpack(Project project, Path downloadsDirectory, String gameVersion) {
        if (project.type() != ProjectType.MODPACK) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("项目不是整合包"));
        }
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode selected = compatiblePrimaryFile(project, gameVersion, "");
                String fileName = selected.path("filename").asText("");
                String sha1 = selected.path("hashes").path("sha1").asText("");
                if (fileName.isBlank() || sha1.length() != 40) throw new IOException("整合包缺少文件名或 SHA-1");
                Path target = downloadsDirectory.resolve(fileName.endsWith(".mrpack") ? fileName : fileName + ".mrpack");
                String id = queue.enqueue("Modrinth 整合包 · " + project.title(), new DownloadService.DownloadRequest(
                        List.of(URI.create(selected.path("url").asText())), target, sha1,
                        DownloadService.ChecksumAlgorithm.SHA_1, selected.path("size").asLong(-1)
                ));
                return queue.completion(id).join();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<Path> install(Project project, GameInstance instance, String gameVersion, String loader) {
        return installRepair(project, instance, gameVersion, loader, null);
    }

    public CompletableFuture<Optional<Project>> findCompatibleMod(String idOrSlug, String gameVersion, String loader) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                Optional<Project> exact = project(idOrSlug);
                if (exact.isPresent() && hasCompatibleVersion(exact.get(), gameVersion, loader)) return exact;
                JsonNode root = get(API.resolve("search?limit=10&query=" + encode(idOrSlug)
                        + "&index=relevance&facets=" + encode("[[\"project_type:mod\"],[\"versions:"
                        + gameVersion + "\"],[\"categories:" + loader + "\"]]")));
                for (JsonNode hit : root.path("hits")) {
                    Project candidate = projectFromSearchHit(hit, ProjectType.MOD);
                    if ((candidate.slug().equalsIgnoreCase(idOrSlug) || candidate.title().equalsIgnoreCase(idOrSlug)
                            || candidate.id().equalsIgnoreCase(idOrSlug))
                            && hasCompatibleVersion(candidate, gameVersion, loader)) return Optional.of(candidate);
                }
                return Optional.empty();
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<Path> installRepair(
            Project project, GameInstance instance, String gameVersion, String loader, Path replacedFile
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                JsonNode selected = compatiblePrimaryFile(project, gameVersion, loader);
                String fileName = selected.path("filename").asText("");
                String sha1 = selected.path("hashes").path("sha1").asText("");
                if (fileName.isBlank() || sha1.length() != 40) throw new IOException("项目文件缺少文件名或 SHA-1");
                String folder = switch (project.type()) {
                    case MOD -> "mods";
                    case RESOURCEPACK -> "resourcepacks";
                    case SHADER -> "shaderpacks";
                    case MODPACK -> throw new IOException("整合包请下载 .mrpack 后使用导入功能");
                };
                Path folderPath = instance.directory().resolve("game").resolve(folder).toAbsolutePath().normalize();
                Files.createDirectories(folderPath);
                Path target = folderPath.resolve(fileName).normalize();
                if (!target.startsWith(folderPath)) throw new IOException("项目文件名包含不安全路径");
                Path temporary = target.resolveSibling(target.getFileName() + ".selene-download");
                String id = queue.enqueue("Modrinth · " + project.title(), new DownloadService.DownloadRequest(
                        List.of(URI.create(selected.path("url").asText())), temporary, sha1,
                        DownloadService.ChecksumAlgorithm.SHA_1, selected.path("size").asLong(-1)
                ));
                Path downloaded = queue.completion(id).join();
                Path backup = backupForReplacement(replacedFile, folderPath);
                try {
                    Files.move(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
                } catch (IOException failure) {
                    if (backup != null && Files.notExists(replacedFile)) Files.move(backup, replacedFile, StandardCopyOption.REPLACE_EXISTING);
                    throw failure;
                }
                return target;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private Optional<Project> project(String idOrSlug) throws IOException {
        try {
            JsonNode value = get(API.resolve("project/" + encode(idOrSlug)));
            String typeName = value.path("project_type").asText("");
            if (!"mod".equals(typeName)) return Optional.empty();
            return Optional.of(new Project(
                    value.path("id").asText(), value.path("slug").asText(), value.path("title").asText(),
                    value.path("description").asText(), value.path("team").asText(""), value.path("downloads").asLong(),
                    value.path("followers").asLong(), value.path("icon_url").asText(""), strings(value.path("categories")),
                    strings(value.path("game_versions")), value.path("updated").asText(""),
                    value.path("client_side").asText("unknown"), value.path("server_side").asText("unknown"), ProjectType.MOD
            ));
        } catch (HttpStatusException failure) {
            if (failure.statusCode() == 404) return Optional.empty();
            throw failure;
        }
    }

    private Project projectFromSearchHit(JsonNode hit, ProjectType type) {
        return new Project(
                hit.path("project_id").asText(), hit.path("slug").asText(), hit.path("title").asText(),
                hit.path("description").asText(), hit.path("author").asText(), hit.path("downloads").asLong(),
                hit.path("follows").asLong(), hit.path("icon_url").asText(""), strings(hit.path("categories")),
                strings(hit.path("versions")), hit.path("date_modified").asText(""),
                hit.path("client_side").asText("unknown"), hit.path("server_side").asText("unknown"), type
        );
    }

    private boolean hasCompatibleVersion(Project project, String gameVersion, String loader) {
        try {
            compatiblePrimaryFile(project, gameVersion, loader);
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private Path backupForReplacement(Path replacedFile, Path folderPath) throws IOException {
        if (replacedFile == null || !Files.isRegularFile(replacedFile)) return null;
        Path normalized = replacedFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(folderPath)) throw new IOException("拒绝替换 mods 目录之外的文件");
        Path backupDirectory = folderPath.resolve(".selene-backup");
        Files.createDirectories(backupDirectory);
        Path backup = backupDirectory.resolve(normalized.getFileName() + ".before-repair");
        Files.move(normalized, backup, StandardCopyOption.REPLACE_EXISTING);
        return backup;
    }

    private JsonNode compatiblePrimaryFile(Project project, String gameVersion, String loader) throws IOException {
        StringBuilder endpoint = new StringBuilder("project/").append(encode(project.id())).append("/version?");
        boolean hasParameter = false;
        if (gameVersion != null && !gameVersion.isBlank()) {
            endpoint.append("game_versions=").append(encode("[\"" + gameVersion + "\"]"));
            hasParameter = true;
        }
        if (loader != null && !loader.isBlank() && !loader.equals("vanilla") && project.type() == ProjectType.MOD) {
            if (hasParameter) endpoint.append('&');
            endpoint.append("loaders=").append(encode("[\"" + loader + "\"]"));
        }
        JsonNode versions = get(API.resolve(endpoint.toString()));
        if (!versions.isArray() || versions.isEmpty()) throw new IOException("未找到与实例兼容的项目版本");
        JsonNode selected = null;
        for (JsonNode file : versions.get(0).path("files")) {
            if (file.path("primary").asBoolean(false)) return file;
            if (selected == null) selected = file;
        }
        if (selected == null) throw new IOException("项目版本没有可下载文件");
        return selected;
    }

    private List<String> strings(JsonNode values) {
        List<String> result = new ArrayList<>();
        if (values.isArray()) values.forEach(value -> result.add(value.asText()));
        return List.copyOf(result);
    }

    private JsonNode get(URI uri) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json")
                .header("User-Agent", "SeleneLauncher/0.2 (resource-center)").GET().build();
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                byte[] buffer = new byte[16 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) {
                    if (output.size() + read > MAX_RESPONSE) throw new IOException("Modrinth 响应超过安全限制");
                    output.write(buffer, 0, read);
                }
                body = output.toByteArray();
            }
            if (response.statusCode() != 200) {
                throw new HttpStatusException(response.statusCode(), "Modrinth 返回 HTTP " + response.statusCode());
            }
            return mapper.readTree(body);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("Modrinth 请求被中断", failure);
        }
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static final class HttpStatusException extends IOException {
        private final int statusCode;

        private HttpStatusException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }

        private int statusCode() {
            return statusCode;
        }
    }

    public enum ProjectType {
        MOD("mod", "模组"), RESOURCEPACK("resourcepack", "资源包"), SHADER("shader", "光影"), MODPACK("modpack", "整合包");

        private final String apiName;
        private final String displayName;

        ProjectType(String apiName, String displayName) {
            this.apiName = apiName;
            this.displayName = displayName;
        }

        public String apiName() { return apiName; }
        public String displayName() { return displayName; }
        @Override public String toString() { return displayName; }
    }

    public record Project(
            String id, String slug, String title, String description, String author,
            long downloads, long follows, String iconUrl, List<String> categories, List<String> gameVersions,
            String dateModified, String clientSide, String serverSide, ProjectType type
    ) {
        public Project {
            categories = categories == null ? List.of() : List.copyOf(categories);
            gameVersions = gameVersions == null ? List.of() : List.copyOf(gameVersions);
        }

        public String latestGameVersions() {
            if (gameVersions.isEmpty()) return "版本信息未知";
            int from = Math.max(0, gameVersions.size() - 4);
            return String.join(" / ", gameVersions.subList(from, gameVersions.size()));
        }

        public String modifiedDate() {
            try {
                return java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd")
                        .withZone(java.time.ZoneId.systemDefault()).format(Instant.parse(dateModified));
            } catch (Exception ignored) {
                return "未知日期";
            }
        }

        @Override public String toString() { return title; }
    }
}
