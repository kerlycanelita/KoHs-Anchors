package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/**
 * A texture for video frames: Vanilla's dynamic texture, sampled smoothly instead of pixel by
 * pixel, so a clip drawn larger or smaller than its own size stays a picture instead of blocks.
 *
 * <p>This copy is for 26.2, 26.1.x and 1.21.11; 26.3 keeps the filter modes in
 * {@code com.mojang.renderpearl} and has its own.</p>
 */
public final class VideoTexture extends DynamicTexture {
    public VideoTexture(String label, int width, int height) {
        super(() -> label, width, height, true);
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
