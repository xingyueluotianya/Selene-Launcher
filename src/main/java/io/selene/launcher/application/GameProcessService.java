package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.selene.launcher.core.model.AccountProfile;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.InstanceSettings;
import io.selene.launcher.core.model.RuntimeInfo;
import io.selene.launcher.core.port.JavaRuntimeManager;
import io.selene.launcher.infra.AuthlibInjectorManager;
import io.selene.launcher.infra.AppLog;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Builds Mojang launch arguments and starts an installed vanilla instance. */
public final class GameProcessService {
    private final VanillaGameService gameService;
    private final JavaRuntimeManager runtimeManager;
    private final AuthlibInjectorManager authlibInjectorManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public GameProcessService(
            VanillaGameService gameService,
            JavaRuntimeManager runtimeManager,
            AuthlibInjectorManager authlibInjectorManager
    ) {
        this.gameService = gameService;
        this.runtimeManager = runtimeManager;
        this.authlibInjectorManager = authlibInjectorManager;
    }

    public CompletableFuture<Process> launch(
            GameInstance instance,
            String playerName,
            Consumer<String> statusListener,
            Consumer<String> logListener
    ) {
        String safePlayer = normalizePlayerName(playerName);
        String uuid = offlineUuid(safePlayer);
        return launch(instance, new AccountProfile(
                "offline-" + safePlayer.toLowerCase(Locale.ROOT), AccountProfile.Type.OFFLINE,
                safePlayer, uuid, "0", "", "", ""
        ), statusListener, logListener);
    }

    public CompletableFuture<Process> launch(
            GameInstance instance,
            AccountProfile account,
            Consumer<String> statusListener,
            Consumer<String> logListener
    ) {
        return launch(instance, account, InstanceSettings.defaults(), statusListener, logListener);
    }

