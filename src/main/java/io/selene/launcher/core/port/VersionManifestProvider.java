package io.selene.launcher.core.port;

import io.selene.launcher.core.model.VersionCatalog;

import java.util.concurrent.CompletableFuture;

/** Supplies authoritative Java Edition version metadata without exposing a website API to the UI. */
public interface VersionManifestProvider {
    CompletableFuture<VersionCatalog> fetchCatalog();
}
