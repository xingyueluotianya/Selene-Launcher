package io.selene.launcher.ui;

import io.selene.launcher.application.AccountService;
import io.selene.launcher.application.FailureDiagnosticService;
import io.selene.launcher.application.GameProcessService;
import io.selene.launcher.application.InstanceManagementService;
import io.selene.launcher.application.ModRepairService;
import io.selene.launcher.application.VanillaGameService;
import io.selene.launcher.core.model.GameInstance;
import io.selene.launcher.core.model.InstanceSettings;
import io.selene.launcher.core.model.ManagedMod;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Spinner;
import javafx.scene.control.SpinnerValueFactory;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Installed-instance selection, launch settings, live logs and local mod management. */
public final class InstanceManagerPage {
    private final InstanceManagementService instances;
    private final VanillaGameService games;
    private final GameProcessService processService;
    private final AccountService accounts;
    private final FailureDiagnosticService failureDiagnostics;
    private final ModRepairService modRepairs;
    private final Consumer<String> errorHandler;
    private final Runnable showInstaller;
    private final BorderPane root = new BorderPane();
    private final ListView<GameInstance> instanceList = new ListView<>();
    private final Label status = new Label("选择实例后可以启动、设置或管理模组");
    private final Label instanceTitle = new Label("尚未选择实例");
    private final Label instanceMeta = new Label("在左侧选择已安装游戏，或前往版本安装页面创建实例");
    private final TabPane tabs = new TabPane();
    private final Button launch = new Button("启动游戏");
    private final Button stop = new Button("结束游戏");
    private final TextArea liveLog = new TextArea();
    private final TextField instanceName = new TextField();
    private final Spinner<Integer> minimumMemory = spinner(256, 32768, 512, 256);
    private final Spinner<Integer> maximumMemory = spinner(512, 65536, 4096, 256);
    private final Spinner<Integer> width = spinner(640, 7680, 1280, 80);
    private final Spinner<Integer> height = spinner(360, 4320, 720, 45);
    private final CheckBox fullscreen = new CheckBox("全屏启动");
    private final TextField javaExecutable = new TextField();
    private final TextField jvmArguments = new TextField();
    private final TextField gameArguments = new TextField();
    private final TextField quickServer = new TextField();
    private final ListView<ManagedMod> modList = new ListView<>();
    private final ListView<ModRepairService.RepairOption> repairList = new ListView<>();
    private final Label repairStatus = new Label("启动失败后可扫描缺失依赖和错误版本");
    private final Button scanRepairs = new Button("扫描模组问题");
    private final Button repairSelected = new Button("一键修复所选");
    private Process runningProcess;
    private boolean repairing;

    public InstanceManagerPage(
            InstanceManagementService instances,
            VanillaGameService games,
            GameProcessService processService,
            AccountService accounts,
            FailureDiagnosticService failureDiagnostics,
            ModRepairService modRepairs,
            Consumer<String> errorHandler,
            Runnable showInstaller
    ) {
        this.instances = Objects.requireNonNull(instances);
        this.games = Objects.requireNonNull(games);
        this.processService = Objects.requireNonNull(processService);
        this.accounts = Objects.requireNonNull(accounts);
        this.failureDiagnostics = Objects.requireNonNull(failureDiagnostics);
        this.modRepairs = Objects.requireNonNull(modRepairs);
        this.errorHandler = Objects.requireNonNull(errorHandler);
        this.showInstaller = Objects.requireNonNull(showInstaller);
        configure();
    }

    public Node view() {
        refreshInstances(instanceList.getSelectionModel().getSelectedItem());
        I18n.localize(root);
        return root;
    }

    private void configure() {
        root.getStyleClass().add("page");
        VBox heading = new VBox(3);
        Label eyebrow = new Label("INSTANCE CONTROL");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("实例管理");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("选择已安装游戏，调整独立启动参数，查看实时日志，并管理该实例的本地模组。");
        subtitle.getStyleClass().add("page-subtitle");
        heading.getChildren().addAll(eyebrow, title, subtitle);
        BorderPane.setMargin(heading, new Insets(0, 0, 13, 0));
        root.setTop(heading);

        VBox left = new VBox(10);
        left.getStyleClass().add("instance-sidebar");
        left.setPadding(new Insets(13));
        left.setPrefWidth(310);
        Label installed = new Label("已安装游戏");
        installed.getStyleClass().add("settings-title");
        instanceList.setCellFactory(ignored -> new InstanceCell());
        instanceList.setPlaceholder(new Label("还没有实例，请先安装游戏"));
        instanceList.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> load(after));
        VBox.setVgrow(instanceList, Priority.ALWAYS);
        Button refresh = new Button("刷新实例");
        refresh.getStyleClass().add("ghost-button");
        refresh.setOnAction(event -> refreshInstances(instanceList.getSelectionModel().getSelectedItem()));
        Button add = new Button("＋ 安装新游戏");
        add.getStyleClass().add("primary-button");
        add.setOnAction(event -> showInstaller.run());
        HBox leftActions = new HBox(8, add, refresh);
        left.getChildren().addAll(installed, instanceList, leftActions);
        BorderPane.setMargin(left, new Insets(0, 13, 0, 0));
        root.setLeft(left);

