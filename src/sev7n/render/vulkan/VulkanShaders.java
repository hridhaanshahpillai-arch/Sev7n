package sev7n.render.vulkan;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/**
 * The built-in shaders for Vulkan: the same look as the OpenGL ones (render/Shaders.java),
 * written for Vulkan's GLSL and compiled to SPIR-V with shaderc when the game starts.
 *
 * What every draw in a frame shares comes from the Frame uniform buffer; what changes per
 * draw (its matrix, color, picture area) comes in push constants.
 */
final class VulkanShaders {

    private VulkanShaders() { }

    /** Bytes of the per-draw push constants: mat4 model, vec4 color, vec4 uvRect, vec4 params. */
    static final int PUSH_SIZE = 112;
    /** Bytes of the Frame uniform buffer (std140). */
    static final int FRAME_SIZE = 208;

    private static final String COMMON = """
            #version 450
            layout(set = 0, binding = 0) uniform Frame {
                mat4 view;
                mat4 projection;
                vec4 eye;
                vec4 lightDirection;  // the direction light travels
                vec4 lightColor;      // w: ambient light
                vec4 fogColor;
                vec4 finish;          // x: fog start, y: fog end (off when start >= end), z: brightness, w: shading
            } frame;
            layout(push_constant) uniform Draw {
                mat4 model;
                vec4 color;           // lit: color; sprites: tint; lines: color and alpha
                vec4 uvRect;          // which part of the picture to show: offset (xy), size (zw)
                vec4 params;          // x: 1 = use the texture; z, w: lines fade start and end
            } draw;
            """;

    /** OpenGL's clip space to Vulkan's: y points down, and depth goes 0..1 instead of -1..1. */
    private static final String TO_VULKAN = """
            vec4 toVulkan(vec4 p) {
                p.y = -p.y;
                p.z = (p.z + p.w) * 0.5;
                return p;
            }
            """;

    static final String LIT_VERTEX = COMMON + TO_VULKAN + """
            layout(location = 0) in vec3 aPosition;
            layout(location = 1) in vec3 aNormal;
            layout(location = 2) in vec2 aUV;
            layout(location = 0) out vec3 vWorldPosition;
            layout(location = 1) out vec3 vNormal;
            layout(location = 2) out vec2 vUV;

            void main() {
                vec4 world = draw.model * vec4(aPosition, 1.0);
                vWorldPosition = world.xyz;
                vNormal = mat3(transpose(inverse(draw.model))) * aNormal;
                vUV = draw.uvRect.xy + aUV * draw.uvRect.zw;
                gl_Position = toVulkan(frame.projection * frame.view * world);
            }
            """;

    private static final String FINISH = """
            vec3 finish(vec3 color, vec3 worldPosition) {
                if (frame.finish.y > frame.finish.x) {
                    float fog = smoothstep(frame.finish.x, frame.finish.y, distance(worldPosition, frame.eye.xyz));
                    color = mix(color, frame.fogColor.rgb, fog);
                }
                return pow(max(color, vec3(0.0)), vec3(1.0 / frame.finish.z));
            }
            """;

    static final String LIT_FRAGMENT = COMMON + FINISH + """
            layout(set = 1, binding = 0) uniform sampler2D uTexture;
            layout(location = 0) in vec3 vWorldPosition;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUV;
            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 texel = draw.params.x > 0.5 ? texture(uTexture, vUV) : vec4(1.0);
                if (texel.a < 0.5) discard;  // cut-out transparency (leaves, fences)
                vec3 base = draw.color.rgb * texel.rgb;
                int shading = int(frame.finish.w + 0.5);  // 0 lit, 1 flat, 2 toon
                if (shading == 1) {
                    fragColor = vec4(finish(base, vWorldPosition), 1.0);
                    return;
                }

                vec3 normal = normalize(vNormal);
                // planes are two-sided: flip the normal when we see the back
                vec3 toEye = normalize(frame.eye.xyz - vWorldPosition);
                if (dot(normal, toEye) < 0.0) normal = -normal;

                vec3 toLight = normalize(-frame.lightDirection.xyz);
                float diffuse = max(dot(normal, toLight), 0.0);
                vec3 halfway = normalize(toLight + toEye);
                float highlight = pow(max(dot(normal, halfway), 0.0), 48.0) * 0.25;
                if (shading == 2) {
                    diffuse = floor(diffuse * 3.0 + 0.5) / 3.0;
                    highlight = highlight > 0.12 ? 0.35 : 0.0;
                }

                vec3 color = base * (frame.lightColor.w + diffuse * frame.lightColor.rgb) + highlight * frame.lightColor.rgb;
                fragColor = vec4(finish(color, vWorldPosition), 1.0);
            }
            """;

    static final String SPRITE_FRAGMENT = COMMON + FINISH + """
            layout(set = 1, binding = 0) uniform sampler2D uTexture;
            layout(location = 0) in vec3 vWorldPosition;
            layout(location = 1) in vec3 vNormal;
            layout(location = 2) in vec2 vUV;
            layout(location = 0) out vec4 fragColor;

            void main() {
                vec4 texel = draw.params.x > 0.5 ? texture(uTexture, vUV) : vec4(1.0);
                vec4 color = texel * draw.color;
                if (color.a < 0.01) discard;
                fragColor = vec4(finish(color.rgb, vWorldPosition), color.a);
            }
            """;

    static final String LINES_VERTEX = COMMON + TO_VULKAN + """
            layout(location = 0) in vec3 aPosition;
            layout(location = 0) out vec3 vWorldPosition;

            void main() {
                vec4 world = draw.model * vec4(aPosition, 1.0);
                vWorldPosition = world.xyz;
                gl_Position = toVulkan(frame.projection * frame.view * world);
            }
            """;

    static final String LINES_FRAGMENT = COMMON + """
            layout(location = 0) in vec3 vWorldPosition;
            layout(location = 0) out vec4 fragColor;

            void main() {
                float fade = 1.0 - smoothstep(draw.params.z, draw.params.w, distance(vWorldPosition, frame.eye.xyz));
                fragColor = vec4(draw.color.rgb, draw.color.a * fade);
            }
            """;

    /** Compiles GLSL to SPIR-V. The caller frees the result with MemoryUtil.memFree. */
    static ByteBuffer compile(String source, boolean fragment, String name) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        try {
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_performance);
            long result = shaderc_compile_into_spv(compiler, source,
                    fragment ? shaderc_fragment_shader : shaderc_vertex_shader, name, "main", options);
            try {
                if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success) {
                    throw new IllegalStateException("Shader " + name + " didn't compile: " + shaderc_result_get_error_message(result));
                }
                ByteBuffer spirv = shaderc_result_get_bytes(result);
                ByteBuffer copy = MemoryUtil.memAlloc(spirv.remaining());
                copy.put(spirv).flip();
                return copy;
            } finally {
                shaderc_result_release(result);
            }
        } finally {
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
