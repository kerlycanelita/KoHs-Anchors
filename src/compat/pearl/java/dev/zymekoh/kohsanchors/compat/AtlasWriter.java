package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.minecraft.client.renderer.texture.TextureAtlas;

/** 26.3: the GPU classes live in {@code com.mojang.renderpearl}; the calls are 26.2's. */
public final class AtlasWriter {
    private AtlasWriter() {
    }

    public static int mipLevels(TextureAtlas atlas) {
        return atlas.getTexture().getMipLevels();
    }

    public static void write(TextureAtlas atlas, NativeImage image, int mip, int x, int y) {
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(atlas.getTexture(), image, mip, 0, x, y);
    }

    public static int frameMipLevels(Object view) {
        return ((GpuTextureView) view).texture().getMipLevels();
    }

    public static void writeFrame(Object view, NativeImage image, int mip) {
        GpuTexture texture = ((GpuTextureView) view).texture();
        RenderSystem.getDevice().createCommandEncoder().writeToTexture(texture, image, mip, 0, 0, 0);
    }
}
