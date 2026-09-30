package dev.zymekoh.kohsanchors.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** 26.3: the same texture; the filter modes live in {@code com.mojang.renderpearl}. */
public final class VideoTexture extends DynamicTexture {
    public VideoTexture(String label, int width, int height) {
        super(() -> label, width, height, true);
        this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
    }
}
