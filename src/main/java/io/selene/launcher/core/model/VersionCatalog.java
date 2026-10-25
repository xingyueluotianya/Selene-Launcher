package io.selene.launcher.core.model;

import java.time.Instant;
import java.util.List;

public record VersionCatalog(
        String latestRelease,
        String latestSnapshot,
        List<GameVersion> versions,
        Instant fetchedAt,
        Origin origin
) {
    public VersionCatalog {
        latestRelease = latestRelease == null ? "" : latestRelease;
        latestSnapshot = latestSnapshot == null ? "" : latestSnapshot;
        versions = List.copyOf(versions);
        if (fetchedAt == null || origin == null) {
            throw new IllegalArgumentException("Catalog source metadata is required");
        }
    }

    public enum Origin {
        NETWORK,
        CACHE
    }
}
