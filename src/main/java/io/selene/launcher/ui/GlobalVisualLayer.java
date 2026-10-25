package io.selene.launcher.ui;

import io.selene.launcher.core.model.VisualTheme;
import javafx.animation.AnimationTimer;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.paint.CycleMethod;
import javafx.scene.paint.LinearGradient;
import javafx.scene.paint.RadialGradient;
import javafx.scene.paint.Stop;

/** Animated application-wide background driven by the safe visual theme parameters. */
public final class GlobalVisualLayer extends Canvas {
    private VisualTheme theme = VisualTheme.moonSea();
    private final AnimationTimer timer;
    private long epoch = System.nanoTime();

    public GlobalVisualLayer() {
        setManaged(false);
        setMouseTransparent(true);
        timer = new AnimationTimer() {
            @Override public void handle(long now) {
                draw((now - epoch) / 1_000_000_000d);
            }
        };
        timer.start();
    }

    public void apply(VisualTheme theme) {
        this.theme = theme == null ? VisualTheme.moonSea() : theme;
        epoch = System.nanoTime();
        draw(0);
    }

    private void draw(double time) {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0 || height <= 0) return;
        GraphicsContext graphics = getGraphicsContext2D();
        Color accent = Color.web(theme.accent());
        Color deep = accent.interpolate(Color.web("#060812"), 0.88);
        graphics.setFill(new LinearGradient(0, 0, 1, 1, true, CycleMethod.NO_CYCLE,
                new Stop(0, Color.web("#070912")), new Stop(0.55, deep), new Stop(1, Color.web("#05070d"))));
        graphics.fillRect(0, 0, width, height);

        for (int layer = 0; layer < 5; layer++) {
            double phase = time * theme.flow() * (0.05 + layer * 0.012) + layer * 1.8;
            double x = width * (0.50 + Math.sin(phase) * 0.43);
            double y = height * (0.40 + Math.cos(phase * 0.77) * 0.38);
            double radius = Math.max(width, height) * (0.24 + layer * 0.035) * (0.72 + theme.glow() * 0.48);
            Color glow = new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.035 + theme.glow() * 0.018);
            graphics.setFill(new RadialGradient(0, 0, x, y, radius, false, CycleMethod.NO_CYCLE,
                    new Stop(0, glow), new Stop(1, Color.TRANSPARENT)));
            graphics.fillOval(x - radius, y - radius, radius * 2, radius * 2);
        }

        int stars = (int) (theme.stars() * Math.min(230, width * height / 7500));
        for (int index = 0; index < stars; index++) {
            double x = pseudo(index * 31 + 7) * width;
            double y = pseudo(index * 19 + 13) * height;
            double pulse = 0.35 + 0.65 * Math.sin(time * (0.25 + pseudo(index) * 0.7) + index) * 0.5 + 0.5;
            graphics.setFill(Color.rgb(225, 232, 255, 0.16 + pulse * 0.34));
            double size = 0.6 + pseudo(index * 47) * 1.25;
            graphics.fillOval(x, y, size, size);
        }

        graphics.setStroke(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 0.05 + theme.glow() * 0.05));
        graphics.setLineWidth(1.1);
        for (int line = 0; line < 5; line++) {
            double baseY = height * (0.15 + line * 0.18);
            graphics.beginPath();
            for (int step = 0; step <= 60; step++) {
                double x = width * step / 60d;
                double y = baseY + Math.sin(step * 0.19 + time * theme.flow() * 0.35 + line) * (8 + line * 2);
                if (step == 0) graphics.moveTo(x, y); else graphics.lineTo(x, y);
            }
            graphics.stroke();
        }
    }

    private double pseudo(int seed) {
        double value = Math.sin(seed * 12.9898) * 43758.5453;
        return value - Math.floor(value);
    }
}
