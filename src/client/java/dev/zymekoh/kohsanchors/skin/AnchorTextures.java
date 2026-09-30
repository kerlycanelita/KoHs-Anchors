package dev.zymekoh.kohsanchors.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.server.packs.resources.Resource;

/**
 * The respawn anchor's textures from the active resource packs, split into the anchor's two
 * layers.
 *
 * <p>The frame is the obsidian body. The glow is everything that lights up, found the same way for
 * any resource pack: pixels that change when the anchor charges (the portal on top, the four
 * charge lights on the sides) and pixels saturated and bright enough to glow on their own (the
 * crying veins). The charge lights also learn which charge turns each of them on, so every light
 * can take its own colour. A player can move any pixel to the other layer in the pixel editor.</p>
 */
public final class AnchorTextures {
    public static final byte FRAME = 1;
    public static final byte GLOW = 2;

    private final Map<AnchorVariant, Texture> textures = new EnumMap<>(AnchorVariant.class);

    private AnchorTextures() {
    }

    public Texture get(AnchorVariant variant) {
        return this.textures.get(variant);
    }

    /** The resolution of the side texture: 16, 32, 64, 128... */
    public int resolution() {
        Texture side = this.textures.get(AnchorVariant.SIDE0);
        return side == null ? 16 : side.width;
    }

    /** The resolution of one face, which may differ from the others in a mixed resource pack. */
    public int resolution(AnchorVariant.Face face) {
        return switch (face) {
            case TOP -> this.textures.get(AnchorVariant.TOP_OFF).width;
            case SIDE -> this.textures.get(AnchorVariant.SIDE0).width;
            case BOTTOM -> this.textures.get(AnchorVariant.BOTTOM).width;
        };
    }

    /**
     * Reads every anchor texture from the resource packs and analyses it. {@code atlas} gives each
     * sprite's frame size, so an animated texture is cut the way the game cuts it.
     *
     * @return the textures, or {@code null} when one of them could not be read
     */
    public static AnchorTextures load(Minecraft minecraft, TextureAtlas atlas) {
        AnchorTextures loaded = new AnchorTextures();
        for (AnchorVariant variant : AnchorVariant.values()) {
            Texture texture = read(minecraft, atlas, variant);
            if (texture == null) {
                return null;
            }
            loaded.textures.put(variant, texture);
        }
        loaded.analyse();
        return loaded;
    }

    private static Texture read(Minecraft minecraft, TextureAtlas atlas, AnchorVariant variant) {
        Optional<Resource> resource = minecraft.getResourceManager().getResource(variant.file());
        if (resource.isEmpty()) {
            KoHsAnchorsClient.LOGGER.warn("No texture {} in the resource packs; the anchor skin is off", variant.file());
            return null;
        }
        try (InputStream stream = resource.get().open(); NativeImage image = NativeImage.read(stream)) {
            int imageWidth = image.getWidth();
            int imageHeight = image.getHeight();
            int frameWidth = imageWidth;
            int frameHeight = imageWidth;
            TextureAtlasSprite sprite = atlas == null ? null : atlas.getSprite(variant.sprite());
            if (sprite != null && sprite.contents().width() > 0 && sprite.contents().width() <= imageWidth
                    && sprite.contents().height() <= imageHeight) {
                frameWidth = sprite.contents().width();
                frameHeight = sprite.contents().height();
            }
            if (frameHeight > imageHeight) {
                frameHeight = imageHeight;
            }
            int perRow = Math.max(1, imageWidth / frameWidth);
            int rows = Math.max(1, imageHeight / frameHeight);
            int frames = perRow * rows;
            if (variant != AnchorVariant.TOP) {
                // Only the portal top is animated in Vanilla; any other strip keeps its first frame,
                // which is what a static sprite shows.
                frames = 1;
            }
            int area = frameWidth * frameHeight;
            int[] pixels = new int[area * frames];
            for (int frame = 0; frame < frames; frame++) {
                int originX = (frame % perRow) * frameWidth;
                int originY = (frame / perRow) * frameHeight;
                for (int y = 0; y < frameHeight; y++) {
                    for (int x = 0; x < frameWidth; x++) {
                        pixels[frame * area + y * frameWidth + x] = image.getPixel(originX + x, originY + y);
                    }
                }
            }
            return new Texture(variant, frameWidth, frameHeight, frames, pixels, perRow);
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not read {}; the anchor skin is off", variant.file(), exception);
            return null;
        }
    }

