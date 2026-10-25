package io.selene.launcher.core.model;

import java.util.Objects;

public record AccountProfile(
        String id,
        Type type,
        String displayName,
        String uuid,
        String accessToken,
        String refreshToken,
        String authServer,
        String clientToken
) {
    public AccountProfile {
        id = Objects.requireNonNull(id, "id");
        type = Objects.requireNonNull(type, "type");
        displayName = Objects.requireNonNullElse(displayName, "Traveler");
        uuid = Objects.requireNonNullElse(uuid, "").replace("-", "");
        accessToken = Objects.requireNonNullElse(accessToken, "0");
        refreshToken = Objects.requireNonNullElse(refreshToken, "");
        authServer = Objects.requireNonNullElse(authServer, "");
        clientToken = Objects.requireNonNullElse(clientToken, "");
    }

    public boolean online() {
        return type != Type.OFFLINE;
    }

    public enum Type {
        OFFLINE("离线档案"),
        MICROSOFT("Microsoft 正版"),
        YGGDRASIL("外置登录");

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }
    }
}
