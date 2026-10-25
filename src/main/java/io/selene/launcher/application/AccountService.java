package io.selene.launcher.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.selene.launcher.core.model.AccountProfile;
import io.selene.launcher.infra.AppLog;
import io.selene.launcher.infra.LocalCredentialVault;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Offline, Microsoft device-code and custom Yggdrasil account manager. */
public final class AccountService {
    private static final int MAX_RESPONSE = 4 * 1024 * 1024;
    private static final String MICROSOFT_DEVICE = "https://login.microsoftonline.com/consumers/oauth2/v2.0/devicecode";
    private static final String MICROSOFT_TOKEN = "https://login.microsoftonline.com/consumers/oauth2/v2.0/token";
    private static final String XBOX_AUTH = "https://user.auth.xboxlive.com/user/authenticate";
    private static final String XSTS_AUTH = "https://xsts.auth.xboxlive.com/xsts/authorize";
    private static final String MC_LOGIN = "https://api.minecraftservices.com/authentication/login_with_xbox";
    private static final String MC_ENTITLEMENTS = "https://api.minecraftservices.com/entitlements/mcstore";
    private static final String MC_PROFILE = "https://api.minecraftservices.com/minecraft/profile";

    private final Path accountFile;
    private final LocalCredentialVault vault;
    private final ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final List<AccountProfile> profiles = new ArrayList<>();
    private AccountProfile active;

    public AccountService(Path accountFile) {
        this.accountFile = accountFile;
        this.vault = new LocalCredentialVault(accountFile.resolveSibling("credential.key"));
        load();
        if (active == null) active = offline("Traveler");
    }

    public synchronized AccountProfile active() {
        return active;
    }

    public synchronized List<AccountProfile> profiles() {
        return List.copyOf(profiles);
    }

    public synchronized AccountProfile useOffline(String name) throws IOException {
        validatePlayerName(name);
        AccountProfile profile = offline(name.trim());
        upsert(profile);
        active = profile;
        save();
        return profile;
    }

