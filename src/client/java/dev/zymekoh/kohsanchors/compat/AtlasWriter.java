package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.TextureAtlas;

/**
 * Writes pixels into the block atlas and into the frames of an animated sprite, the same calls
 * Vanilla makes when it uploads them.
 *
 * <p>This copy is for 26.2, where {@code CommandEncoder.writeToTexture} takes the destination
 * corner. 26.3 moved the GPU classes to {@code com.mojang.renderpearl}; 26.1.x and 1.21.11 also
 * take the size and source corner. The build swaps in the right copy; all keep these methods.</p>
 */
public final class AtlasWriter {
    private AtlasWriter() {
    }

    public static int mipLevels(TextureAtlas atlas) {
        return atlas.getTexture().getMipLevels();
    }

    /** {@code image} at mip level {@code mip}, with its top left corner at {@code x, y} of that level. */
    public static void write(TextureAtlas atlas, NativeImage image, int mip, int x, int y) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(atlas.getTexture(), image, mip, 0, x, y);
    }

    /** The mip levels of one frame of an animated sprite; {@code view} is Vanilla's view of it. */
    public static int frameMipLevels(Object view) {
        return ((GpuTextureView) view).texture().getMipLevels();
    }

    /** One frame of an animated sprite, at mip level {@code mip}. */
    public static void writeFrame(Object view, NativeImage image, int mip) {
        GpuTexture texture = ((GpuTextureView) view).texture();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, mip, 0, 0, 0);
    }
}
