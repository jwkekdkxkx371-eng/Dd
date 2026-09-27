package dev.dd.empty;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;
import net.minecraft.client.texture.AbstractTexture;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

import java.io.IOException;
import java.io.InputStream;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public final class MsdfClient implements ClientModInitializer {
    private static final Identifier FONT_DATA = Identifier.of("empty_mod", "textures/font/msdf.json");
    private static final Identifier FONT_TEXTURE = Identifier.of("empty_mod", "textures/font/msdf-atlas.png");
    private static final Identifier VERTEX_SHADER = Identifier.of("empty_mod", "shaders/msdf.vsh");
    private static final Identifier FRAGMENT_SHADER = Identifier.of("empty_mod", "shaders/msdf.fsh");
    private static final String LABEL = "vceeem";
    private static final float MAX_TEXT_SCALE = 0.5f;

    private static final Map<Character, Glyph> GLYPHS = new HashMap<>();
    private static int atlasWidth;
    private static int atlasHeight;
    private static float distanceRange;
    private static int shaderProgram;
    private static int vertexArray;
    private static int vertexBuffer;
    private static boolean initialized;
    private static boolean failed;

    @Override
    public void onInitializeClient() {
        HudRenderCallback.EVENT.register(MsdfClient::renderHud);
    }

    private static void renderHud(DrawContext context, net.minecraft.client.render.RenderTickCounter tickCounter) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (!InputUtil.isKeyPressed(client.getWindow().getHandle(), GLFW.GLFW_KEY_RIGHT_SHIFT) || failed) {
            return;
        }

        try {
            initialize(client);
            renderText(client, context);
        } catch (IOException | RuntimeException exception) {
            failed = true;
            System.err.println("[empty_mod] Could not initialize MSDF renderer: " + exception.getMessage());
        }
    }

    private static void initialize(MinecraftClient client) throws IOException {
        if (initialized) {
            return;
        }

        Resource fontResource = client.getResourceManager().getResource(FONT_DATA)
                .orElseThrow(() -> new IOException("Missing font metadata: " + FONT_DATA));
        try (InputStream input = fontResource.getInputStream()) {
            JsonObject root = JsonParser.parseString(new String(input.readAllBytes(), StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonObject common = root.getAsJsonObject("common");
            atlasWidth = common.get("scaleW").getAsInt();
            atlasHeight = common.get("scaleH").getAsInt();
            distanceRange = root.getAsJsonObject("distanceField").get("distanceRange").getAsFloat();

            JsonArray chars = root.getAsJsonArray("chars");
            for (JsonElement element : chars) {
                JsonObject glyph = element.getAsJsonObject();
                GLYPHS.put((char) glyph.get("id").getAsInt(), new Glyph(
                        glyph.get("x").getAsInt(),
                        glyph.get("y").getAsInt(),
                        glyph.get("width").getAsInt(),
                        glyph.get("height").getAsInt(),
                        glyph.get("xoffset").getAsInt(),
                        glyph.get("yoffset").getAsInt(),
                        glyph.get("xadvance").getAsInt()
                ));
            }
        }

        shaderProgram = createProgram(client);
        vertexArray = GL30.glGenVertexArrays();
        vertexBuffer = GL15.glGenBuffers();
        int oldVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 0L);
        GL20.glEnableVertexAttribArray(1);
        GL20.glVertexAttribPointer(1, 2, GL11.GL_FLOAT, false, 4 * Float.BYTES, 2L * Float.BYTES);
        GL30.glBindVertexArray(oldVertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArrayBuffer);
        initialized = true;
    }

    private static int createProgram(MinecraftClient client) throws IOException {
        int vertexShader = compileShader(client, VERTEX_SHADER, GL20.GL_VERTEX_SHADER);
        int fragmentShader = compileShader(client, FRAGMENT_SHADER, GL20.GL_FRAGMENT_SHADER);
        int program = GL20.glCreateProgram();
        GL20.glAttachShader(program, vertexShader);
        GL20.glAttachShader(program, fragmentShader);
        GL20.glBindAttribLocation(program, 0, "Position");
        GL20.glBindAttribLocation(program, 1, "UV0");
        GL20.glLinkProgram(program);
        GL20.glDeleteShader(vertexShader);
        GL20.glDeleteShader(fragmentShader);

        if (GL20.glGetProgrami(program, GL20.GL_LINK_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetProgramInfoLog(program);
            GL20.glDeleteProgram(program);
            throw new IOException("MSDF shader link failed: " + log);
        }
        return program;
    }

    private static int compileShader(MinecraftClient client, Identifier id, int type) throws IOException {
        Resource resource = client.getResourceManager().getResource(id)
                .orElseThrow(() -> new IOException("Missing shader: " + id));
        String source;
        try (InputStream input = resource.getInputStream()) {
            source = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }

        int shader = GL20.glCreateShader(type);
        GL20.glShaderSource(shader, source);
        GL20.glCompileShader(shader);
        if (GL20.glGetShaderi(shader, GL20.GL_COMPILE_STATUS) == GL11.GL_FALSE) {
            String log = GL20.glGetShaderInfoLog(shader);
            GL20.glDeleteShader(shader);
            throw new IOException("MSDF shader compile failed (" + id + "): " + log);
        }
        return shader;
    }

    private static void renderText(MinecraftClient client, DrawContext context) {
        float unscaledWidth = 0;
        for (int index = 0; index < LABEL.length(); index++) {
            Glyph glyph = GLYPHS.get(LABEL.charAt(index));
            if (glyph != null) {
                unscaledWidth += glyph.xAdvance;
            }
        }

        float screenWidth = context.getScaledWindowWidth();
        float screenHeight = context.getScaledWindowHeight();
        float textScale = Math.min(MAX_TEXT_SCALE, screenWidth * 0.8f / unscaledWidth);
        float totalWidth = unscaledWidth * textScale;
        float penX = (screenWidth - totalWidth) * 0.5f;
        float baselineY = screenHeight * 0.5f - 24.0f * textScale;
        FloatBuffer vertices = BufferUtils.createFloatBuffer(LABEL.length() * 6 * 4);

        for (int index = 0; index < LABEL.length(); index++) {
            Glyph glyph = GLYPHS.get(LABEL.charAt(index));
            if (glyph == null) {
                continue;
            }

            if (glyph.width > 0 && glyph.height > 0) {
                float left = penX + glyph.xOffset * textScale;
                float top = baselineY + glyph.yOffset * textScale;
                float right = left + glyph.width * textScale;
                float bottom = top + glyph.height * textScale;
                float u0 = (float) glyph.x / atlasWidth;
                float v0 = (float) glyph.y / atlasHeight;
                float u1 = (float) (glyph.x + glyph.width) / atlasWidth;
                float v1 = (float) (glyph.y + glyph.height) / atlasHeight;

                putVertex(vertices, left, top, u0, v0);
                putVertex(vertices, right, top, u1, v0);
                putVertex(vertices, left, bottom, u0, v1);
                putVertex(vertices, left, bottom, u0, v1);
                putVertex(vertices, right, top, u1, v0);
                putVertex(vertices, right, bottom, u1, v1);
            }
            penX += glyph.xAdvance * textScale;
        }
        vertices.flip();

        int oldProgram = GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM);
        int oldVertexArray = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        int oldArrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
        int oldActiveTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        boolean oldBlend = GL11.glIsEnabled(GL11.GL_BLEND);
        boolean oldCull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean oldDepthTest = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean oldDepthWrite = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        boolean oldScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        IntBuffer oldScissorBox = BufferUtils.createIntBuffer(4);
        GL11.glGetIntegerv(GL11.GL_SCISSOR_BOX, oldScissorBox);
        int oldBlendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        int oldBlendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        int oldBlendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        int oldBlendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);

        GL13.glActiveTexture(GL13.GL_TEXTURE0);
        int oldTexture = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        AbstractTexture texture = client.getTextureManager().getTexture(FONT_TEXTURE);
        GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture.getGlId());
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

        GL11.glEnable(GL11.GL_BLEND);
        GL14.glBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA,
                GL11.GL_ONE, GL11.GL_ONE_MINUS_SRC_ALPHA);
        GL11.glDisable(GL11.GL_CULL_FACE);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glDepthMask(false);
        GL11.glDisable(GL11.GL_SCISSOR_TEST);
        GL20.glUseProgram(shaderProgram);
        GL20.glUniform2f(GL20.glGetUniformLocation(shaderProgram, "ScreenSize"), screenWidth, screenHeight);
        GL20.glUniform4f(GL20.glGetUniformLocation(shaderProgram, "Color"), 1.0f, 1.0f, 1.0f, 1.0f);
        GL20.glUniform1f(GL20.glGetUniformLocation(shaderProgram, "PxRange"), distanceRange);
        GL20.glUniform1i(GL20.glGetUniformLocation(shaderProgram, "Sampler0"), 0);
        GL30.glBindVertexArray(vertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, vertexBuffer);
        GL15.glBufferData(GL15.GL_ARRAY_BUFFER, vertices, GL15.GL_STREAM_DRAW);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, vertices.remaining() / 4);

        GL11.glBindTexture(GL11.GL_TEXTURE_2D, oldTexture);
        GL13.glActiveTexture(oldActiveTexture);
        GL20.glUseProgram(oldProgram);
        GL30.glBindVertexArray(oldVertexArray);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, oldArrayBuffer);
        GL14.glBlendFuncSeparate(oldBlendSrcRgb, oldBlendDstRgb, oldBlendSrcAlpha, oldBlendDstAlpha);
        GL11.glDepthMask(oldDepthWrite);
        GL11.glScissor(oldScissorBox.get(0), oldScissorBox.get(1), oldScissorBox.get(2), oldScissorBox.get(3));
        setEnabled(GL11.GL_BLEND, oldBlend);
        setEnabled(GL11.GL_CULL_FACE, oldCull);
        setEnabled(GL11.GL_DEPTH_TEST, oldDepthTest);
        setEnabled(GL11.GL_SCISSOR_TEST, oldScissor);
    }

    private static void putVertex(FloatBuffer buffer, float x, float y, float u, float v) {
        buffer.put(x).put(y).put(u).put(v);
    }

    private static void setEnabled(int capability, boolean enabled) {
        if (enabled) {
            GL11.glEnable(capability);
        } else {
            GL11.glDisable(capability);
        }
    }

    private record Glyph(int x, int y, int width, int height, int xOffset, int yOffset, int xAdvance) {
    }
}