    public CompletableFuture<AccountProfile> loginYggdrasil(
            URI apiRoot, String username, String password, Consumer<String> status
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                URI root = normalizeApiRoot(apiRoot);
                status.accept("正在连接外置登录服务器");
                ObjectNode request = mapper.createObjectNode();
                ObjectNode agent = request.putObject("agent");
                agent.put("name", "Minecraft");
                agent.put("version", 1);
                request.put("username", username);
                request.put("password", password);
                request.put("requestUser", true);
                request.put("clientToken", UUID.randomUUID().toString());
                JsonNode response = postJson(root.resolve("authserver/authenticate"), request, "");
                JsonNode selected = response.path("selectedProfile");
                if (!selected.isObject()) {
                    JsonNode available = response.path("availableProfiles");
                    if (available.isArray() && !available.isEmpty()) selected = available.get(0);
                }
                String token = required(response, "accessToken");
                String clientToken = response.path("clientToken").asText("");
                String name = required(selected, "name");
                String uuid = required(selected, "id");
                AccountProfile profile = new AccountProfile(
                        "yggdrasil-" + uuid, AccountProfile.Type.YGGDRASIL, name, uuid,
                        token, "", root.toString(), clientToken
                );
                synchronized (this) {
                    upsert(profile);
                    active = profile;
                    save();
                }
                status.accept("外置登录成功：" + name);
                return profile;
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<DeviceCode> requestMicrosoftDeviceCode(String clientId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (clientId == null || clientId.isBlank()) throw new IOException("请填写你为 Selene Launcher 申请的 Microsoft OAuth 客户端 ID");
                JsonNode node = postForm(URI.create(MICROSOFT_DEVICE), Map.of(
                        "client_id", clientId.trim(),
                        "scope", "XboxLive.signin offline_access"
                ));
                return new DeviceCode(
                        required(node, "device_code"), required(node, "user_code"),
                        required(node, "verification_uri"), node.path("message").asText(""),
                        node.path("expires_in").asInt(900), Math.max(5, node.path("interval").asInt(5)), clientId.trim()
                );
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    public CompletableFuture<AccountProfile> finishMicrosoftLogin(DeviceCode code, Consumer<String> status) {
        return CompletableFuture.supplyAsync(() -> {
            long deadline = System.nanoTime() + Duration.ofSeconds(code.expiresIn()).toNanos();
            int interval = code.interval();
            try {
                while (System.nanoTime() < deadline) {
                    JsonNode oauth;
                    try {
                        oauth = postForm(URI.create(MICROSOFT_TOKEN), Map.of(
                                "grant_type", "urn:ietf:params:oauth:grant-type:device_code",
                                "client_id", code.clientId(),
                                "device_code", code.deviceCode()
                        ));
                    } catch (OAuthPending pending) {
                        if (pending.slowDown()) interval += 5;
                        Thread.sleep(Duration.ofSeconds(interval));
                        continue;
                    }
                    status.accept("Microsoft 已授权，正在验证 Xbox Live");
                    String msaToken = required(oauth, "access_token");
                    String refresh = oauth.path("refresh_token").asText("");
                    XboxToken user = xboxUserToken(msaToken);
                    XboxToken xsts = xstsToken(user.token());
                    status.accept("正在验证 Minecraft Java Edition 所有权");
                    String minecraftToken = minecraftToken(xsts);
                    requireEntitlement(minecraftToken);
                    JsonNode profileNode = getJson(URI.create(MC_PROFILE), minecraftToken);
                    AccountProfile profile = new AccountProfile(
                            "microsoft-" + required(profileNode, "id"), AccountProfile.Type.MICROSOFT,
                            required(profileNode, "name"), required(profileNode, "id"), minecraftToken,
                            refresh, "", code.clientId()
                    );
                    synchronized (this) {
                        upsert(profile);
                        active = profile;
                        save();
                    }
                    status.accept("Microsoft 正版登录成功：" + profile.displayName());
                    return profile;
                }
                throw new IOException("Microsoft 设备码已过期，请重新登录");
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new CompletionException(new IOException("Microsoft 登录等待被中断", failure));
            } catch (IOException failure) {
                throw new CompletionException(failure);
            }
        });
    }

    private XboxToken xboxUserToken(String accessToken) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode properties = body.putObject("Properties");
        properties.put("AuthMethod", "RPS");
        properties.put("SiteName", "user.auth.xboxlive.com");
        properties.put("RpsTicket", "d=" + accessToken);
        body.put("RelyingParty", "http://auth.xboxlive.com");
        body.put("TokenType", "JWT");
        JsonNode response = postJson(URI.create(XBOX_AUTH), body, "");
        return parseXbox(response);
    }

    private XboxToken xstsToken(String userToken) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        ObjectNode properties = body.putObject("Properties");
        ArrayNode tokens = properties.putArray("UserTokens");
        tokens.add(userToken);
        properties.put("SandboxId", "RETAIL");
        body.put("RelyingParty", "rp://api.minecraftservices.com/");
        body.put("TokenType", "JWT");
        return parseXbox(postJson(URI.create(XSTS_AUTH), body, ""));
    }

    private XboxToken parseXbox(JsonNode response) throws IOException {
        String token = required(response, "Token");
        JsonNode claims = response.path("DisplayClaims").path("xui");
        String userHash = claims.isArray() && !claims.isEmpty() ? claims.get(0).path("uhs").asText("") : "";
        if (userHash.isBlank()) throw new IOException("Xbox Live 响应缺少用户哈希");
        return new XboxToken(token, userHash);
    }

