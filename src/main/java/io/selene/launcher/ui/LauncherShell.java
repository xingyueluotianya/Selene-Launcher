package io.selene.launcher.ui;

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
import io.selene.launcher.core.model.AppLanguage;
import io.selene.launcher.core.model.LauncherSettings;
import io.selene.launcher.core.model.VisualTheme;
import io.selene.launcher.core.port.SettingsRepository;
import io.selene.launcher.infra.AppPaths;
import io.selene.launcher.infra.VisualThemeRepository;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public final class LauncherShell {
    private final BorderPane root = new BorderPane();
    private final StackPane layeredRoot = new StackPane();
    private final GlobalVisualLayer globalVisual = new GlobalVisualLayer();
    private final StackPane content = new StackPane();
    private final Map<String, Button> navigation = new LinkedHashMap<>();
    private final SettingsRepository settingsRepository;
    private final GameLibraryPage gameLibraryPage;
    private final InstanceManagerPage instanceManagerPage;
    private final DownloadQueuePage downloadQueuePage;
    private final VisualThemePage visualThemePage;
    private final DiagnosticService diagnosticService;
    private final AccountService accountService;
    private final AccountPage accountPage;
    private final ResourceCenterPage resourceCenterPage;
    private final AppPaths paths;
    private final Button accountButton = new Button();
    private LauncherSettings settings;

    public LauncherShell(
            LauncherSettings settings,
            SettingsRepository settingsRepository,
            VersionCatalogService versionCatalogService,
            DownloadQueueService downloadQueueService,
            VanillaGameService vanillaGameService,
            GameProcessService gameProcessService,
            VisualThemeRepository visualThemeRepository,
            DiagnosticService diagnosticService,
            AccountService accountService,
            FabricLoaderService fabricLoaderService,
            InstallerLoaderService installerLoaderService,
            ModrinthService modrinthService,
            ModpackImportService modpackImportService,
            InstanceManagementService instanceManagementService,
            FailureDiagnosticService failureDiagnosticService,
            ModRepairService modRepairService,
            AppPaths paths
    ) {
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.paths = paths;
        this.diagnosticService = diagnosticService;
        this.accountService = accountService;
        this.accountPage = new AccountPage(accountService, this::showError, this::refreshAccountButton);
        this.resourceCenterPage = new ResourceCenterPage(
                vanillaGameService, modrinthService, modpackImportService, paths.downloadsDirectory(),
                this::showError, this::showDownloads
        );
        this.downloadQueuePage = new DownloadQueuePage(downloadQueueService);
        this.gameLibraryPage = new GameLibraryPage(
                versionCatalogService,
                vanillaGameService,
                gameProcessService,
                accountService,
                fabricLoaderService,
                installerLoaderService,
                failureDiagnosticService,
                this::showError,
                this::showDownloads
        );
        this.instanceManagerPage = new InstanceManagerPage(
                instanceManagementService, vanillaGameService, gameProcessService, accountService,
                failureDiagnosticService, modRepairService, this::showError, this::showGameLibrary
        );
        this.visualThemePage = new VisualThemePage(visualThemeRepository, this::applyTheme, this::showError);
        root.getStyleClass().add("app-shell");
        root.setLeft(createSidebar());
        root.setTop(createTitleBar());
        root.setCenter(content);
        BorderPane.setMargin(content, new Insets(14, 22, 22, 14));
        layeredRoot.getStyleClass().add("visual-root");
        layeredRoot.getChildren().addAll(globalVisual, root);
        globalVisual.widthProperty().bind(layeredRoot.widthProperty());
        globalVisual.heightProperty().bind(layeredRoot.heightProperty());
        I18n.onChange(this::refreshLanguage);
        showDashboard();
        refreshLanguage();
    }

    public Node view() {
        return layeredRoot;
    }

    public void updateSettings(LauncherSettings settings) {
        this.settings = settings;
    }

    public void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR, I18n.text(message));
        alert.setHeaderText(I18n.text("Selene Launcher 无法完成操作"));
        alert.showAndWait();
    }

    private Node createSidebar() {
        VBox sidebar = new VBox(8);
        sidebar.getStyleClass().add("sidebar");
        sidebar.setPadding(new Insets(26, 16, 26, 16));
        sidebar.setPrefWidth(218);

        HBox brand = new HBox(11);
        brand.setAlignment(Pos.CENTER_LEFT);
        Label mark = new Label("◐");
        mark.getStyleClass().add("brand-mark");
        VBox titles = new VBox(-2);
        Label title = new Label("Selene Launcher");
        title.getStyleClass().add("brand-title");
        Label channel = new Label("SELENE LAUNCHER · BETA 6");
        channel.getStyleClass().add("brand-channel");
        titles.getChildren().addAll(title, channel);
        brand.getChildren().addAll(mark, titles);

        sidebar.getChildren().add(brand);
        Region gap = new Region();
        gap.setPrefHeight(25);
        sidebar.getChildren().add(gap);

        addNavigation(sidebar, "概览", "⌂", this::showDashboard);
        addNavigation(sidebar, "实例管理", "◫", this::showInstances);
        addNavigation(sidebar, "版本安装", "▦", this::showGameLibrary);
        addNavigation(sidebar, "资源中心", "◇", this::showResources);
        addNavigation(sidebar, "服务器", "▤", () -> showPlaceholder("服务器", "本地服与远程服务器管理"));
        addNavigation(sidebar, "下载", "⇣", this::showDownloads);
        addNavigation(sidebar, "视觉工坊", "✦", this::showVisualTheme);

        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        sidebar.getChildren().add(spacer);
        addNavigation(sidebar, "设置", "⚙", this::showSettings);
        return sidebar;
    }

    private Node createTitleBar() {
        HBox bar = new HBox(12);
        bar.setAlignment(Pos.CENTER_RIGHT);
        bar.setPadding(new Insets(18, 25, 8, 14));
        Label status = new Label("●  服务正常");
        status.getStyleClass().add("service-status");
        accountButton.getStyleClass().add("account-button");
        accountButton.setOnAction(event -> showAccounts());
        refreshAccountButton();
        bar.getChildren().addAll(status, accountButton);
        return bar;
    }

    private void addNavigation(VBox parent, String text, String icon, Runnable action) {
        Button button = new Button(icon + "   " + text);
        button.getStyleClass().add("nav-button");
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setOnAction(event -> {
            select(text);
            action.run();
        });
        navigation.put(text, button);
        parent.getChildren().add(button);
    }

    private void select(String selected) {
        navigation.forEach((name, button) -> button.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("selected"), name.equals(selected)
        ));
    }

    private void showDashboard() {
        visualThemePage.deactivate();
        select("概览");
        VBox page = new VBox(17);
        page.getStyleClass().add("page");

        VBox heading = new VBox(2);
        Label eyebrow = new Label("GOOD EVENING, TRAVELER");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("下一站，月海。你好，旅行者。");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("Selene Launcher 将轻量体验、完整功能与可塑视觉融合在一起。");
        subtitle.getStyleClass().add("page-subtitle");
        heading.getChildren().addAll(eyebrow, title, subtitle);

        StackPane hero = new StackPane();
        hero.getStyleClass().add("hero-card");
        hero.setMinHeight(250);
        VBox heroText = new VBox(8);
        heroText.setMaxWidth(660);
        heroText.setAlignment(Pos.CENTER_LEFT);
        Label edition = new Label("JAVA EDITION");
        edition.getStyleClass().add("pill");
        Label version = new Label("选择并安装你的第一个游戏实例");
        version.getStyleClass().add("hero-title");
        Label hint = new Label("Selene Launcher 会规划客户端、依赖库、资源文件与启动参数，并执行完整性校验。");
        hint.getStyleClass().add("hero-copy");
        Button install = new Button("＋  安装游戏");
        install.getStyleClass().add("primary-button");
        install.setOnAction(event -> {
            select("版本安装");
            showGameLibrary();
        });
        heroText.getChildren().addAll(edition, version, hint, install);
        StackPane.setAlignment(heroText, Pos.CENTER_LEFT);
        StackPane.setMargin(heroText, new Insets(32));
        hero.getChildren().add(heroText);

        HBox cards = new HBox(14,
                quickCard("资源中心", "发现模组与整合包", "◇", "accent-cyan"),
                quickCard("快速开服", "创建或接管服务器", "▤", "accent-green"),
                quickCard("迁移实例", "导入已有游戏目录", "⇄", "accent-coral")
        );
        cards.getChildren().forEach(node -> HBox.setHgrow(node, Priority.ALWAYS));

        page.getChildren().addAll(heading, hero, cards);
        showContent(page);
    }

    private void showInstances() {
        visualThemePage.deactivate();
        select("实例管理");
        showContent(instanceManagerPage.view());
    }

    private void showGameLibrary() {
        visualThemePage.deactivate();
        select("版本安装");
        showContent(gameLibraryPage.view());
    }

    private void showResources() {
        visualThemePage.deactivate();
        showContent(resourceCenterPage.view());
    }

    private void showAccounts() {
        visualThemePage.deactivate();
        navigation.values().forEach(button -> button.pseudoClassStateChanged(
                javafx.css.PseudoClass.getPseudoClass("selected"), false));
        showContent(accountPage.view());
    }

    private void refreshAccountButton() {
        var account = accountService.active();
        accountButton.setText(account.displayName() + " · " + I18n.text(account.type().displayName()) + "  ›");
    }

    private VBox quickCard(String title, String copy, String icon, String accentClass) {
        VBox card = new VBox(7);
        card.getStyleClass().addAll("quick-card", accentClass);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setPadding(new Insets(18));
        Label iconLabel = new Label(icon);
        iconLabel.getStyleClass().add("quick-icon");
        Label titleLabel = new Label(title);
        titleLabel.getStyleClass().add("card-title");
        Label copyLabel = new Label(copy);
        copyLabel.getStyleClass().add("card-copy");
        card.getChildren().addAll(iconLabel, titleLabel, copyLabel);
        return card;
    }

    private void showSettings() {
        visualThemePage.deactivate();
        VBox page = new VBox(18);
        page.getStyleClass().add("page");

        VBox heading = new VBox(3);
        Label eyebrow = new Label("PREFERENCES");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("设置");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("调整下载策略与大型文件位置；版本、公共资源和 Java 均可放到其他磁盘。");
        subtitle.getStyleClass().add("page-subtitle");
        heading.getChildren().addAll(eyebrow, title, subtitle);

        VBox panel = new VBox(14);
        panel.getStyleClass().add("settings-panel");
        panel.setPadding(new Insets(22));

        Label languageHeading = new Label("界面语言");
        languageHeading.getStyleClass().add("settings-title");
        Label languageHint = new Label("语言切换会立即生效并随设置保存");
        languageHint.getStyleClass().add("setting-label");
        ComboBox<AppLanguage> language = new ComboBox<>();
        language.getItems().setAll(AppLanguage.values());
        language.getSelectionModel().select(AppLanguage.fromTag(settings.locale()));
        language.setPrefWidth(210);
        language.setCellFactory(ignored -> languageCell());
        language.setButtonCell(languageCell());
        language.valueProperty().addListener((observable, before, selected) -> {
            if (selected != null) {
                settings = new LauncherSettings(
                        selected.tag(), settings.theme(), settings.maxConcurrentDownloads(), settings.playIntro(),
                        settings.preferMirror(), settings.activeProfileId(), settings.onboardingCompleted(),
                        settings.downloadsDirectory(), settings.instancesDirectory(), settings.sharedMinecraftDirectory(),
                        settings.runtimesDirectory()
                );
                I18n.setLanguage(selected);
                language.setButtonCell(languageCell());
            }
        });
        HBox languageRow = new HBox(18, languageHint, language);
        languageRow.setAlignment(Pos.CENTER_LEFT);

        Separator languageSeparator = new Separator();
        Label downloadHeading = new Label("下载策略");
        downloadHeading.getStyleClass().add("settings-title");
        Spinner<Integer> concurrency = new Spinner<>();
        concurrency.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(
                1, 16, settings.maxConcurrentDownloads()
        ));
        concurrency.setEditable(true);
        concurrency.setPrefWidth(110);
        Label concurrencyHint = new Label("并发下载数（建议 4）");
        concurrencyHint.getStyleClass().add("setting-label");
        HBox concurrencyRow = new HBox(18, concurrencyHint, concurrency);
        concurrencyRow.setAlignment(Pos.CENTER_LEFT);

        CheckBox preferMirror = new CheckBox("网络不稳定时优先尝试镜像源");
        preferMirror.setSelected(settings.preferMirror());
        CheckBox playIntro = new CheckBox("启动时播放开场动效");
        playIntro.setSelected(settings.playIntro());

        Separator storageSeparator = new Separator();
        Label storageHeading = new Label("存储位置");
        storageHeading.getStyleClass().add("settings-title");
        StoragePathPane storagePaths = new StoragePathPane(paths.root(), settings, () -> root.getScene().getWindow());

        Separator separator = new Separator();
        Label runtimeHeading = new Label("运行环境");
        runtimeHeading.getStyleClass().add("settings-title");
        Label runtimeCopy = new Label("桌面应用固定携带 Java 21。游戏运行时按版本元数据独立管理：支持 Java 8、16、17、21、25，以及官方元数据声明并可从 Temurin 获取的其他主版本；JRE 不可用时自动回退 JDK，不修改系统环境变量。");
        runtimeCopy.getStyleClass().add("page-subtitle");
        runtimeCopy.setWrapText(true);

        Label diagnosticHeading = new Label("日志与诊断");
        diagnosticHeading.getStyleClass().add("settings-title");
        Label diagnosticCopy = new Label("启动器日志会自动轮转保存。遇到安装或启动失败时，可导出诊断包后发送给开发者；账号令牌不会打包。");
        diagnosticCopy.getStyleClass().add("page-subtitle");
        diagnosticCopy.setWrapText(true);
        Label diagnosticStatus = new Label();
        diagnosticStatus.getStyleClass().add("save-status");
        Button exportDiagnostics = new Button("导出诊断包");
        exportDiagnostics.getStyleClass().add("ghost-button");
        exportDiagnostics.setOnAction(event -> {
            try {
                diagnosticStatus.setText("已导出：" + diagnosticService.export());
            } catch (IOException exception) {
                diagnosticStatus.setText("导出失败");
                showError("无法导出诊断包：" + exception.getMessage());
            }
        });
        HBox diagnosticRow = new HBox(12, exportDiagnostics, diagnosticStatus);
        diagnosticRow.setAlignment(Pos.CENTER_LEFT);

        Label saveStatus = new Label();
        saveStatus.getStyleClass().add("save-status");
        Button save = new Button("保存设置");
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> {
            LauncherSettings updated = new LauncherSettings(
                    language.getValue().tag(), settings.theme(), concurrency.getValue(), playIntro.isSelected(),
                    preferMirror.isSelected(), settings.activeProfileId(), true,
                    settings.downloadsDirectory(), settings.instancesDirectory(), settings.sharedMinecraftDirectory(),
                    settings.runtimesDirectory()
            );
            try {
                storagePaths.validateAndPrepare();
                updated = storagePaths.applyTo(updated);
                settingsRepository.save(updated);
                settings = updated;
                saveStatus.setText("已保存；路径修改将在重启 Selene Launcher 后生效");
            } catch (IOException | RuntimeException exception) {
                saveStatus.setText("保存失败");
                showError("无法保存设置：" + exception.getMessage());
            }
        });
        HBox saveRow = new HBox(12, save, saveStatus);
        saveRow.setAlignment(Pos.CENTER_LEFT);

        panel.getChildren().addAll(
                languageHeading,
                languageRow,
                languageSeparator,
                downloadHeading,
                concurrencyRow,
                preferMirror,
                playIntro,
                storageSeparator,
                storageHeading,
                storagePaths.view(),
                separator,
                runtimeHeading,
                runtimeCopy,
                separator,
                diagnosticHeading,
                diagnosticCopy,
                diagnosticRow,
                saveRow
        );
        page.getChildren().addAll(heading, panel);
        ScrollPane scroll = new ScrollPane(page);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        showContent(scroll);
    }

    private void showDownloads() {
        visualThemePage.deactivate();
        select("下载");
        showContent(downloadQueuePage.view());
    }

    private void showVisualTheme() {
        select("视觉工坊");
        showContent(visualThemePage.view());
    }

    private void applyTheme(VisualTheme theme) {
        globalVisual.apply(theme);
        root.setStyle("-selene-accent: " + theme.accent() + "; -selene-glass: "
                + (0.70 + theme.glass() * 0.22) + "; -fx-opacity: " + (0.94 + theme.glass() * 0.06) + ";");
    }

    private void showPlaceholder(String title, String subtitle) {
        visualThemePage.deactivate();
        VBox placeholder = new VBox(10);
        placeholder.setAlignment(Pos.CENTER);
        Label icon = new Label("◌");
        icon.getStyleClass().add("placeholder-icon");
        Label heading = new Label(title);
        heading.getStyleClass().add("page-title");
        Label copy = new Label(subtitle + "\n此模块将在后续阶段接入真实数据。");
        copy.getStyleClass().add("page-subtitle");
        copy.setTextAlignment(javafx.scene.text.TextAlignment.CENTER);
        placeholder.getChildren().addAll(icon, heading, copy);
        showContent(placeholder);
    }

    private void showContent(Node node) {
        I18n.localize(node);
        content.getChildren().setAll(node);
    }

    private void refreshLanguage() {
        I18n.localize(layeredRoot);
        refreshAccountButton();
    }

    private ListCell<AppLanguage> languageCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AppLanguage item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.displayName(I18n.language()));
            }
        };
    }
}
