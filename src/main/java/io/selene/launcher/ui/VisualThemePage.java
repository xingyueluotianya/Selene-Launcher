package io.selene.launcher.ui;

import io.selene.launcher.core.model.VisualTheme;
import io.selene.launcher.infra.VisualThemeRepository;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Visual node editor that compiles a safe Selene shader graph into a GLSL-style preview program. */
public final class VisualThemePage {
    private final VisualThemeRepository repository;
    private final Consumer<VisualTheme> applyTheme;
    private final Consumer<String> errorHandler;
    private final VBox root = new VBox(14);
    private final Canvas preview = new Canvas(540, 280);
    private final TextArea shaderSource = new TextArea();
    private final ComboBox<String> preset = new ComboBox<>();
    private final Slider glow = slider(0, 1);
    private final Slider flow = slider(0, 2);
    private final Slider stars = slider(0, 1);
    private final Slider glass = slider(0, 1);
    private final ColorPicker accent = new ColorPicker();
    private final Map<String, VisualTheme> presets = new LinkedHashMap<>();
    private final AnimationTimer previewTimer;
    private VisualTheme theme;
    private long epoch;
    private boolean previewRunning;

    public VisualThemePage(
            VisualThemeRepository repository,
            Consumer<VisualTheme> applyTheme,
            Consumer<String> errorHandler
    ) {
        this.repository = repository;
        this.applyTheme = applyTheme;
        this.errorHandler = errorHandler;
        this.previewTimer = createPreviewTimer();
        configurePresets();
        configure();
        apply(repository.load());
        root.sceneProperty().addListener((observable, before, scene) -> updatePreviewLifecycle());
        root.visibleProperty().addListener((observable, before, visible) -> updatePreviewLifecycle());
    }

    public Node view() {
        preset.setButtonCell(presetCell());
        root.setVisible(true);
        updatePreviewLifecycle();
        return root;
    }

    public void deactivate() {
        root.setVisible(false);
        updatePreviewLifecycle();
    }

    private void configurePresets() {
        presets.put("月海极光", new VisualTheme("moon-sea", 0.72, 0.55, 0.48, 0.76, "#9B86FF"));
        presets.put("星云脉冲", new VisualTheme("nebula-pulse", 0.95, 1.15, 0.88, 0.63, "#FF5FD2"));
        presets.put("日蚀矩阵", new VisualTheme("eclipse-matrix", 0.54, 0.35, 0.22, 0.89, "#FF9F43"));
        presets.put("深海霓虹", new VisualTheme("abyss-neon", 0.86, 0.82, 0.62, 0.71, "#32E6D1"));
    }

    private void configure() {
        root.getStyleClass().add("page");
        Label eyebrow = new Label("SELENE SHADER GRAPH");
        eyebrow.getStyleClass().add("eyebrow");
        Label title = new Label("视觉工坊");
        title.getStyleClass().add("page-title");
        Label subtitle = new Label("图形化调整通用效果节点，实时生成可移植的 GLSL 风格程序；安全运行层会映射到 JavaFX。");
        subtitle.getStyleClass().add("page-subtitle");
        VBox heading = new VBox(3, eyebrow, title, subtitle);

        StackPane previewPane = new StackPane(preview);
        previewPane.getStyleClass().add("shader-preview");
        previewPane.setMinHeight(220);
        previewPane.setPrefHeight(280);
        previewPane.setMaxHeight(360);
        preview.setManaged(false);
        previewPane.widthProperty().addListener((observable, before, width) ->
                preview.setWidth(Math.clamp(width.doubleValue(), 320, 1600)));
        previewPane.heightProperty().addListener((observable, before, height) ->
                preview.setHeight(Math.clamp(height.doubleValue(), 220, 360)));
        Label previewBadge = new Label("LIVE GPU-SAFE PREVIEW");
        previewBadge.getStyleClass().add("preview-badge");
        StackPane.setAlignment(previewBadge, Pos.TOP_LEFT);
        StackPane.setMargin(previewBadge, new Insets(15));
        previewPane.getChildren().add(previewBadge);

        GridPane graph = new GridPane();
        graph.getStyleClass().add("shader-graph");
        graph.setHgap(12);
        graph.setVgap(11);
        graph.setPadding(new Insets(15));
        preset.getItems().setAll(presets.keySet());
        preset.setCellFactory(ignored -> presetCell());
        preset.setButtonCell(presetCell());
        preset.setOnAction(event -> {
            VisualTheme selected = presets.get(preset.getValue());
            if (selected != null) apply(selected);
        });
        graph.add(node("预设输入", preset), 0, 0);
        graph.add(node("光晕节点", glow), 1, 0);
        graph.add(node("流速节点", flow), 2, 0);
        graph.add(node("星尘节点", stars), 0, 1);
        graph.add(node("玻璃节点", glass), 1, 1);
        graph.add(node("强调色", accent), 2, 1);

        HBox buttons = new HBox(10);
        Button compile = new Button("编译效果图");
        compile.getStyleClass().add("primary-button");
        compile.setOnAction(event -> updateFromControls());
        Button save = new Button("保存并应用");
        save.getStyleClass().add("ghost-button");
        save.setOnAction(event -> save());
        buttons.getChildren().addAll(compile, save);

        shaderSource.setEditable(false);
        shaderSource.setWrapText(false);
        shaderSource.setPrefRowCount(12);
        shaderSource.getStyleClass().add("shader-code");
        VBox code = new VBox(8, new Label("生成的 Selene Universal Shader Language"), shaderSource, buttons);
        code.getStyleClass().add("settings-panel");
        code.setPadding(new Insets(15));
        VBox.setVgrow(shaderSource, Priority.ALWAYS);

        BorderPane workbench = new BorderPane();
        workbench.setTop(previewPane);
        workbench.setCenter(graph);
        workbench.setBottom(code);
        root.getChildren().addAll(heading, workbench);
        VBox.setVgrow(workbench, Priority.ALWAYS);
        I18n.localize(root);

        glow.valueProperty().addListener((observable, before, after) -> updateFromControls());
        flow.valueProperty().addListener((observable, before, after) -> updateFromControls());
        stars.valueProperty().addListener((observable, before, after) -> updateFromControls());
        glass.valueProperty().addListener((observable, before, after) -> updateFromControls());
        accent.valueProperty().addListener((observable, before, after) -> updateFromControls());
    }

