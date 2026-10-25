package io.selene.launcher.application;

import io.selene.launcher.core.model.VersionCatalog;
import io.selene.launcher.core.port.VersionManifestProvider;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class VersionCatalogService {
    private final VersionManifestProvider provider;

    public VersionCatalogService(VersionManifestProvider provider) {
        this.provider = Objects.requireNonNull(provider, "provider");
    }

    public CompletableFuture<VersionCatalog> refresh() {
        return provider.fetchCatalog();
    }
}