    public CompletableFuture<Process> launch(
            GameInstance instance,
            AccountProfile account,
            InstanceSettings settings,
            Consumer<String> statusListener,
            Consumer<String> logListener
    ) {
        Consumer<String> status = statusListener == null ? ignored -> { } : statusListener;
        return CompletableFuture.supplyAsync(() -> {
            try {
                status.accept("正在解析 Java " + instance.requiredJavaMajor() + " 运行时");
                RuntimeInfo runtime = runtimeManager.resolve(instance.requiredJavaMajor()).join();
                Path executable = resolveJavaExecutable(settings, runtime);
                status.accept("正在生成启动参数");
                JsonNode metadata = objectMapper.readTree(gameService.versionMetadata(instance.versionId()).toFile());
                Path instanceConfig = instance.directory().resolve("instance.json");
                JsonNode installed = objectMapper.readTree(instanceConfig.toFile());
                Path clientJar = Path.of(installed.path("clientJar").asText());
                String assetIndex = installed.path("assetIndex").asText();
                Path gameDirectory = instance.directory().resolve("game");
                Path nativeDirectory = instance.directory().resolve("natives");
                Files.createDirectories(gameDirectory);
                Path logFile = instance.directory().resolve("logs/latest.log");
                Files.createDirectories(logFile.getParent());

                String classpath = buildClasspath(metadata, clientJar);
                Map<String, String> substitutions = substitutions(
                        instance, account, classpath, nativeDirectory, gameDirectory, assetIndex, metadata, settings
                );
                List<String> command = new ArrayList<>();
                command.add(executable.toString());
                command.add("-Xms" + settings.minimumMemoryMb() + "M");
                command.add("-Xmx" + settings.maximumMemoryMb() + "M");
                command.addAll(splitCustomArguments(settings.jvmArguments(), substitutions));
                if (account.type() == AccountProfile.Type.YGGDRASIL) {
                    status.accept("正在准备外置登录运行组件");
                    Path injector = authlibInjectorManager.resolve().join();
                    command.add("-javaagent:" + injector + "=" + account.authServer());
                    command.add("-Dauthlibinjector.side=client");
                }
                command.addAll(resolveArguments(metadata.path("arguments").path("jvm"), substitutions));
                if (metadata.path("arguments").path("jvm").isMissingNode()) {
                    command.add("-Djava.library.path=" + nativeDirectory);
                    command.add("-cp");
                    command.add(classpath);
                }
                addLoggingArgument(metadata, substitutions, command);
                command.add(instance.mainClass());
                JsonNode modernGameArgs = metadata.path("arguments").path("game");
                if (modernGameArgs.isArray()) {
                    command.addAll(resolveArguments(modernGameArgs, substitutions));
                } else {
                    command.addAll(splitLegacy(metadata.path("minecraftArguments").asText(""), substitutions));
                }
                command.add("--width");
                command.add(Integer.toString(settings.width()));
                command.add("--height");
                command.add(Integer.toString(settings.height()));
                if (settings.fullscreen()) command.add("--fullscreen");
                addQuickServerArguments(command, settings.quickServerAddress());
                command.addAll(splitCustomArguments(settings.gameArguments(), substitutions));

                status.accept("正在启动 " + instance.name());
                ProcessBuilder builder = new ProcessBuilder(command)
                        .directory(gameDirectory.toFile())
                        .redirectErrorStream(true);
                AppLog.info("启动游戏实例 " + instance.id() + "，账号类型 " + account.type()
                        + "，Java " + executable + "，内存 " + settings.minimumMemoryMb() + "-"
                        + settings.maximumMemoryMb() + " MiB");
                Process process = builder.start();
                CompletableFuture.runAsync(() -> captureOutput(process, logFile, logListener));
                status.accept("游戏已启动，进程号 " + process.pid());
                return process;
            } catch (IOException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        });
    }

    private List<String> resolveArguments(JsonNode arguments, Map<String, String> substitutions) {
        List<String> result = new ArrayList<>();
        if (!arguments.isArray()) return result;
        for (JsonNode argument : arguments) {
            if (argument.isTextual()) {
                result.add(expand(argument.asText(), substitutions));
            } else if (argument.isObject() && rulesAllow(argument.path("rules"))) {
                JsonNode value = argument.path("value");
                if (value.isTextual()) result.add(expand(value.asText(), substitutions));
                if (value.isArray()) value.forEach(item -> result.add(expand(item.asText(), substitutions)));
            }
        }
        return result;
    }

    private String buildClasspath(JsonNode metadata, Path clientJar) {
        List<String> entries = new ArrayList<>();
        for (JsonNode library : metadata.path("libraries")) {
            if (!rulesAllow(library.path("rules"))) continue;
            String relative = library.path("downloads").path("artifact").path("path").asText("");
            if (relative.isBlank()) relative = mavenPath(library.path("name").asText(""));
            if (!relative.isBlank()) entries.add(gameService.librariesDirectory().resolve(relative).toString());
        }
        entries.add(clientJar.toString());
        return String.join(System.getProperty("path.separator"), entries);
    }

    private String mavenPath(String coordinate) {
        if (coordinate.isBlank()) return "";
        String[] extensionParts = coordinate.split("@", 2);
        String extension = extensionParts.length == 2 ? extensionParts[1] : "jar";
        String[] parts = extensionParts[0].split(":");
        if (parts.length < 3 || parts.length > 4) return "";
        String classifier = parts.length == 4 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                + parts[1] + "-" + parts[2] + classifier + "." + extension;
    }

    private Map<String, String> substitutions(
            GameInstance instance,
            AccountProfile account,
            String classpath,
            Path natives,
            Path gameDirectory,
            String assetIndex,
            JsonNode metadata,
            InstanceSettings settings
    ) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("${auth_player_name}", account.displayName());
        values.put("${version_name}", instance.versionId());
        values.put("${game_directory}", gameDirectory.toString());
        values.put("${assets_root}", gameService.assetsDirectory().toString());
        values.put("${assets_index_name}", assetIndex);
        values.put("${auth_uuid}", account.uuid());
        values.put("${auth_access_token}", account.accessToken());
        values.put("${auth_session}", account.accessToken());
        values.put("${clientid}", account.type() == AccountProfile.Type.MICROSOFT ? account.clientToken() : "");
        values.put("${auth_xuid}", "");
        values.put("${user_type}", account.type() == AccountProfile.Type.MICROSOFT ? "msa" : "legacy");
        values.put("${version_type}", metadata.path("type").asText("release"));
        values.put("${resolution_width}", Integer.toString(settings.width()));
        values.put("${resolution_height}", Integer.toString(settings.height()));
        values.put("${natives_directory}", natives.toString());
        values.put("${launcher_name}", "SeleneLauncher");
        values.put("${launcher_version}", "0.2.0-beta.9");
        values.put("${classpath}", classpath);
        values.put("${classpath_separator}", System.getProperty("path.separator"));
        values.put("${library_directory}", gameService.librariesDirectory().toString());
        return values;
    }

