package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTexture;
import com.mojang.blaze3d.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** 1.21.11: {@code writeToTexture} also takes the size to write and the source corner. */
public final class AtlasWriter {
    private AtlasWriter() {
    }

    public static int mipLevels(TextureAtlas atlas) {
        return atlas.getTexture().getMipLevels();
    }

    public static void write(TextureAtlas atlas, NativeImage image, int mip, int x, int y) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(atlas.getTexture(), image, mip, 0, x, y,
                image.getWidth(), image.getHeight(), 0, 0);
    }

    public static int frameMipLevels(Object view) {
        return ((GpuTextureView) view).texture().getMipLevels();
    }

    public static void writeFrame(Object view, NativeImage image, int mip) {
        GpuTexture texture = ((GpuTextureView) view).texture();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, mip, 0, 0, 0,
                image.getWidth(), image.getHeight(), 0, 0);
    }
}
