package io.selene.launcher.ui;

import io.selene.launcher.application.AccountService;
import io.selene.launcher.application.FabricLoaderService;
import io.selene.launcher.application.FailureDiagnosticService;
import io.selene.launcher.application.GameProcessService;
import io.selene.launcher.application.InstallerLoaderService;
import io.selene.launcher.application.VanillaGameService;
import io.selene.launcher.application.VersionCatalogService;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.GameVersion;
import io.selene.launcher.core.model.LoaderVersion;
import io.selene.launcher.core.model.VersionCatalog;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/** Java Edition catalog, vanilla installation and launch UI. */
public final class GameLibraryPage {
    private final VersionCatalogService catalogService;
    private final VanillaGameService gameService;
    private final GameProcessService processService;
    private final AccountService accountService;
    private final FabricLoaderService fabricLoaderService;
    private final InstallerLoaderService installerLoaderService;
    private final FailureDiagnosticService failureDiagnostics;
    private final Consumer<String> errorHandler;
    private final Runnable showDownloads;
    private final VBox root = new VBox(15);
    private final Label catalogStatus = new Label("尚未同步版本清单");
    private final Label latestStatus = new Label("正在准备 Mojang 官方版本源…");
    private final Label operationStatus = new Label("选择版本后可安装原版游戏");
    private final ProgressIndicator progress = new ProgressIndicator();
    private final Button refresh = new Button("刷新清单");
    private final Button install = new Button("安装所选版本");
    private final Button launch = new Button("启动游戏");
    private final Button downloads = new Button("查看下载队列");
    private final TextField search = new TextField();
    private final Label activeAccount = new Label();
    private final ComboBox<String> loader = new ComboBox<>();
    private final ComboBox<LoaderVersion> loaderVersion = new ComboBox<>();
    private final Label loaderStatus = new Label("选择加载器后自动读取版本");
    private final Button refreshLoaders = new Button("刷新 Loader");
    private final ComboBox<String> typeFilter = new ComboBox<>();
    private final ListView<GameVersion> versionList = new ListView<>();
    private final ObservableList<GameVersion> sourceVersions = FXCollections.observableArrayList();
    private final FilteredList<GameVersion> filteredVersions = new FilteredList<>(sourceVersions);
    private boolean loaded;
    private boolean busy;
    private boolean loadingLoaders;
    private long loaderRequest;

    public GameLibraryPage(
            VersionCatalogService catalogService,
            VanillaGameService gameService,
            GameProcessService processService,
            AccountService accountService,
            FabricLoaderService fabricLoaderService,
            InstallerLoaderService installerLoaderService,
            FailureDiagnosticService failureDiagnostics,
            Consumer<String> errorHandler,
            Runnable showDownloads
    ) {
        this.catalogService = Objects.requireNonNull(catalogService, "catalogService");
        this.gameService = Objects.requireNonNull(gameService, "gameService");
        this.processService = Objects.requireNonNull(processService, "processService");
        this.accountService = Objects.requireNonNull(accountService, "accountService");
        this.fabricLoaderService = Objects.requireNonNull(fabricLoaderService, "fabricLoaderService");
        this.installerLoaderService = Objects.requireNonNull(installerLoaderService, "installerLoaderService");
        this.failureDiagnostics = Objects.requireNonNull(failureDiagnostics, "failureDiagnostics");
        this.errorHandler = Objects.requireNonNull(errorHandler, "errorHandler");
        this.showDownloads = Objects.requireNonNull(showDownloads, "showDownloads");
        configureView();
    }

    public Node view() {
        loader.setButtonCell(localizedStringCell());
        typeFilter.setButtonCell(localizedStringCell());
        if (!loaded) {
            loaded = true;
            refresh();
        }
        activeAccount.setText(accountService.active().displayName() + " · " + accountService.active().type().displayName());
        updateActions();
        return root;
    }

