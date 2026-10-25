package io.selene.launcher.ui;

import io.selene.launcher.application.ModpackImportService;
import io.selene.launcher.application.ModrinthService;
import io.selene.launcher.application.VanillaGameService;
import io.selene.launcher.core.model.GameInstance;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.DragEvent;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;

import java.io.File;
import java.nio.file.Path;
import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Rich Modrinth resource browser with one-click and drag-and-drop pack installation. */
public final class ResourceCenterPage {
    private static final Map<String, String> CATEGORY_NAMES = Map.ofEntries(
            Map.entry("fabric", "Fabric"), Map.entry("forge", "Forge"), Map.entry("neoforge", "NeoForge"),
            Map.entry("adventure", "冒险"), Map.entry("technology", "科技"), Map.entry("magic", "魔法"),
            Map.entry("decoration", "装饰"), Map.entry("optimization", "性能优化"), Map.entry("utility", "实用工具"),
            Map.entry("worldgen", "世界生成"), Map.entry("storage", "存储"), Map.entry("social", "社交"),
            Map.entry("management", "管理"), Map.entry("library", "前置库"), Map.entry("food", "食物"),
            Map.entry("combat", "战斗"), Map.entry("mobs", "生物"), Map.entry("equipment", "装备"),
            Map.entry("quests", "任务"), Map.entry("multiplayer", "多人游戏")
    );

    private final VanillaGameService games;
    private final ModrinthService modrinth;
    private final ModpackImportService importer;
    private final Path downloadsDirectory;
    private final Consumer<String> errorHandler;
    private final Runnable showDownloads;
    private final VBox root = new VBox(14);
    private final ComboBox<GameInstance> instance = new ComboBox<>();
    private final ComboBox<ModrinthService.ProjectType> type = new ComboBox<>();
    private final TextField search = new TextField();
    private final ListView<ModrinthService.Project> results = new ListView<>();
    private final Label status = new Label("选择实例后可按兼容版本搜索和安装");
    private final VBox dropZone = new VBox(5);

    public ResourceCenterPage(
            VanillaGameService games,
            ModrinthService modrinth,
            ModpackImportService importer,
            Path downloadsDirectory,
            Consumer<String> errorHandler,
            Runnable showDownloads
    ) {
        this.games = games;
        this.modrinth = modrinth;
        this.importer = importer;
        this.downloadsDirectory = downloadsDirectory;
        this.errorHandler = errorHandler;
        this.showDownloads = showDownloads;
        configure();
    }

    public Node view() {
        type.setButtonCell(projectTypeCell());
        GameInstance selected = instance.getValue();
        List<GameInstance> installed = games.installedInstances();
        instance.setItems(FXCollections.observableArrayList(installed));
        if (selected != null) instance.getSelectionModel().select(installed.stream()
                .filter(item -> item.id().equals(selected.id())).findFirst().orElse(null));
        if (instance.getValue() == null && !installed.isEmpty()) instance.getSelectionModel().selectFirst();
        updateInstanceHint();
        return root;
    }