    private String minecraftToken(XboxToken xsts) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("identityToken", "XBL3.0 x=" + xsts.userHash() + ";" + xsts.token());
        body.put("ensureLegacyEnabled", true);
        return required(postJson(URI.create(MC_LOGIN), body, ""), "access_token");
    }

    private void requireEntitlement(String token) throws IOException {
        JsonNode result = getJson(URI.create(MC_ENTITLEMENTS), token);
        if (!result.path("items").isArray() || result.path("items").isEmpty()) {
            throw new IOException("该 Microsoft 账号未检测到 Minecraft Java Edition 所有权");
        }
    }

    private JsonNode postForm(URI uri, Map<String, String> values) throws IOException {
        String form = values.entrySet().stream()
                .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .reduce((left, right) -> left + "&" + right).orElse("");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json").POST(HttpRequest.BodyPublishers.ofString(form)).build();
        JsonNode response = send(request);
        String error = response.path("error").asText("");
        if (!error.isBlank()) {
            if (error.equals("authorization_pending") || error.equals("slow_down")) throw new OAuthPending(error.equals("slow_down"));
            throw new IOException("Microsoft OAuth 返回：" + error + " " + response.path("error_description").asText(""));
        }
        return response;
    }

    private JsonNode postJson(URI uri, JsonNode body, String bearer) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()));
        if (!bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
        return send(builder.build());
    }

    private JsonNode getJson(URI uri, String bearer) throws IOException {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json").GET();
        if (!bearer.isBlank()) builder.header("Authorization", "Bearer " + bearer);
        return send(builder.build());
    }

    private JsonNode send(HttpRequest request) throws IOException {
        try {
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream input = response.body()) {
                body = readLimited(input);
            }
            JsonNode node = body.length == 0 ? mapper.createObjectNode() : mapper.readTree(body);
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                String detail = node.path("errorMessage").asText(node.path("error").asText(node.path("message").asText("")));
                if (request.uri().toString().equals(MICROSOFT_TOKEN)
                        && (detail.equals("authorization_pending") || detail.equals("slow_down"))) {
                    throw new OAuthPending(detail.equals("slow_down"));
                }
                throw new IOException(request.uri().getHost() + " 返回 HTTP " + response.statusCode()
                        + (detail.isBlank() ? "" : "：" + detail));
            }
            return node;
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IOException("账号服务请求被中断", failure);
        }
    }

    private byte[] readLimited(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = input.read(buffer)) >= 0) {
            if (output.size() + read > MAX_RESPONSE) throw new IOException("账号服务响应超过安全限制");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private URI normalizeApiRoot(URI value) throws IOException {
        if (value == null || !"https".equalsIgnoreCase(value.getScheme())) throw new IOException("外置登录地址必须使用 HTTPS");
        String text = value.toString();
        if (!text.endsWith("/")) text += "/";
        return URI.create(text);
    }

    private AccountProfile offline(String name) {
        String uuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)).toString().replace("-", "");
        return new AccountProfile("offline-" + name.toLowerCase(), AccountProfile.Type.OFFLINE, name, uuid, "0", "", "", "");
    }

    private void validatePlayerName(String name) {
        if (name == null || !name.trim().matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("离线档案名只能包含 3–16 位英文字母、数字或下划线");
        }
    }

    private synchronized void upsert(AccountProfile profile) {
        profiles.removeIf(item -> item.id().equals(profile.id()));
        profiles.add(profile);
    }

    private synchronized void load() {
        if (Files.notExists(accountFile)) return;
        try {
            JsonNode root = mapper.readTree(accountFile.toFile());
            String activeId = root.path("activeId").asText("");
            for (JsonNode node : root.path("accounts")) {
                AccountProfile profile = new AccountProfile(
                        required(node, "id"), AccountProfile.Type.valueOf(required(node, "type")),
                        required(node, "displayName"), node.path("uuid").asText(""),
                        vault.open(node.path("accessToken").asText("")), vault.open(node.path("refreshToken").asText("")),
                        node.path("authServer").asText(""), vault.open(node.path("clientToken").asText(""))
                );
                profiles.add(profile);
                if (profile.id().equals(activeId)) active = profile;
            }
        } catch (Exception failure) {
            profiles.clear();
            active = null;
            AppLog.warning("无法读取账号档案，将使用离线档案", failure);
        }
    }

    private synchronized void save() throws IOException {
        Files.createDirectories(accountFile.toAbsolutePath().normalize().getParent());
        ObjectNode root = mapper.createObjectNode();
        root.put("schemaVersion", 1);
        root.put("activeId", active.id());
        ArrayNode array = root.putArray("accounts");
        for (AccountProfile profile : profiles) {
            ObjectNode node = array.addObject();
            node.put("id", profile.id());
            node.put("type", profile.type().name());
            node.put("displayName", profile.displayName());
            node.put("uuid", profile.uuid());
            node.put("accessToken", vault.seal(profile.accessToken()));
            node.put("refreshToken", vault.seal(profile.refreshToken()));
            node.put("authServer", profile.authServer());
            node.put("clientToken", vault.seal(profile.clientToken()));
        }
        Path temporary = accountFile.resolveSibling(accountFile.getFileName() + ".tmp");
        mapper.writeValue(temporary.toFile(), root);
        try {
            Files.move(temporary, accountFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.move(temporary, accountFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private String required(JsonNode node, String field) throws IOException {
        String value = node.path(field).asText("");
        if (value.isBlank()) throw new IOException("账号服务响应缺少字段：" + field);
        return value;
    }

    private String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    public record DeviceCode(
            String deviceCode, String userCode, String verificationUri, String message,
            int expiresIn, int interval, String clientId
    ) {
    }

    private record XboxToken(String token, String userHash) {
    }

    private static final class OAuthPending extends IOException {
        private final boolean slowDown;

        private OAuthPending(boolean slowDown) {
            this.slowDown = slowDown;
        }

        private boolean slowDown() {
            return slowDown;
        }
    }
}
