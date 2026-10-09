package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.mixin.MinecraftPickInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/** 26.1, 26.1.1 and 26.1.2: the screen and overlay still live on Minecraft. */
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
        ((MinecraftPickInvoker) minecraft).kohsAnchors$pick(1.0F);
    }

    public static void extractDeferredSubtitles(Minecraft minecraft) {
        minecraft.gui.extractDeferredSubtitles();
    }

    /** Block and sky light, 0 to 15, packed as the renderers take them. */
    public static int packLight(int block, int sky) {
        return net.minecraft.util.LightCoordsUtil.pack(block, sky);
    }

    private static final net.minecraft.client.renderer.Projection STAGE_PROJECTION = new net.minecraft.client.renderer.Projection();
    private static net.minecraft.client.renderer.ProjectionMatrixBuffer stageProjectionBuffer;

    /**
     * Puts what is drawn next in perspective, the projection built as the level's own is (depth
     * range and direction included). A picture-in-picture pass sets a flat projection for itself
     * before every draw, so nothing has to be put back.
     */
    public static void perspective(float fovDegrees, float width, float height, float near, float far) {
        if (stageProjectionBuffer == null) {
            stageProjectionBuffer = new net.minecraft.client.renderer.ProjectionMatrixBuffer("KoHs Anchor's stage");
        }
        STAGE_PROJECTION.setupPerspective(near, far, fovDegrees, width, height);
        com.mojang.blaze3d.systems.RenderSystem.setProjectionMatrix(stageProjectionBuffer.getBuffer(STAGE_PROJECTION),
                com.mojang.blaze3d.ProjectionType.PERSPECTIVE);
    }
}