    private void configure() {
        root.getStyleClass().add("page");
        Label eyebrow = new Label("MODRINTH ECOSYSTEM");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("资源中心");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("按实例版本与加载器搜索兼容资源；查看中文分类、预览和热度，或直接拖入 ZIP / .mrpack 安装。");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(3, eyebrow, title, subtitle);

        instance.setPromptText("选择已安装实例");
        instance.setPrefWidth(260);
        instance.setCellFactory(ignored -> instanceCell());
        instance.setButtonCell(instanceCell());
        instance.valueProperty().addListener((observable, before, after) -> updateInstanceHint());
        type.getItems().setAll(ModrinthService.ProjectType.MOD, ModrinthService.ProjectType.RESOURCEPACK,
                ModrinthService.ProjectType.SHADER, ModrinthService.ProjectType.MODPACK);
        type.setCellFactory(ignored -> projectTypeCell());
        type.setButtonCell(projectTypeCell());
        type.getSelectionModel().selectFirst();
        search.setPromptText("搜索中文名、英文名、作者或关键词");
        search.setPrefWidth(310);
        Button searchButton = new Button("搜索兼容资源");
        searchButton.getStyleClass().add("primary-button");
        searchButton.setOnAction(event -> search());
        search.setOnAction(event -> search());
        HBox filters = new HBox(10, instance, type, search, searchButton);
        filters.setAlignment(Pos.CENTER_LEFT);

        configureDropZone();
        results.setCellFactory(ignored -> new ProjectCell());
        results.setPlaceholder(new Label("输入关键词搜索，或把 ZIP / .mrpack 拖到上方导入区"));
        VBox.setVgrow(results, Priority.ALWAYS);

        status.getStyleClass().add("save-status");
        Button importPack = new Button("选择 ZIP / .mrpack");
        importPack.getStyleClass().add("ghost-button");
        importPack.setOnAction(event -> choosePack());
        Button downloads = new Button("查看下载队列");
        downloads.getStyleClass().add("ghost-button");
        downloads.setOnAction(event -> showDownloads.run());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, status, spacer, importPack, downloads);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        root.getChildren().addAll(heading, filters, dropZone, results, toolbar);
        I18n.localize(root);
    }

    private void configureDropZone() {
        dropZone.getStyleClass().add("pack-drop-zone");
        dropZone.setAlignment(Pos.CENTER);
        dropZone.setPadding(new Insets(12));
        Label title = new Label("⇣  拖入本地整合包即可安装");
        title.getStyleClass().add("settings-title");
        Label copy = new Label("支持 .mrpack、Modrinth ZIP 和包含完整本地文件的普通 ZIP；将导入到当前选择的实例");
        copy.getStyleClass().add("page-subtitle");
        dropZone.getChildren().addAll(title, copy);
        dropZone.setOnDragOver(event -> {
            if (event.getDragboard().hasFiles() && event.getDragboard().getFiles().stream()
                    .map(File::toPath).anyMatch(importer::accepts)) {
                event.acceptTransferModes(TransferMode.COPY);
                dropZone.getStyleClass().add("drag-active");
            }
            event.consume();
        });
        dropZone.setOnDragExited(event -> dropZone.getStyleClass().remove("drag-active"));
        dropZone.setOnDragDropped(this::handleDrop);
    }

    private void handleDrop(DragEvent event) {
        dropZone.getStyleClass().remove("drag-active");
        Path archive = event.getDragboard().getFiles().stream().map(File::toPath).filter(importer::accepts).findFirst().orElse(null);
        event.setDropCompleted(archive != null);
        event.consume();
        if (archive != null) importPack(archive);
    }

    private void updateInstanceHint() {
        GameInstance target = instance.getValue();
        if (target == null) {
            status.setText("请先在游戏库安装并选择一个实例");
        } else {
            status.setText("目标：" + target.name() + " · Minecraft " + games.baseGameVersion(target)
                    + " · " + games.loader(target) + " · Java " + target.requiredJavaMajor());
        }
    }

    private void search() {
        GameInstance target = instance.getValue();
        String gameVersion = target == null ? "" : games.baseGameVersion(target);
        String loader = target == null ? "" : games.loader(target);
        status.setText("正在按实例兼容性搜索 Modrinth…");
        modrinth.search(search.getText(), type.getValue(), gameVersion, loader)
                .whenComplete((projects, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        status.setText("搜索失败");
                        errorHandler.accept("无法搜索 Modrinth：" + rootCause(failure));
                    } else {
                        results.getItems().setAll(projects);
                        status.setText("找到 " + projects.size() + " 个兼容项目 · 已按下载量排序");
                    }
                }));
    }

    private void install(ModrinthService.Project project) {
        GameInstance target = instance.getValue();
        if (target == null) {
            errorHandler.accept("请先在游戏库安装一个实例，再选择资源安装目标");
            return;
        }
        String loader = games.loader(target);
        String version = games.baseGameVersion(target);
        status.setText("正在准备 " + project.title());
        if (project.type() == ModrinthService.ProjectType.MODPACK) {
            Path cache = downloadsDirectory.resolve("modpacks");
            modrinth.downloadModpack(project, cache, version)
                    .thenCompose(path -> importer.importArchive(path, target,
                            text -> Platform.runLater(() -> status.setText(text))))
                    .whenComplete((count, failure) -> Platform.runLater(() -> {
                        if (failure != null) {
                            status.setText("整合包安装失败");
                            errorHandler.accept("在线整合包安装失败：" + rootCause(failure));
                        } else {
                            status.setText("整合包安装完成，共处理 " + count + " 个文件");
                        }
                    }));
            return;
        }
        modrinth.install(project, target, version, loader).whenComplete((path, failure) -> Platform.runLater(() -> {
            if (failure != null) {
                status.setText("安装失败");
                errorHandler.accept("资源安装失败：" + rootCause(failure));
            } else {
                status.setText("已安装到 " + path.getFileName());
            }
        }));
    }

    private void choosePack() {
        if (instance.getValue() == null) {
            errorHandler.accept("请先选择整合包导入目标实例");
            return;
        }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("选择本地整合包");
        chooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("支持的整合包 (*.mrpack, *.zip)", "*.mrpack", "*.zip"),
                new FileChooser.ExtensionFilter("Modrinth 整合包 (*.mrpack)", "*.mrpack"),
                new FileChooser.ExtensionFilter("ZIP 整合包 (*.zip)", "*.zip")
        );
        File file = chooser.showOpenDialog(root.getScene().getWindow());
        if (file != null) importPack(file.toPath());
    }

    private void importPack(Path archive) {
        GameInstance target = instance.getValue();
        if (target == null) {
            errorHandler.accept("请先选择整合包导入目标实例");
            return;
        }
        if (!importer.accepts(archive)) {
            errorHandler.accept("不支持该文件；请拖入 .mrpack 或 .zip 整合包");
            return;
        }
        status.setText("正在识别 " + archive.getFileName() + "…");
        importer.importArchive(archive, target, text -> Platform.runLater(() -> status.setText(text)))
                .whenComplete((count, failure) -> Platform.runLater(() -> {
                    if (failure != null) {
                        status.setText("整合包导入失败");
                        errorHandler.accept("整合包导入失败：" + rootCause(failure));
                    } else {
                        status.setText("整合包导入完成，共处理 " + count + " 个文件");
                    }
                }));
    }

    private ListCell<ModrinthService.ProjectType> projectTypeCell() {
        return new ListCell<>() {
            @Override protected void updateItem(ModrinthService.ProjectType item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : I18n.text(item.displayName()));
            }
        };
    }

    private ListCell<GameInstance> instanceCell() {
        return new ListCell<>() {
            @Override protected void updateItem(GameInstance item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.name() + " · Java " + item.requiredJavaMajor());
            }
        };
    }

    private final class ProjectCell extends ListCell<ModrinthService.Project> {
        @Override protected void updateItem(ModrinthService.Project project, boolean empty) {
            super.updateItem(project, empty);
            if (empty || project == null) {
                setGraphic(null);
                return;
            }
            ImageView icon = projectIcon(project);
            Label title = new Label(project.title());
            title.getStyleClass().add("resource-title");
            Label slug = new Label(project.slug() + " · 作者 " + project.author());
            slug.getStyleClass().add("version-date");
            Label description = new Label(project.description());
            description.getStyleClass().add("page-subtitle");
            description.setWrapText(true);
            description.setMaxWidth(620);

            String categories = project.categories().stream().limit(5).map(ResourceCenterPage.this::localCategory)
                    .reduce((left, right) -> left + " · " + right).orElse(I18n.text(project.type().displayName()));
            Label category = badge(I18n.text("中文分类") + "  " + categories);
            Label versions = badge(I18n.text("兼容") + "  " + project.latestGameVersions());
            Label environment = badge(I18n.text("客户端") + " " + sideName(project.clientSide())
                    + " / " + I18n.text("服务端") + " " + sideName(project.serverSide()));
            HBox badges = new HBox(7, category, versions, environment);

            String metrics = NumberFormat.getIntegerInstance(I18n.language().locale()).format(project.downloads())
                    + " " + I18n.text("次下载") + "  ·  "
                    + NumberFormat.getIntegerInstance(I18n.language().locale()).format(project.follows())
                    + " " + I18n.text("人关注") + "  ·  " + I18n.text("更新") + " " + project.modifiedDate();
            Label meta = new Label(metrics);
            meta.getStyleClass().add("version-date");
            VBox text = new VBox(3, title, slug, description, badges, meta);
            HBox.setHgrow(text, Priority.ALWAYS);
            Button install = new Button(project.type() == ModrinthService.ProjectType.MODPACK ? "安装整合包" : "安装到实例");
            install.getStyleClass().add("ghost-button");
            install.setOnAction(event -> install(project));
            HBox row = new HBox(14, icon, text, install);
            row.setAlignment(Pos.CENTER_LEFT);
            row.setPadding(new Insets(10, 5, 10, 5));
            setGraphic(row);
        }
    }

    private ImageView projectIcon(ModrinthService.Project project) {
        ImageView view = new ImageView();
        view.setFitWidth(56);
        view.setFitHeight(56);
        view.setPreserveRatio(true);
        view.getStyleClass().add("resource-icon");
        Rectangle clip = new Rectangle(56, 56);
        clip.setArcWidth(13);
        clip.setArcHeight(13);
        view.setClip(clip);
        if (!project.iconUrl().isBlank()) {
            Image image = new Image(project.iconUrl(), 56, 56, true, true, true);
            view.setImage(image);
        }
        return view;
    }

    private Label badge(String text) {
        Label label = new Label(text);
        label.getStyleClass().add("resource-badge");
        return label;
    }

    private String localCategory(String value) {
        return I18n.text(CATEGORY_NAMES.getOrDefault(value.toLowerCase(Locale.ROOT), value));
    }

    private String sideName(String value) {
        return I18n.text(switch (value == null ? "" : value) {
            case "required" -> "必需";
            case "optional" -> "可选";
            case "unsupported" -> "不支持";
            default -> "未知";
        });
    }

    private String rootCause(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }
}
