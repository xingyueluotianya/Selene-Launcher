package io.selene.launcher;

import io.selene.launcher.application.AccountService;
import io.selene.launcher.application.DiagnosticService;
import io.selene.launcher.application.DownloadQueueService;
import io.selene.launcher.application.FabricLoaderService;
import io.selene.launcher.application.FailureDiagnosticService;
import io.selene.launcher.application.GameProcessService;
import io.selene.launcher.application.InstallerLoaderService;
import io.selene.launcher.application.InstanceManagementService;
import io.selene.launcher.application.ModpackImportService;
import io.selene.launcher.application.ModRepairService;
import io.selene.launcher.application.ModrinthService;
import io.selene.launcher.application.VanillaGameService;
import io.selene.launcher.application.VersionCatalogService;
import io.selene.launcher.core.model.LauncherSettings;
import io.selene.launcher.infra.AdoptiumJavaRuntimeManager;
import io.selene.launcher.infra.AppLog;
import io.selene.launcher.infra.AppPaths;
import io.selene.launcher.infra.AuthlibInjectorManager;
import io.selene.launcher.infra.HttpDownloadService;
import io.selene.launcher.infra.JsonSettingsRepository;
import io.selene.launcher.infra.MojangVersionManifestProvider;
import io.selene.launcher.infra.VisualThemeRepository;
import io.selene.launcher.ui.FirstRunWizard;
import io.selene.launcher.ui.I18n;
import io.selene.launcher.ui.LauncherShell;
import javafx.animation.FadeTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.image.Image;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.IOException;
import java.util.Objects;

public final class SeleneLauncherApp extends Application {
    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        AppPaths bootstrapPaths = AppPaths.systemDefault();
        AppLog.initialize(bootstrapPaths.logsDirectory());
        AppLog.info("Selene Launcher 开始启动，Java " + Runtime.version() + "，系统 " + System.getProperty("os.name"));
        JsonSettingsRepository repository = new JsonSettingsRepository(
                bootstrapPaths.configDirectory().resolve("settings.json")
        );
        LauncherSettings settings = loadSettings(repository);
        I18n.setLanguage(settings.locale());
        if (!settings.onboardingCompleted()) {
            var completed = FirstRunWizard.show(stage, settings, bootstrapPaths);
            if (completed.isEmpty()) {
                Platform.exit();
                return;
            }
            settings = completed.get();
            try {
                repository.save(settings);
            } catch (IOException exception) {
                showStartupError("无法保存首次启动设置：" + exception.getMessage());
                Platform.exit();
                return;
            }
        }
        AppPaths paths = AppPaths.fromSettings(bootstrapPaths.root(), settings);
        try {
            paths.prepareStorage();
        } catch (IOException exception) {
            showStartupError("大型文件保存位置不可用，请重新连接磁盘或修正配置文件：\n" + exception.getMessage()
                    + "\n\n配置文件：" + bootstrapPaths.configDirectory().resolve("settings.json"));
            Platform.exit();
            return;
        }
        AppLog.info("存储位置：下载=" + paths.downloadsDirectory() + "，实例=" + paths.instancesDirectory()
                + "，公共游戏文件=" + paths.sharedMinecraftDirectory() + "，Java=" + paths.runtimesDirectory());
        VersionCatalogService versionCatalogService = new VersionCatalogService(
                new MojangVersionManifestProvider(paths.cacheDirectory().resolve("mojang/version_manifest_v2.json"))
        );
        HttpDownloadService httpDownloadService = new HttpDownloadService(settings.maxConcurrentDownloads());
        DownloadQueueService downloadQueueService = new DownloadQueueService(
                httpDownloadService,
                paths.configDirectory().resolve("download-queue.json")
        );
        VanillaGameService vanillaGameService = new VanillaGameService(
                downloadQueueService,
                paths.instancesDirectory(),
                paths.sharedMinecraftDirectory()
        );
        AdoptiumJavaRuntimeManager runtimeManager = new AdoptiumJavaRuntimeManager(
                downloadQueueService,
                paths.runtimesDirectory()
        );
        AccountService accountService = new AccountService(paths.configDirectory().resolve("accounts.json"));
        FabricLoaderService fabricLoaderService = new FabricLoaderService(
                vanillaGameService, downloadQueueService, paths.instancesDirectory(),
                paths.sharedMinecraftDirectory(), settings.preferMirror()
        );
        InstallerLoaderService installerLoaderService = new InstallerLoaderService(
                vanillaGameService, downloadQueueService, paths.instancesDirectory(),
                paths.sharedMinecraftDirectory(), paths.toolsDirectory(), runtimeManager, settings.preferMirror()
        );
        ModrinthService modrinthService = new ModrinthService(downloadQueueService);
        ModpackImportService modpackImportService = new ModpackImportService(downloadQueueService);
        InstanceManagementService instanceManagementService = new InstanceManagementService(vanillaGameService);
        FailureDiagnosticService failureDiagnosticService = new FailureDiagnosticService();
        ModRepairService modRepairService = new ModRepairService(vanillaGameService, modrinthService);
        GameProcessService gameProcessService = new GameProcessService(
                vanillaGameService,
                runtimeManager,
                new AuthlibInjectorManager(downloadQueueService, paths.toolsDirectory())
        );
        VisualThemeRepository visualThemeRepository = new VisualThemeRepository(
                paths.configDirectory().resolve("visual-theme.json")
        );
        LauncherShell shell = new LauncherShell(
                settings,
                repository,
                versionCatalogService,
                downloadQueueService,
                vanillaGameService,
                gameProcessService,
                visualThemeRepository,
                new DiagnosticService(paths),
                accountService,
                fabricLoaderService,
                installerLoaderService,
                modrinthService,
                modpackImportService,
                instanceManagementService,
                failureDiagnosticService,
                modRepairService,
                paths
        );
        StackPane root = new StackPane(shell.view());
        stage.setOnCloseRequest(event -> {
            AppLog.info("Selene Launcher 正在退出");
            httpDownloadService.close();
        });