    private void configureView() {
        root.getStyleClass().add("page");
        VBox heading = new VBox(3);
        Label eyebrow = new Label("GAME LIBRARY");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("游戏库");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("浏览 Mojang 官方版本，安装经过校验的原版实例，并使用离线档案启动。");
        subtitle.getStyleClass().add("page-subtitle");
        heading.getChildren().addAll(eyebrow, title, subtitle);

        VBox summary = new VBox(7);
        summary.getStyleClass().add("catalog-summary");
        summary.setPadding(new Insets(14, 18, 14, 18));
        latestStatus.getStyleClass().add("settings-title");
        catalogStatus.getStyleClass().add("page-subtitle");
        operationStatus.getStyleClass().add("save-status");
        summary.getChildren().addAll(latestStatus, catalogStatus, operationStatus);

        search.setPromptText("搜索版本，例如 1.21.5");
        search.getStyleClass().add("catalog-search");
        search.setPrefWidth(245);
        typeFilter.getItems().addAll("全部版本", "正式版", "快照版", "历史版本");
        typeFilter.setCellFactory(ignored -> localizedStringCell());
        typeFilter.setButtonCell(localizedStringCell());
        typeFilter.getSelectionModel().selectFirst();
        refresh.getStyleClass().add("ghost-button");
        refresh.setOnAction(event -> refresh());
        progress.setPrefSize(24, 24);
        progress.setVisible(false);
        progress.setManaged(false);
        Region filterSpacer = new Region();
        HBox.setHgrow(filterSpacer, Priority.ALWAYS);
        HBox filters = new HBox(10, search, typeFilter, filterSpacer, progress, refresh);
        filters.setAlignment(Pos.CENTER_LEFT);

        versionList.setItems(filteredVersions);
        versionList.setPlaceholder(new Label("没有符合条件的版本"));
        versionList.setCellFactory(ignored -> new VersionCell());
        versionList.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> {
            refreshLoaderVersions();
            updateActions();
        });
        VBox.setVgrow(versionList, Priority.ALWAYS);
        versionList.setMinHeight(240);

        activeAccount.getStyleClass().add("version-type");
        loader.getItems().addAll("原版", "Fabric", "Forge", "NeoForge");
        loader.setCellFactory(ignored -> localizedStringCell());
        loader.setButtonCell(localizedStringCell());
        loader.getSelectionModel().selectFirst();
        loader.setPrefWidth(105);
        loaderVersion.setPromptText("点击选择 Loader 版本");
        loaderVersion.setPrefWidth(205);
        loaderVersion.setDisable(true);
        loaderStatus.getStyleClass().add("version-date");
        loaderStatus.setMinWidth(150);
        refreshLoaders.getStyleClass().add("ghost-button");
        refreshLoaders.setOnAction(event -> refreshLoaderVersions());
        refreshLoaders.setDisable(true);
        loader.valueProperty().addListener((observable, before, value) -> {
            refreshLoaderVersions();
            updateActions();
        });
        loaderVersion.valueProperty().addListener((observable, before, value) -> updateActions());
        install.getStyleClass().add("primary-button");
        install.setOnAction(event -> installSelected());
        launch.getStyleClass().add("launch-button");
        launch.setOnAction(event -> launchSelected());
        downloads.getStyleClass().add("ghost-button");
        downloads.setOnAction(event -> showDownloads.run());
        HBox actions = new HBox(10, new Label("启动账号"), activeAccount, loader, loaderVersion,
                refreshLoaders, install, launch, downloads);
        actions.setAlignment(Pos.CENTER_LEFT);
        HBox loaderInfo = new HBox(8, new Label("Loader 状态"), loaderStatus);
        loaderInfo.setAlignment(Pos.CENTER_LEFT);

