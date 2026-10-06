package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

/** The particle sprites the mascot draws with, from KoHs Inventory Tweaks' scenes. */
final class ZScene {
    private ZScene() {
    }

    /** One particle sprite, {@code size} across, centred on {@code cx, cy} and tinted. */
    public static void sprite(
        final GuiGraphicsExtractor graphics,
        final String name,
        final float cx,
        final float cy,
        final float size,
        final int textureSize,
        final int color
    ) {
        if ((color >>> 24) < 4 || size <= 0.5F) {
            return;
        }
        Matrix3x2fStack pose = graphics.pose();
        pose.pushMatrix();
        pose.translate(cx - size * 0.5F, cy - size * 0.5F);
        pose.scale(size / textureSize, size / textureSize);
        graphics.blit(RenderPipelines.GUI_TEXTURED, Identifier.withDefaultNamespace("textures/particle/" + name + ".png"),
            0, 0, 0.0F, 0.0F, textureSize, textureSize, textureSize, textureSize, textureSize, textureSize, color);
        pose.popMatrix();
    }
}