        var workArea = Screen.getPrimary().getVisualBounds();
        double initialWidth = Math.min(1280, workArea.getWidth());
        double initialHeight = Math.min(800, workArea.getHeight());
        Scene scene = new Scene(root, initialWidth, initialHeight, Color.web("#090b13"));
        scene.getStylesheets().add(Objects.requireNonNull(
                SeleneLauncherApp.class.getResource("/io/selene/launcher/selene.css")
        ).toExternalForm());

        stage.setTitle("Selene Launcher · Beta");
        stage.getIcons().add(new Image(Objects.requireNonNull(
                SeleneLauncherApp.class.getResourceAsStream("/io/selene/launcher/selene-icon.png")
        )));
        stage.setMinWidth(Math.min(1024, workArea.getWidth()));
        stage.setMinHeight(Math.min(680, workArea.getHeight()));
        stage.setScene(scene);
        stage.setX(workArea.getMinX() + Math.max(0, (workArea.getWidth() - initialWidth) / 2));
        stage.setY(workArea.getMinY() + Math.max(0, (workArea.getHeight() - initialHeight) / 2));
        stage.show();
        StartupFailureReporter.markStarted();
        AppLog.info("Selene Launcher 主窗口已显示");
        stage.setAlwaysOnTop(true);
        stage.toFront();
        stage.requestFocus();
        PauseTransition releaseForeground = new PauseTransition(Duration.millis(850));
        releaseForeground.setOnFinished(event -> stage.setAlwaysOnTop(false));
        releaseForeground.play();

        if (settings.playIntro()) {
            playIntro(shell.view());
        }
    }

    private void showStartupError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, I18n.text(message));
        alert.setHeaderText(I18n.text("Selene Launcher 无法初始化存储目录"));
        alert.showAndWait();
    }

    private LauncherSettings loadSettings(JsonSettingsRepository repository) {
        try {
            return repository.load();
        } catch (IOException exception) {
            AppLog.warning("无法读取 Selene Launcher 设置，将使用默认值", exception);
            return LauncherSettings.defaults();
        }
    }

    private void playIntro(javafx.scene.Node node) {
        node.setOpacity(0);
        node.setScaleX(1.025);
        node.setScaleY(1.025);

        FadeTransition fade = new FadeTransition(Duration.millis(720), node);
        fade.setFromValue(0);
        fade.setToValue(1);
        ScaleTransition scale = new ScaleTransition(Duration.millis(920), node);
        scale.setToX(1);
        scale.setToY(1);
        ParallelTransition intro = new ParallelTransition(fade, scale);
        intro.setOnFinished(event -> Platform.runLater(node::requestFocus));
        intro.play();
    }
}