    private void addLoggingArgument(JsonNode metadata, Map<String, String> substitutions, List<String> command) {
        JsonNode logging = metadata.path("logging").path("client");
        String argument = logging.path("argument").asText("");
        String fileId = logging.path("file").path("id").asText("");
        if (!argument.isBlank() && !fileId.isBlank()) {
            Map<String, String> loggingValues = new LinkedHashMap<>(substitutions);
            loggingValues.put("${path}", gameService.assetsDirectory().resolve("log_configs").resolve(fileId).toString());
            command.add(expand(argument, loggingValues));
        }
    }

    private List<String> splitLegacy(String value, Map<String, String> substitutions) {
        List<String> result = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(character) && !quoted) {
                if (!token.isEmpty()) {
                    result.add(expand(token.toString(), substitutions));
                    token.setLength(0);
                }
            } else {
                token.append(character);
            }
        }
        if (!token.isEmpty()) result.add(expand(token.toString(), substitutions));
        return result;
    }

    private String expand(String value, Map<String, String> substitutions) {
        String result = value;
        for (Map.Entry<String, String> entry : substitutions.entrySet()) {
            result = result.replace(entry.getKey(), entry.getValue());
        }
        return result;
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
        return !rule.path("features").isObject() || rule.path("features").isEmpty();
    }

    private String currentOs() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac")) return "osx";
        return "linux";
    }

    private String normalizeArch(String arch) {
        String value = arch.toLowerCase(Locale.ROOT);
        if (value.equals("x86") || value.equals("i386") || value.equals("i686")) return "x86";
        if (value.contains("aarch64") || value.contains("arm64")) return "arm64";
        return "x86_64";
    }

    private Path resolveJavaExecutable(InstanceSettings settings, RuntimeInfo managed) throws IOException {
        if (settings.javaExecutable().isBlank()) return managed.executable();
        Path custom = Path.of(settings.javaExecutable()).toAbsolutePath().normalize();
        if (!Files.isRegularFile(custom)) throw new IOException("自定义 Java 不存在：" + custom);
        return custom;
    }

    private List<String> splitCustomArguments(String value, Map<String, String> substitutions) {
        if (value == null || value.isBlank()) return List.of();
        return splitLegacy(value, substitutions);
    }

    private void addQuickServerArguments(List<String> command, String address) {
        if (address == null || address.isBlank()) return;
        String value = address.trim();
        String host = value;
        String port = "";
        if (value.startsWith("[") && value.contains("]")) {
            int end = value.indexOf(']');
            host = value.substring(1, end);
            if (end + 1 < value.length() && value.charAt(end + 1) == ':') port = value.substring(end + 2);
        } else {
            int separator = value.lastIndexOf(':');
            if (separator > 0 && value.indexOf(':') == separator) {
                host = value.substring(0, separator);
                port = value.substring(separator + 1);
            }
        }
        command.add("--server");
        command.add(host);
        if (port.matches("\\d{1,5}")) {
            int number = Integer.parseInt(port);
            if (number > 0 && number <= 65535) {
                command.add("--port");
                command.add(port);
            }
        }
    }

    private String normalizePlayerName(String playerName) {
        String value = playerName == null ? "Traveler" : playerName.trim();
        if (!value.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("离线档案名只能包含 3–16 位英文字母、数字或下划线");
        }
        return value;
    }

    private String offlineUuid(String player) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + player).getBytes(StandardCharsets.UTF_8))
                .toString().replace("-", "");
    }

    private void captureOutput(Process process, Path logFile, Consumer<String> listener) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
             var output = Files.newBufferedWriter(logFile, StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            output.write("Selene Launcher 游戏日志 · " + Instant.now());
            output.newLine();
            String line;
            while ((line = reader.readLine()) != null) {
                output.write(line);
                output.newLine();
                output.flush();
                if (listener != null) listener.accept(line);
            }
            output.write("游戏进程已退出，代码 " + process.waitFor());
            output.newLine();
        } catch (IOException | InterruptedException exception) {
            if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            AppLog.warning("捕获游戏输出失败：" + logFile, exception);
        }
    }
}
