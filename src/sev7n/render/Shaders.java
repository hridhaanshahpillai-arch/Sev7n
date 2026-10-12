package sev7n.render;

/** GLSL source for the built-in shaders. */
final class Shaders {

    private Shaders() { }

    /** Solid objects: one directional light, ambient light, a soft highlight, optional texture. */
    static final String LIT_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUV;

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;
            uniform vec4 uUVRect;   // which part of the picture to show: offset (xy), size (zw)

            out vec3 vWorldPosition;
            out vec3 vNormal;
            out vec2 vUV;

            void main() {
                vec4 world = uModel * vec4(aPosition, 1.0);
                vWorldPosition = world.xyz;
                vNormal = mat3(transpose(inverse(uModel))) * aNormal;
                vUV = uUVRect.xy + aUV * uUVRect.zw;
                gl_Position = uProjection * uView * world;
            }
            """;

    /** Fog and brightness, shared by the lit and sprite shaders (see RenderSettings). */
    private static final String FINISH = """
            uniform vec3 uFogColor;
            uniform float uFogStart;
            uniform float uFogEnd;          // fog is off when start >= end
            uniform float uBrightness;      // 1 = normal

            vec3 finish(vec3 color, vec3 worldPosition, vec3 eye) {
                if (uFogEnd > uFogStart) {
                    float fog = smoothstep(uFogStart, uFogEnd, distance(worldPosition, eye));
                    color = mix(color, uFogColor, fog);
                }
                return pow(max(color, vec3(0.0)), vec3(1.0 / uBrightness));
            }
            """;

    static final String LIT_FRAGMENT = """
            #version 330 core
            in vec3 vWorldPosition;
            in vec3 vNormal;
            in vec2 vUV;

            uniform vec3 uColor;
            uniform vec3 uEye;
            uniform vec3 uLightDirection;   // the direction light travels
            uniform vec3 uLightColor;
            uniform float uAmbient;
            uniform sampler2D uTexture;
            uniform float uUseTexture;      // 1 = multiply by the texture
            uniform int uShading;           // 0 lit, 1 flat, 2 toon (wireframe draws lit lines)

            out vec4 fragColor;
            """ + FINISH + """

            void main() {
                vec4 texel = uUseTexture > 0.5 ? texture(uTexture, vUV) : vec4(1.0);
                if (texel.a < 0.5) discard;  // cut-out transparency (leaves, fences)
                vec3 base = uColor * texel.rgb;
                if (uShading == 1) {
                    fragColor = vec4(finish(base, vWorldPosition, uEye), 1.0);
                    return;
                }

                vec3 normal = normalize(vNormal);
                // planes are two-sided: flip the normal when we see the back
                vec3 toEye = normalize(uEye - vWorldPosition);
                if (dot(normal, toEye) < 0.0) normal = -normal;

                vec3 toLight = normalize(-uLightDirection);
                float diffuse = max(dot(normal, toLight), 0.0);
                vec3 halfway = normalize(toLight + toEye);
                float highlight = pow(max(dot(normal, halfway), 0.0), 48.0) * 0.25;
                if (uShading == 2) {
                    diffuse = floor(diffuse * 3.0 + 0.5) / 3.0;          // three hard bands of light
                    highlight = highlight > 0.12 ? 0.35 : 0.0;           // one crisp shine
                }

                vec3 color = base * (uAmbient + diffuse * uLightColor) + highlight * uLightColor;
                fragColor = vec4(finish(color, vWorldPosition, uEye), 1.0);
            }
            """;

    /** Sprites: a picture times a tint, unaffected by lighting, with smooth transparency. */
    static final String SPRITE_FRAGMENT = """
            #version 330 core
            in vec3 vWorldPosition;
            in vec2 vUV;

            uniform vec4 uTint;
            uniform sampler2D uTexture;
            uniform float uUseTexture;
            uniform vec3 uEye;

            out vec4 fragColor;
            """ + FINISH + """

            void main() {
                vec4 texel = uUseTexture > 0.5 ? texture(uTexture, vUV) : vec4(1.0);
                vec4 color = texel * uTint;
                if (color.a < 0.01) discard;
                fragColor = vec4(finish(color.rgb, vWorldPosition, uEye), color.a);
            }
            """;

    /** Flat-colored lines (grid, outlines) that can fade out with distance. */
    static final String UNLIT_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;

            uniform mat4 uModel;
            uniform mat4 uView;
            uniform mat4 uProjection;

            out vec3 vWorldPosition;

            void main() {
                vec4 world = uModel * vec4(aPosition, 1.0);
                vWorldPosition = world.xyz;
                gl_Position = uProjection * uView * world;
            }
            """;

    static final String UNLIT_FRAGMENT = """
            #version 330 core
            in vec3 vWorldPosition;

            uniform vec3 uColor;
            uniform vec3 uEye;
            uniform float uAlpha;
            uniform float uFadeStart;
            uniform float uFadeEnd;

            out vec4 fragColor;

            void main() {
                float fade = 1.0 - smoothstep(uFadeStart, uFadeEnd, distance(vWorldPosition, uEye));
                fragColor = vec4(uColor, uAlpha * fade);
            }
            """;
}
