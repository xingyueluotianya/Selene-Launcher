package io.selene.launcher.core.model;

import java.util.Locale;

public record VisualTheme(
        String preset,
        double glow,
        double flow,
        double stars,
        double glass,
        String accent
) {
    public VisualTheme {
        preset = preset == null || preset.isBlank() ? "moon-sea" : preset;
        glow = Math.clamp(glow, 0, 1);
        flow = Math.clamp(flow, 0, 2);
        stars = Math.clamp(stars, 0, 1);
        glass = Math.clamp(glass, 0, 1);
        accent = normalizeAccent(accent);
    }

    public static VisualTheme moonSea() {
        return new VisualTheme("moon-sea", 0.72, 0.55, 0.48, 0.76, "#9B86FF");
    }

    public String shaderSource() {
        return """
                // Selene Universal Shader Graph 1.0
                uniform float u_time;
                uniform vec2  u_resolution;
                uniform float u_glow   = %.3f;
                uniform float u_flow   = %.3f;
                uniform float u_stars  = %.3f;
                uniform float u_glass  = %.3f;
                uniform vec3  u_accent = vec3(%s);

                vec4 selene_fragment(vec2 uv) {
                    vec2 p = uv * 2.0 - 1.0;
                    float wave = sin((p.x + u_time * u_flow) * 3.14159) * 0.5 + 0.5;
                    float halo = exp(-dot(p, p) * (3.0 - u_glow));
                    float star = step(1.0 - u_stars * 0.035, selene_noise(floor(uv * 180.0)));
                    vec3 color = mix(vec3(0.025, 0.035, 0.085), u_accent, halo * wave);
                    color += star * u_stars;
                    return vec4(color, mix(0.78, 0.96, u_glass));
                }
                """.formatted(glow, flow, stars, glass, rgbExpression(accent));
    }

    private static String normalizeAccent(String value) {
        String result = value == null ? "#9B86FF" : value.trim().toUpperCase(Locale.ROOT);
        return result.matches("#[0-9A-F]{6}") ? result : "#9B86FF";
    }

    private static String rgbExpression(String color) {
        int red = Integer.parseInt(color.substring(1, 3), 16);
        int green = Integer.parseInt(color.substring(3, 5), 16);
        int blue = Integer.parseInt(color.substring(5, 7), 16);
        return "%.4f, %.4f, %.4f".formatted(red / 255d, green / 255d, blue / 255d);
    }
}