    private void analyse() {
        float[] sv = new float[2];
        for (Texture texture : this.textures.values()) {
            int area = texture.width * texture.height;
            for (int index = 0; index < area; index++) {
                boolean glows = false;
                for (int frame = 0; frame < texture.frames && !glows; frame++) {
                    ColorMath.saturationValue(texture.pixels[frame * area + index], sv);
                    glows = sv[0] > 0.72F && sv[1] > 0.32F;
                }
                texture.layer[index] = glows ? GLOW : FRAME;
            }
        }
        // The portal: whatever differs between the charged top and the empty one.
        Texture on = this.textures.get(AnchorVariant.TOP);
        Texture off = this.textures.get(AnchorVariant.TOP_OFF);
        if (on.width == off.width && on.height == off.height) {
            int area = on.width * on.height;
            for (int index = 0; index < area; index++) {
                for (int frame = 0; frame < on.frames; frame++) {
                    if (ColorMath.differs(on.pixels[frame * area + index], off.pixels[index])) {
                        on.layer[index] = GLOW;
                        on.portal[index] = true;
                        break;
                    }
                }
            }
        }
        // The charge lights: a side pixel that differs from the empty side is lit, and the lowest
        // charge that lights it is its number.
        Texture empty = this.textures.get(AnchorVariant.SIDE0);
        for (int charge = 1; charge <= 4; charge++) {
            Texture side = this.textures.get(AnchorVariant.side(charge));
            if (side.width != empty.width || side.height != empty.height) {
                continue;
            }
            int area = side.width * side.height;
            for (int index = 0; index < area; index++) {
                if (ColorMath.differs(side.pixels[index], empty.pixels[index])) {
                    if (empty.chargeLight[index] == 0) {
                        empty.chargeLight[index] = (byte) charge;
                    }
                }
            }
        }
        for (int charge = 0; charge <= 4; charge++) {
            Texture side = this.textures.get(AnchorVariant.side(charge));
            if (side.width != empty.width || side.height != empty.height) {
                continue;
            }
            int area = side.width * side.height;
            for (int index = 0; index < area; index++) {
                byte light = empty.chargeLight[index];
                side.chargeLight[index] = light;
                if (light > 0 && light <= charge) {
                    side.layer[index] = GLOW;
                }
            }
        }
    }

    /** One anchor texture: its frames, and which layer each of its pixels belongs to. */
    public static final class Texture {
        public final AnchorVariant variant;
        public final int width;
        public final int height;
        public final int frames;
        /** Frames one after the other, each {@code width * height} ARGB pixels. */
        public final int[] pixels;
        /** Frames per row in the source strip: frame {@code i} is at column {@code i % perRow}. */
        public final int perRow;
        /** {@link #FRAME} or {@link #GLOW}, per pixel of a frame. */
        public final byte[] layer;
        /** For the sides: the charge, 1 to 4, that lights this pixel; 0 for any other pixel. */
        public final byte[] chargeLight;
        /** For the charged top: whether the pixel is part of the portal. */
        public final boolean[] portal;

        Texture(AnchorVariant variant, int width, int height, int frames, int[] pixels, int perRow) {
            this.variant = variant;
            this.width = width;
            this.height = height;
            this.frames = frames;
            this.pixels = pixels;
            this.perRow = perRow;
            this.layer = new byte[width * height];
            this.chargeLight = new byte[width * height];
            this.portal = new boolean[width * height];
        }

        public int area() {
            return this.width * this.height;
        }

        /** Whether the pixel at {@code index} is a charge light that this texture shows lit. */
        public boolean litChargeLight(int index) {
            int light = this.chargeLight[index];
            return light > 0 && this.variant.face() == AnchorVariant.Face.SIDE && light <= this.variant.charge();
        }
    }
}