    private ListCell<String> presetCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : I18n.text(item));
            }
        };
    }

    private VBox node(String name, Node control) {
        Label label = new Label("◉  " + name);
        label.getStyleClass().add("settings-title");
        VBox node = new VBox(9, label, control);
        node.getStyleClass().add("shader-node");
        node.setPadding(new Insets(12));
        node.setPrefWidth(185);
        return node;
    }

    private void apply(VisualTheme next) {
        theme = next;
        glow.setValue(next.glow());
        flow.setValue(next.flow());
        stars.setValue(next.stars());
        glass.setValue(next.glass());
        accent.setValue(Color.web(next.accent()));
        presets.entrySet().stream().filter(entry -> entry.getValue().preset().equals(next.preset()))
                .findFirst().ifPresent(entry -> preset.setValue(entry.getKey()));
        shaderSource.setText(next.shaderSource());
        applyTheme.accept(next);
    }

    private void updateFromControls() {
        String presetId = theme == null ? "custom" : theme.preset();
        theme = new VisualTheme(
                presetId, glow.getValue(), flow.getValue(), stars.getValue(), glass.getValue(), colorHex(accent.getValue())
        );
        shaderSource.setText(theme.shaderSource());
        applyTheme.accept(theme);
    }

    private void save() {
        updateFromControls();
        try {
            repository.save(theme);
        } catch (IOException exception) {
            errorHandler.accept("无法保存视觉主题：" + exception.getMessage());
        }
    }

    private AnimationTimer createPreviewTimer() {
        return new AnimationTimer() {
            @Override
            public void handle(long now) {
                draw((now - epoch) / 1_000_000_000d);
            }
        };
    }

    private void updatePreviewLifecycle() {
        boolean shouldRun = root.isVisible() && root.getScene() != null;
        if (shouldRun && !previewRunning) {
            epoch = System.nanoTime();
            previewTimer.start();
            previewRunning = true;
        } else if (!shouldRun && previewRunning) {
            previewTimer.stop();
            previewRunning = false;
        }
    }

    private void draw(double time) {
        if (theme == null || preview.getWidth() <= 0 || preview.getHeight() <= 0) return;
        GraphicsContext graphics = preview.getGraphicsContext2D();
        double width = preview.getWidth();
        double height = preview.getHeight();
        graphics.setFill(Color.web("#080A16"));
        graphics.fillRect(0, 0, width, height);
        Color color = Color.web(theme.accent());
        for (int layer = 0; layer < 5; layer++) {
            double phase = time * theme.flow() * (0.18 + layer * 0.04) + layer * 1.37;
            double x = width * (0.5 + 0.36 * Math.sin(phase));
            double y = height * (0.48 + 0.28 * Math.cos(phase * 0.73));
            double radius = (110 + layer * 26) * (0.55 + theme.glow());
            graphics.setFill(new Color(color.getRed(), color.getGreen(), color.getBlue(), 0.018 + theme.glow() * 0.025));
            graphics.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }
        int starCount = (int) (theme.stars() * 120);
        graphics.setFill(Color.rgb(230, 236, 255, 0.72));
        for (int index = 0; index < starCount; index++) {
            double x = pseudo(index * 17 + 3) * width;
            double y = pseudo(index * 31 + 9) * height;
            double pulse = 0.5 + 0.5 * Math.sin(time * (0.6 + pseudo(index) * 1.4) + index);
            double size = 0.7 + pulse * 1.5;
            graphics.fillOval(x, y, size, size);
        }
        graphics.setStroke(new Color(color.getRed(), color.getGreen(), color.getBlue(), 0.22));
        graphics.setLineWidth(1.2);
        for (int line = 0; line < 4; line++) {
            double y = height * (0.22 + line * 0.17) + Math.sin(time * theme.flow() + line) * 12;
            graphics.strokeArc(-width * 0.1, y - 80, width * 1.2, 170, 12, 156, javafx.scene.shape.ArcType.OPEN);
        }
    }

    private double pseudo(int seed) {
        double value = Math.sin(seed * 12.9898) * 43758.5453;
        return value - Math.floor(value);
    }

    private static Slider slider(double minimum, double maximum) {
        Slider slider = new Slider(minimum, maximum, minimum);
        slider.setShowTickMarks(false);
        slider.setPrefWidth(155);
        return slider;
    }

    private String colorHex(Color color) {
        return "#%02X%02X%02X".formatted(
                (int) Math.round(color.getRed() * 255),
                (int) Math.round(color.getGreen() * 255),
                (int) Math.round(color.getBlue() * 255)
        );
    }
}