        VBox details = new VBox(10);
        HBox header = new HBox(12);
        VBox identity = new VBox(2, instanceTitle, instanceMeta);
        instanceTitle.getStyleClass().add("settings-title");
        instanceMeta.getStyleClass().add("page-subtitle");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        launch.getStyleClass().add("launch-button");
        launch.setOnAction(event -> launchSelected());
        stop.getStyleClass().add("danger-button");
        stop.setOnAction(event -> stopGame());
        Button folder = new Button("打开游戏文件夹");
        folder.getStyleClass().add("ghost-button");
        folder.setOnAction(event -> openSelectedGameDirectory());
        header.getChildren().addAll(identity, spacer, folder, stop, launch);
        header.setAlignment(Pos.CENTER_LEFT);

        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        tabs.getTabs().addAll(
                new Tab("游戏设置", settingsPane()),
                new Tab("模组管理", modsPane()),
                new Tab("启动日志", logsPane())
        );
        VBox.setVgrow(tabs, Priority.ALWAYS);
        status.getStyleClass().add("save-status");
        details.getChildren().addAll(header, tabs, status);
        root.setCenter(details);
        updateActions();
    }

    private Node settingsPane() {
        VBox panel = new VBox(13);
        panel.setPadding(new Insets(18));
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(11);
        instanceName.setPromptText("实例显示名称");
        javaExecutable.setPromptText("留空则自动下载并选择兼容 Java");
        jvmArguments.setPromptText("例如 -XX:+UseG1GC");
        gameArguments.setPromptText("额外 Minecraft 参数");
        quickServer.setPromptText("可选，例如 play.example.com");
        int row = 0;
        addSetting(grid, row++, "实例名称", instanceName);
        addSetting(grid, row++, "内存范围（MiB）", new HBox(8, minimumMemory, new Label("至"), maximumMemory));
        addSetting(grid, row++, "窗口分辨率", new HBox(8, width, new Label("×"), height, fullscreen));
        addSetting(grid, row++, "自定义 Java", javaExecutable);
        addSetting(grid, row++, "额外 JVM 参数", jvmArguments);
        addSetting(grid, row++, "额外游戏参数", gameArguments);
        addSetting(grid, row, "快速进入服务器", quickServer);
        Button save = new Button("保存实例设置");
        save.getStyleClass().add("primary-button");
        save.setOnAction(event -> saveSettings());
        Button delete = new Button("删除实例");
        delete.getStyleClass().add("danger-button");
        delete.setOnAction(event -> deleteSelected());
        Label hint = new Label("每个实例独立保存内存、窗口、自定义 Java 与附加参数；公共游戏文件不会因删除实例而移除。");
        hint.getStyleClass().add("page-subtitle");
        hint.setWrapText(true);
        panel.getChildren().addAll(grid, new Separator(), new HBox(10, save, delete), hint);
        ScrollPane scroll = new ScrollPane(panel);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("settings-scroll");
        return scroll;
    }

    private Node modsPane() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(14));
        Label hint = new Label("导入 .jar 模组后可随时启用、禁用或删除。禁用只会重命名文件，不会破坏模组内容。");
        hint.getStyleClass().add("page-subtitle");
        modList.setCellFactory(ignored -> new ModCell());
        modList.setPlaceholder(new Label("该实例还没有本地模组"));
        VBox.setVgrow(modList, Priority.ALWAYS);
        Button add = new Button("导入 .jar 模组");
        add.getStyleClass().add("primary-button");
        add.setOnAction(event -> chooseMods());
        Button toggle = new Button("启用 / 禁用");
        toggle.getStyleClass().add("ghost-button");
        toggle.setOnAction(event -> toggleSelectedMod());
        Button delete = new Button("删除模组");
        delete.getStyleClass().add("danger-button");
        delete.setOnAction(event -> deleteSelectedMod());
        Button folder = new Button("打开 mods 文件夹");
        folder.getStyleClass().add("ghost-button");
        folder.setOnAction(event -> openModsDirectory());

        Separator repairSeparator = new Separator();
        Label repairTitle = new Label("模组错误自动修复");
        repairTitle.getStyleClass().add("settings-title");
        repairStatus.getStyleClass().add("page-subtitle");
        repairStatus.setWrapText(true);
        repairList.setCellFactory(ignored -> new RepairCell());
        repairList.setPlaceholder(new Label("尚未扫描，或日志中没有模组依赖问题"));
        repairList.setPrefHeight(190);
        scanRepairs.getStyleClass().add("ghost-button");
        scanRepairs.setOnAction(event -> scanModProblems());
        repairSelected.getStyleClass().add("primary-button");
        repairSelected.setOnAction(event -> repairSelectedProblem());
        repairSelected.setDisable(true);
        repairList.getSelectionModel().selectedItemProperty().addListener((observable, before, after) -> updateRepairActions());
        panel.getChildren().addAll(hint, modList, new HBox(9, add, toggle, delete, folder), repairSeparator,
                repairTitle, repairStatus, repairList, new HBox(9, scanRepairs, repairSelected));
        return panel;
    }

    private Node logsPane() {
        VBox panel = new VBox(9);
        panel.setPadding(new Insets(14));
        liveLog.setEditable(false);
        liveLog.setWrapText(false);
        liveLog.getStyleClass().add("game-log");
        VBox.setVgrow(liveLog, Priority.ALWAYS);
        Button clear = new Button("清空显示");
        clear.getStyleClass().add("ghost-button");
        clear.setOnAction(event -> liveLog.clear());
        Button open = new Button("打开日志文件");
        open.getStyleClass().add("ghost-button");
        open.setOnAction(event -> openLatestLog());
        panel.getChildren().addAll(liveLog, new HBox(9, clear, open));
        return panel;
    }

    private void addSetting(GridPane grid, int row, String label, Node editor) {
        Label text = new Label(label);
        text.getStyleClass().add("setting-label");
        GridPane.setHgrow(editor, Priority.ALWAYS);
        if (editor instanceof Region region) region.setMaxWidth(Double.MAX_VALUE);
        grid.add(text, 0, row);
        grid.add(editor, 1, row);
    }

    private void refreshInstances(GameInstance preferred) {
        List<GameInstance> values = instances.instances();
        instanceList.setItems(FXCollections.observableArrayList(values));
        GameInstance select = preferred == null ? null : values.stream()
                .filter(item -> item.id().equals(preferred.id())).findFirst().orElse(null);
        if (select != null) instanceList.getSelectionModel().select(select);
        else if (!values.isEmpty()) instanceList.getSelectionModel().selectFirst();
        else load(null);
        status.setText("已读取 " + values.size() + " 个实例");
    }

    private void load(GameInstance selected) {
        if (selected == null) {
            instanceTitle.setText("尚未选择实例");
            instanceMeta.setText("在左侧选择已安装游戏，或前往版本安装页面创建实例");
            modList.getItems().clear();
            repairList.getItems().clear();
            repairStatus.setText("启动失败后可扫描缺失依赖和错误版本");
            updateActions();
            updateRepairActions();
            return;
        }
        instanceTitle.setText(selected.name());
        instanceMeta.setText("Minecraft " + games.baseGameVersion(selected) + " · " + loaderName(games.loader(selected))
                + " · Java " + selected.requiredJavaMajor() + " · " + selected.directory());
        InstanceSettings settings = instances.loadSettings(selected);
        instanceName.setText(selected.name());
        minimumMemory.getValueFactory().setValue(settings.minimumMemoryMb());
        maximumMemory.getValueFactory().setValue(settings.maximumMemoryMb());
        width.getValueFactory().setValue(settings.width());
        height.getValueFactory().setValue(settings.height());
        fullscreen.setSelected(settings.fullscreen());
        javaExecutable.setText(settings.javaExecutable());
        jvmArguments.setText(settings.jvmArguments());
        gameArguments.setText(settings.gameArguments());
        quickServer.setText(settings.quickServerAddress());
        refreshMods();
        repairList.getItems().clear();
        repairStatus.setText("启动失败后可扫描缺失依赖和错误版本");
        loadExistingLog(selected);
        updateActions();
        updateRepairActions();
    }

    private void saveSettings() {
        GameInstance selected = selected();
        if (selected == null) return;
        try {
            if (minimumMemory.getValue() > maximumMemory.getValue()) throw new IllegalArgumentException("最小内存不能大于最大内存");
            InstanceSettings settings = enteredSettings();
            instances.saveSettings(selected, settings);
            GameInstance renamed = instances.rename(selected, instanceName.getText());
            refreshInstances(renamed);
            status.setText("实例设置已保存");
        } catch (IOException | RuntimeException failure) {
            errorHandler.accept("无法保存实例设置：" + failure.getMessage());
        }
    }

    private InstanceSettings enteredSettings() {
        return new InstanceSettings(minimumMemory.getValue(), maximumMemory.getValue(), width.getValue(), height.getValue(),
                fullscreen.isSelected(), javaExecutable.getText(), jvmArguments.getText(), gameArguments.getText(), quickServer.getText());
    }

    private void launchSelected() {
        GameInstance selected = selected();
        if (selected == null || runningProcess != null && runningProcess.isAlive()) return;
        InstanceSettings settings = enteredSettings();
        try {
            instances.saveSettings(selected, settings);
        } catch (IOException failure) {
            errorHandler.accept("无法保存启动设置：" + failure.getMessage());
            return;
        }
        liveLog.clear();
        appendLog("Selene Launcher 正在启动 " + selected.name() + "…");
        status.setText("正在准备游戏进程");
        launch.setDisable(true);
        processService.launch(selected, accounts.active(), settings,
                        message -> Platform.runLater(() -> status.setText(message)),
                        line -> Platform.runLater(() -> appendLog(line)))
                .whenComplete((process, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        runningProcess = null;
                        status.setText("启动失败");
                        errorHandler.accept("游戏启动失败：" + rootCause(failure));
                        updateActions();
                        return;
                    }
                    runningProcess = process;
                    updateActions();
                    process.onExit().thenRun(() -> Platform.runLater(() -> {
                        int exitCode = process.exitValue();
                        appendLog("游戏进程已退出，代码 " + exitCode);
                        runningProcess = null;
                        updateActions();
                        if (exitCode == 0) {
                            status.setText("游戏已正常退出");
                        } else {
                            FailureDiagnosticService.Diagnosis diagnosis = failureDiagnostics.diagnoseLaunch(
                                    selected.directory().resolve("logs/latest.log"), exitCode);
                            status.setText("异常退出 · 自动诊断：" + diagnosis.title());
                            appendLog("\n=== Selene Launcher 自动诊断 ===\n" + diagnosis.displayText());
                            if (diagnosis.title().contains("模组")) {
                                tabs.getSelectionModel().select(1);
                                scanModProblems();
                            }
                            errorHandler.accept("游戏退出自动诊断\n\n" + diagnosis.displayText());
                        }
                    }));
                }));
    }

    private void stopGame() {
        if (runningProcess == null || !runningProcess.isAlive()) return;
        status.setText("正在结束游戏进程…");
        runningProcess.destroy();
        CompletableFutureSupport.forceAfter(runningProcess, 4);
    }

    private void deleteSelected() {
        GameInstance selected = selected();
        if (selected == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.text("确定删除实例“") + selected.name() + I18n.text("”？存档、模组和配置都会被永久删除。"),
                ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(I18n.text("删除实例"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            instances.delete(selected);
            refreshInstances(null);
            status.setText("实例已删除");
        } catch (IOException failure) {
            errorHandler.accept("无法删除实例：" + failure.getMessage());
        }
    }

    private void chooseMods() {
        GameInstance selected = selected();
        if (selected == null) return;
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择模组文件");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Minecraft 模组 (*.jar)", "*.jar"));
        List<File> files = chooser.showOpenMultipleDialog(root.getScene().getWindow());
        if (files == null) return;
        try {
            for (File file : files) instances.importMod(selected, file.toPath());
            refreshMods();
            status.setText("已导入 " + files.size() + " 个模组");
        } catch (IOException | RuntimeException failure) {
            errorHandler.accept("无法导入模组：" + failure.getMessage());
        }
    }

    private void toggleSelectedMod() {
        ManagedMod mod = modList.getSelectionModel().getSelectedItem();
        if (mod == null) return;
        try {
            instances.setEnabled(mod, !mod.enabled());
            refreshMods();
        } catch (IOException failure) {
            errorHandler.accept("无法修改模组状态：" + failure.getMessage());
        }
    }

    private void deleteSelectedMod() {
        ManagedMod mod = modList.getSelectionModel().getSelectedItem();
        if (mod == null) return;
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                I18n.text("确定永久删除模组文件？") + "\n" + mod.fileName(), ButtonType.CANCEL, ButtonType.OK);
        confirm.setHeaderText(I18n.text("删除模组"));
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) return;
        try {
            instances.deleteMod(mod);
            refreshMods();
        } catch (IOException failure) {
            errorHandler.accept("无法删除模组：" + failure.getMessage());
        }
    }

    private void refreshMods() {
        GameInstance selected = selected();
        if (selected == null) return;
        try {
            modList.getItems().setAll(instances.mods(selected));
        } catch (IOException failure) {
            errorHandler.accept("无法读取模组目录：" + failure.getMessage());
        }
    }

    private void scanModProblems() {
        GameInstance selected = selected();
        if (selected == null || repairing) return;
        repairing = true;
        repairList.getItems().clear();
        repairStatus.setText("正在分析启动日志并查询兼容模组…");
        updateRepairActions();
        modRepairs.findRepairs(selected).whenComplete((options, failure) -> Platform.runLater(() -> {
            repairing = false;
            if (failure != null) {
                repairStatus.setText("扫描未完成");
                errorHandler.accept("无法诊断模组问题：" + rootCause(failure));
            } else {
                repairList.getItems().setAll(options);
                long available = options.stream().filter(ModRepairService.RepairOption::available).count();
                long unavailable = options.size() - available;
                if (options.isEmpty()) {
                    repairStatus.setText("日志中没有识别到缺失依赖或模组版本错误");
                } else {
                    repairStatus.setText("发现 " + options.size() + " 项问题 · 可一键修复 " + available
                            + " 项 · 没有找到可用模组 " + unavailable + " 项");
                    repairList.getSelectionModel().selectFirst();
                }
            }
            updateRepairActions();
        }));
    }

    private void repairSelectedProblem() {
        GameInstance selected = selected();
        ModRepairService.RepairOption option = repairList.getSelectionModel().getSelectedItem();
        if (selected == null || option == null || !option.available() || repairing) return;
        repairing = true;
        repairStatus.setText((option.issue().type() == ModRepairService.IssueType.MISSING_DEPENDENCY
                ? "正在下载依赖 " : "正在替换错误版本 ") + option.issue().modId() + "…");
        updateRepairActions();
        modRepairs.repair(selected, option).whenComplete((path, failure) -> Platform.runLater(() -> {
            repairing = false;
            if (failure != null) {
                repairStatus.setText("自动修复失败");
                errorHandler.accept("无法自动修复 " + option.issue().modId() + "：" + rootCause(failure));
                updateRepairActions();
                return;
            }
            repairStatus.setText("已修复 " + option.issue().modId() + " · " + path.getFileName() + "；请重新启动游戏验证");
            repairList.getItems().remove(option);
            refreshMods();
            updateRepairActions();
        }));
    }

    private void updateRepairActions() {
        ModRepairService.RepairOption selected = repairList.getSelectionModel().getSelectedItem();
        scanRepairs.setDisable(selected() == null || repairing);
        repairSelected.setDisable(repairing || selected == null || !selected.available());
        if (selected == null) repairSelected.setText("一键修复所选");
        else if (selected.issue().type() == ModRepairService.IssueType.MISSING_DEPENDENCY) repairSelected.setText("一键下载依赖");
        else repairSelected.setText("一键替换版本");
    }

    private void loadExistingLog(GameInstance selected) {
        Path file = selected.directory().resolve("logs/latest.log");
        if (runningProcess != null && runningProcess.isAlive()) return;
        if (Files.notExists(file)) {
            liveLog.setText("该实例还没有启动日志。\n");
            return;
        }
        try {
            long size = Files.size(file);
            if (size > 1024 * 1024) {
                liveLog.setText("日志超过 1 MiB，请点击“打开日志文件”查看完整内容。\n");
            } else {
                liveLog.setText(Files.readString(file));
                liveLog.positionCaret(liveLog.getLength());
            }
        } catch (IOException failure) {
            liveLog.setText("无法读取日志：" + failure.getMessage());
        }
    }

    private void appendLog(String line) {
        liveLog.appendText(line + System.lineSeparator());
        if (liveLog.getLength() > 1_500_000) liveLog.deleteText(0, 250_000);
        liveLog.positionCaret(liveLog.getLength());
    }

    private void openSelectedGameDirectory() {
        GameInstance selected = selected();
        if (selected != null) openDirectory(instances.gameDirectory(selected));
    }

    private void openModsDirectory() {
        GameInstance selected = selected();
        if (selected != null) openDirectory(instances.modsDirectory(selected));
    }

    private void openLatestLog() {
        GameInstance selected = selected();
        if (selected == null) return;
        Path file = selected.directory().resolve("logs/latest.log");
        if (Files.notExists(file)) {
            errorHandler.accept("该实例还没有日志文件");
            return;
        }
        open(file);
    }

    private void openDirectory(Path directory) {
        try {
            Files.createDirectories(directory);
            open(directory);
        } catch (IOException failure) {
            errorHandler.accept("无法打开文件夹：" + failure.getMessage());
        }
    }

    private void open(Path path) {
        try {
            if (!Desktop.isDesktopSupported()) throw new IOException("当前系统不支持文件管理器集成");
            Desktop.getDesktop().open(path.toFile());
        } catch (IOException | UnsupportedOperationException failure) {
            errorHandler.accept("无法打开路径：" + failure.getMessage());
        }
    }

    private GameInstance selected() {
        return instanceList.getSelectionModel().getSelectedItem();
    }

    private void updateActions() {
        boolean has = selected() != null;
        boolean running = runningProcess != null && runningProcess.isAlive();
        tabs.setDisable(!has);
        launch.setDisable(!has || running);
        stop.setDisable(!running);
    }

    private String loaderName(String loader) {
        return switch (loader) {
            case "fabric" -> "Fabric";
            case "forge" -> "Forge";
            case "neoforge" -> "NeoForge";
            default -> "原版";
        };
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private static Spinner<Integer> spinner(int min, int max, int initial, int step) {
        Spinner<Integer> value = new Spinner<>();
        value.setValueFactory(new SpinnerValueFactory.IntegerSpinnerValueFactory(min, max, initial, step));
        value.setEditable(true);
        value.setPrefWidth(125);
        return value;
    }

    private final class InstanceCell extends ListCell<GameInstance> {
        @Override protected void updateItem(GameInstance item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(item.name());
            name.getStyleClass().add("resource-title");
            Label meta = new Label("Minecraft " + games.baseGameVersion(item) + " · " + loaderName(games.loader(item))
                    + " · Java " + item.requiredJavaMajor());
            meta.getStyleClass().add("version-date");
            setGraphic(new VBox(2, name, meta));
        }
    }

    private final class RepairCell extends ListCell<ModRepairService.RepairOption> {
        @Override protected void updateItem(ModRepairService.RepairOption option, boolean empty) {
            super.updateItem(option, empty);
            if (empty || option == null) {
                setGraphic(null);
                return;
            }
            ModRepairService.ModIssue issue = option.issue();
            Label type = new Label(issue.type().displayName());
            type.getStyleClass().add(option.available() ? "installed-badge" : "version-date");
            Label name = new Label(issue.displayName() + "  [" + issue.modId() + "]");
            name.getStyleClass().add("resource-title");
            String versions = (issue.installedVersion().isBlank() ? "" : "当前 " + issue.installedVersion() + " · ")
                    + "需要 " + issue.requiredVersion();
            Label requirement = new Label(versions);
            requirement.getStyleClass().add("version-date");
            Label resolution = new Label(option.available() ? option.status() : "未找到：" + option.status());
            resolution.getStyleClass().add("page-subtitle");
            resolution.setWrapText(true);
            VBox detail = new VBox(2, new HBox(8, type, name), requirement, resolution);
            detail.setPadding(new Insets(6, 3, 6, 3));
            setGraphic(detail);
        }
    }

    private final class ModCell extends ListCell<ManagedMod> {
        private final DecimalFormat size = new DecimalFormat("0.0");
        @Override protected void updateItem(ManagedMod item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label name = new Label(item.fileName());
            name.getStyleClass().add("resource-title");
            Label state = new Label(item.enabled() ? "已启用" : "已禁用");
            state.getStyleClass().add(item.enabled() ? "installed-badge" : "version-date");
            Label fileSize = new Label(size.format(item.size() / 1024.0 / 1024.0) + " MiB");
            fileSize.getStyleClass().add("version-date");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(10, name, spacer, state, fileSize);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(7, 4, 7, 4));
            setGraphic(row);
        }
    }

    private static final class CompletableFutureSupport {
        private static void forceAfter(Process process, int seconds) {
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    if (!process.waitFor(seconds, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            });
        }
    }
}