        search.textProperty().addListener((observable, before, after) -> updateFilter());
        typeFilter.valueProperty().addListener((observable, before, after) -> updateFilter());
        root.getChildren().addAll(heading, summary, filters, versionList, loaderInfo, actions);
        I18n.localize(root);
    }

    private void refresh() {
        setLoading(true);
        catalogStatus.setText("正在连接 Mojang 官方版本清单…");
        catalogService.refresh().whenComplete((catalog, failure) -> Platform.runLater(() -> {
            setLoading(false);
            if (failure != null) {
                catalogStatus.setText("同步失败，可稍后重试");
                errorHandler.accept("无法读取 Java Edition 版本清单：" + rootCauseMessage(failure));
                return;
            }
            apply(catalog);
        }));
    }

    private void installSelected() {
        GameVersion selected = versionList.getSelectionModel().getSelectedItem();
        if (selected == null || busy) return;
        busy = true;
        updateActions();
        operationStatus.setText("正在创建安装计划…");
        var listener = (java.util.function.Consumer<String>) message ->
                Platform.runLater(() -> operationStatus.setText(message));
        LoaderVersion selectedLoaderVersion = loaderVersion.getValue();
        String version = selectedLoaderVersion == null ? "" : selectedLoaderVersion.version();
        java.util.concurrent.CompletableFuture<GameInstance> installation = switch (loader.getValue()) {
            case "Fabric" -> fabricLoaderService.install(selected, version, listener);
            case "Forge" -> installerLoaderService.install(
                    InstallerLoaderService.Loader.FORGE, selected, version, listener);
            case "NeoForge" -> installerLoaderService.install(
                    InstallerLoaderService.Loader.NEOFORGE, selected, version, listener);
            default -> gameService.install(selected, listener);
        };
        installation.whenComplete((instance, failure) -> Platform.runLater(() -> {
                    busy = false;
                    updateActions();
                    versionList.refresh();
                    if (failure != null) {
                        FailureDiagnosticService.Diagnosis diagnosis = failureDiagnostics.diagnoseInstallation(
                                loader.getValue() + " Minecraft " + selected.id(), failure);
                        operationStatus.setText("安装失败 · 自动诊断：" + diagnosis.title());
                        errorHandler.accept("安装失败自动诊断\n\n" + diagnosis.displayText());
                    } else {
                        operationStatus.setText(instance.name() + " 已安装，可以启动");
                    }
                }));
    }

    private void launchSelected() {
        GameVersion selected = versionList.getSelectionModel().getSelectedItem();
        if (selected == null || busy) return;
        try {
            GameInstance instance = selectedInstalledInstance(selected);
            busy = true;
            updateActions();
            processService.launch(instance, accountService.active(),
                            message -> Platform.runLater(() -> operationStatus.setText(message)),
                            ignored -> { })
                    .whenComplete((process, failure) -> Platform.runLater(() -> {
                        busy = false;
                        updateActions();
                        if (failure != null) errorHandler.accept("游戏启动失败：" + rootCauseMessage(failure));
                    }));
        } catch (Exception exception) {
            errorHandler.accept("无法读取实例：" + exception.getMessage());
        }
    }

    private void apply(VersionCatalog catalog) {
        List<GameVersion> versions = catalog.versions();
        sourceVersions.setAll(versions);
        updateFilter();
        latestStatus.setText("最新正式版  " + catalog.latestRelease() + "    ·    最新快照  " + catalog.latestSnapshot());
        String origin = catalog.origin() == VersionCatalog.Origin.NETWORK ? "Mojang 官方网络数据" : "本地缓存（网络暂不可用）";
        catalogStatus.setText(origin + " · 共 " + versions.size() + " 个版本 · 已安装 " + gameService.installedInstances().size() + " 个实例");
    }

    private void updateFilter() {
        String query = search.getText() == null ? "" : search.getText().trim().toLowerCase(Locale.ROOT);
        String selectedType = typeFilter.getValue();
        filteredVersions.setPredicate(version -> {
            if (!version.id().toLowerCase(Locale.ROOT).contains(query)) return false;
            if (selectedType == null || selectedType.equals("全部版本")) return true;
            return switch (selectedType) {
                case "正式版" -> version.type() == GameVersion.Type.RELEASE;
                case "快照版" -> version.type() == GameVersion.Type.SNAPSHOT;
                case "历史版本" -> version.type() == GameVersion.Type.OLD_ALPHA || version.type() == GameVersion.Type.OLD_BETA;
                default -> true;
            };
        });
    }

    private void refreshLoaderVersions() {
        GameVersion selected = versionList.getSelectionModel().getSelectedItem();
        String selectedLoader = loader.getValue();
        long request = ++loaderRequest;
        loaderVersion.getItems().clear();
        loaderVersion.getSelectionModel().clearSelection();
        boolean vanilla = selectedLoader == null || "原版".equals(selectedLoader);
        loaderVersion.setDisable(vanilla || selected == null);
        refreshLoaders.setDisable(vanilla || selected == null);
        if (vanilla) {
            loadingLoaders = false;
            loaderStatus.setText("原版无需 Loader");
            updateActions();
            return;
        }
        if (selected == null) {
            loadingLoaders = false;
            loaderStatus.setText("请先选择 Minecraft 版本");
            updateActions();
            return;
        }
        loadingLoaders = true;
        loaderVersion.setDisable(true);
        refreshLoaders.setDisable(true);
        loaderStatus.setText("正在读取 " + selectedLoader + " 官方版本目录…");
        var future = switch (selectedLoader) {
            case "Fabric" -> fabricLoaderService.versions(selected.id());
            case "Forge" -> installerLoaderService.versions(InstallerLoaderService.Loader.FORGE, selected.id());
            case "NeoForge" -> installerLoaderService.versions(InstallerLoaderService.Loader.NEOFORGE, selected.id());
            default -> java.util.concurrent.CompletableFuture.<List<LoaderVersion>>completedFuture(List.of());
        };
        future.whenComplete((versions, failure) -> Platform.runLater(() -> {
            if (request != loaderRequest) return;
            loadingLoaders = false;
            refreshLoaders.setDisable(false);
            if (failure != null) {
                loaderVersion.setDisable(true);
                loaderStatus.setText("版本目录读取失败，可点击刷新");
            } else {
                loaderVersion.getItems().setAll(versions);
                loaderVersion.setDisable(false);
                if (!versions.isEmpty()) loaderVersion.getSelectionModel().selectFirst();
                long stable = versions.stream().filter(LoaderVersion::stable).count();
                loaderStatus.setText("可选 " + versions.size() + " 个版本 · " + stable + " 个稳定/正式版");
            }
            updateActions();
        }));
        updateActions();
    }

    private void updateActions() {
        GameVersion selected = versionList.getSelectionModel().getSelectedItem();
        String selectedLoader = loader.getValue();
        boolean installed = selected != null && gameService.installedInstances().stream()
                .anyMatch(item -> matchesLoader(item, selected.id(), selectedLoader, loaderVersion.getValue()));
        boolean versionMissing = !"原版".equals(selectedLoader) && loaderVersion.getValue() == null;
        install.setDisable(busy || loadingLoaders || selected == null || installed || versionMissing);
        install.setText(installed ? "已安装" : "原版".equals(selectedLoader) ? "安装所选版本" : "安装 " + selectedLoader);
        launch.setDisable(busy || !installed);
    }

    private GameInstance selectedInstalledInstance(GameVersion selected) throws java.io.IOException {
        String selectedLoader = loader.getValue();
        return gameService.installedInstances().stream()
                .filter(item -> matchesLoader(item, selected.id(), selectedLoader, loaderVersion.getValue()))
                .findFirst().orElseThrow(() -> new java.io.IOException("所选实例尚未安装"));
    }

    private boolean matchesLoader(
            GameInstance instance, String gameVersion, String selectedLoader, LoaderVersion selectedVersion
    ) {
        String version = selectedVersion == null ? "" : selectedVersion.version();
        return switch (selectedLoader) {
            case "Fabric" -> !version.isBlank() && instance.id().equals("fabric-loader-" + version + "-" + gameVersion);
            case "Forge" -> !version.isBlank() && instance.id().equals("forge-" + version + "-" + gameVersion);
            case "NeoForge" -> !version.isBlank() && instance.id().equals("neoforge-" + version + "-" + gameVersion);
            default -> instance.id().equals(gameVersion);
        };
    }

    private void setLoading(boolean loading) {
        refresh.setDisable(loading);
        progress.setVisible(loading);
        progress.setManaged(loading);
    }

    private ListCell<String> localizedStringCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : I18n.text(item));
            }
        };
    }

    private String rootCauseMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class VersionCell extends ListCell<GameVersion> {
        private final DateTimeFormatter dateFormat = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(Locale.SIMPLIFIED_CHINESE).withZone(ZoneId.systemDefault());

        @Override
        protected void updateItem(GameVersion version, boolean empty) {
            super.updateItem(version, empty);
            if (empty || version == null) {
                setGraphic(null);
                return;
            }
            Label id = new Label(version.id());
            id.getStyleClass().add("version-id");
            Label type = new Label(I18n.text(version.type().displayName()));
            type.getStyleClass().add("version-type");
            Label installed = new Label(gameService.isInstalled(version.id()) ? "✓ 已安装" : "");
            installed.getStyleClass().add("installed-badge");
            Label date = new Label(dateFormat.format(version.releaseTime()));
            date.getStyleClass().add("version-date");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(12, id, type, installed, spacer, date);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(7, 4, 7, 4));
            setGraphic(row);
        }
    }
}
