package io.selene.launcher.core.model;

import java.net.URI;
import java.time.Instant;
import java.util.Locale;

public record GameVersion(
        String id,
        Type type,
        Instant releaseTime,
        URI metadataUri,
        String sha1,
        int complianceLevel
) {
    public GameVersion {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("Version id is required");
        }
        if (type == null || releaseTime == null || metadataUri == null) {
            throw new IllegalArgumentException("Version metadata is incomplete");
        }
    }

    public enum Type {
        RELEASE("正式版"),
        SNAPSHOT("快照版"),
        OLD_BETA("旧测试版"),
        OLD_ALPHA("旧 Alpha"),
        UNKNOWN("其他");

        private final String displayName;

        Type(String displayName) {
            this.displayName = displayName;
        }

        public String displayName() {
            return displayName;
        }

        public static Type fromManifest(String value) {
            if (value == null) {
                return UNKNOWN;
            }
            try {
                return valueOf(value.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException ignored) {
                return UNKNOWN;
            }
        }
    }
}
