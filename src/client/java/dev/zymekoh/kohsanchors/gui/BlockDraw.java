package dev.zymekoh.kohsanchors.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * A block drawn by the game's own renderer, centred on a point and turned. The screen keeps what it
 * is given until the frame is drawn, so every block gets its own rotation and translation.
 */
final class BlockDraw {
    void draw(GuiGraphicsExtractor graphics, EntityRenderState state, int centerX, int centerY, float size, float yaw,
            float pitch) {
        if (size < 1.5F) {
            return;
        }
        Quaternionf rotation = new Quaternionf().rotateZ((float) Math.PI).rotateX((float) Math.toRadians(pitch))
                .rotateY((float) Math.toRadians(yaw));
        Vector3f translation = rotation.transform(new Vector3f(0.0F, 0.5F, 0.0F)).negate();
        int half = Math.round(size);
        graphics.entity(state, size / 1.6F, translation, rotation, new Quaternionf(), centerX - half, centerY - half,
                centerX + half, centerY + half);
    }
}
