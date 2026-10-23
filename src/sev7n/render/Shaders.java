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
            uniform sampler2DShadow uShadowMap;
            uniform mat4 uShadowMatrix;
            uniform vec2 uShadow;           // x: strength (0 = no shadows), y: world size of a shadow map pixel

            out vec4 fragColor;
            """ + FINISH + """

            // how much sunlight reaches this point: 1 = all, less in shadow
            float sunlight(vec3 position, vec3 normal) {
                if (uShadow.x <= 0.0) return 1.0;
                vec4 p = uShadowMatrix * vec4(position + normal * uShadow.y * 1.5, 1.0);
                vec3 at = p.xyz / p.w * 0.5 + 0.5;
                float edge = max(abs(at.x - 0.5), abs(at.y - 0.5)) * 2.0;
                if (edge >= 1.0 || at.z >= 1.0) return 1.0;
                // 9 samples, each blended by the hardware, for soft edges
                vec2 texel = 1.0 / vec2(textureSize(uShadowMap, 0));
                float lit = 0.0;
                for (int x = -1; x <= 1; x++)
                    for (int y = -1; y <= 1; y++)
                        lit += texture(uShadowMap, vec3(at.xy + vec2(x, y) * texel, at.z - 0.0004));
                float shadow = (1.0 - lit / 9.0) * uShadow.x;
                return 1.0 - shadow * (1.0 - smoothstep(0.8, 1.0, edge)); // fades out at the shadow distance
            }

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
                float sun = sunlight(vWorldPosition, normal);
                float diffuse = max(dot(normal, toLight), 0.0) * sun;
                vec3 halfway = normalize(toLight + toEye);
                float highlight = pow(max(dot(normal, halfway), 0.0), 48.0) * 0.25 * sun;
                if (uShading == 2) {
                    diffuse = floor(diffuse * 3.0 + 0.5) / 3.0;          // three hard bands of light
                    highlight = highlight > 0.12 ? 0.35 : 0.0;           // one crisp shine
                }

                vec3 color = base * (uAmbient + diffuse * uLightColor) + highlight * uLightColor;
                fragColor = vec4(finish(color, vWorldPosition, uEye), 1.0);
            }
            """;

    /** The shadow map: only depth, as the light sees the scene. */
    static final String SHADOW_VERTEX = """
            #version 330 core
            layout(location = 0) in vec3 aPosition;
            layout(location = 2) in vec2 aUV;

            uniform mat4 uModel;
            uniform mat4 uLightMatrix;

            out vec2 vUV;

            void main() {
                vUV = aUV;
                gl_Position = uLightMatrix * uModel * vec4(aPosition, 1.0);
            }
            """;

    static final String SHADOW_FRAGMENT = """
            #version 330 core
            in vec2 vUV;

            uniform sampler2D uTexture;
            uniform float uUseTexture;

            void main() {
                if (uUseTexture > 0.5 && texture(uTexture, vUV).a < 0.5) discard;  // light passes through cut-outs
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
