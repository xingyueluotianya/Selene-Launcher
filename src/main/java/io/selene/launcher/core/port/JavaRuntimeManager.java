package io.selene.launcher.core.port;

import io.selene.launcher.core.model.RuntimeInfo;

import java.util.concurrent.CompletableFuture;

/** Resolves a compatible runtime without mutating the system Java installation. */
public interface JavaRuntimeManager {
    CompletableFuture<RuntimeInfo> resolve(int requiredMajorVersion);
}
