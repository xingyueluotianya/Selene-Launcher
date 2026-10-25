package io.selene.launcher.core.port;

import io.selene.launcher.core.model.LauncherSettings;

import java.io.IOException;

public interface SettingsRepository {
    LauncherSettings load() throws IOException;

    void save(LauncherSettings settings) throws IOException;
}
