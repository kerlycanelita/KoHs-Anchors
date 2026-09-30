package dev.zymekoh.kohsanchors.compat;

import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;

/**
 * 1.21.11: Vanilla's dragon-ray material is already the glow's: additive, tested against depth and
 * writing none.
 */
public final class GlowMaterial {
    private GlowMaterial() {
    }

    public static RenderType glow() {
        return RenderTypes.dragonRays();
    }
}
