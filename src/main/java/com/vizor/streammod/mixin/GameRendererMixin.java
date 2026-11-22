package com.vizor.streammod.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    private static ByteBuffer streammod$pixelBuffer;
    private static int streammod$lastW;
    private static int streammod$lastH;

    @Inject(method = "render", at = @At("TAIL"))
    private void streammod$grabFrame(float tickDelta, long nanoTime, boolean renderBlockOutline, CallbackInfo ci) {

        if (!com.vizor.streammod.Streamer.isStreaming()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        int w = client.getWindow().getFramebufferWidth();
        int h = client.getWindow().getFramebufferHeight();

        if (w <= 0 || h <= 0) return;

        int bufSize = w * h * 4;
        if (streammod$pixelBuffer == null || streammod$lastW != w || streammod$lastH != h) {
            streammod$pixelBuffer = ByteBuffer.allocateDirect(bufSize);
            streammod$lastW = w;
            streammod$lastH = h;
        }

        streammod$pixelBuffer.clear();
        streammod$pixelBuffer.position(0);

        GL11.glReadPixels(0, 0, w, h, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, streammod$pixelBuffer);

        com.vizor.streammod.Streamer.grabFrame(w, h, streammod$pixelBuffer);
    }
}
