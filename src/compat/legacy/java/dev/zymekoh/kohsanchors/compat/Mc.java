package dev.zymekoh.kohsanchors.compat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/** 1.21.11: the screen lives on Minecraft and the crosshair raycast on GameRenderer. */
public final class Mc {
    private Mc() {
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.screen;
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.getOverlay();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static void pick(Minecraft minecraft) {
        minecraft.gameRenderer.pick(1.0F);
    }

    /** 1.21.11 draws subtitles with the HUD, not from the screen background. */
    public static void extractDeferredSubtitles(Minecraft minecraft) {
    }

    /** Block and sky light, 0 to 15, packed as the renderers take them. */
    public static int packLight(int block, int sky) {
        return net.minecraft.client.renderer.LightTexture.pack(block, sky);
    }

    private static net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer stageProjectionBuffer;

    /**
     * Puts what is drawn next in perspective, the projection built as the level's own is. A
     * picture-in-picture pass sets a flat projection for itself before every draw, so nothing has to
     * be put back. 1.21.11's buffer keeps the first near and far planes it is given.
     */
    public static void perspective(float fovDegrees, float width, float height, float near, float far) {
        if (stageProjectionBuffer == null) {
            stageProjectionBuffer = new net.minecraft.client.renderer.CachedPerspectiveProjectionMatrixBuffer(
                    "KoHs Anchor's stage", near, far);
        }
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(
                stageProjectionBuffer.getBuffer(Math.max(1, Math.round(width)), Math.max(1, Math.round(height)), fovDegrees),
                com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
    }
}
