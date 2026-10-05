package dev.zymekoh.kohsanchors.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.compat.AtlasWriter;
import dev.zymekoh.kohsanchors.compat.Mc;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.mixin.AnimationStateAccessor;
import dev.zymekoh.kohsanchors.mixin.TextureAtlasSpriteAccessor;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;

/**
 * Puts the anchor skin into the game: the respawn anchor's sprites in the block atlas are
 * overwritten with the skinned pixels, so every anchor in the world, its item and every preview
 * draw it without a single extra draw call or model change. Sodium, which meshes from the same
 * atlas, draws it too.
 *
 * <p>Static sprites are written straight into their place in the atlas, at every mip level,
 * padding included. The charged top is animated: Vanilla keeps each frame in its own small
 * texture and draws the current one into the atlas as the animation runs, so the frames are
 * rewritten there instead and the animation carries the skin.</p>
 *
 * <p>The source pixels are read from the resource packs, never from the atlas, so turning the skin
 * off writes the original back exactly. A resource reload uploads a fresh atlas; the skin is
 * written again on the next tick. Nothing happens while the skin is off and was never on.</p>
 */
public final class AtlasSkin {
    private static AnchorTextures textures;
    private static SkinComposer composer;
    private static boolean texturesStale = true;
    private static boolean loadFailed;
    /** Whether the atlas holds skinned pixels right now. */
    private static boolean written;
    private static int writtenStamp = Integer.MIN_VALUE;
    private static int generation;
    private static WeakReference<Object> topAnimation = new WeakReference<>(null);
    private static final Map<AnchorVariant, int[]> COMPOSED = new EnumMap<>(AnchorVariant.class);
    /** The enemy's skin: never written into the atlas, drawn over their anchors (EnemySkinRenderer). */
    private static final Map<AnchorVariant, int[]> COMPOSED_ENEMY = new EnumMap<>(AnchorVariant.class);
    /** What each sprite holds now, to rewrite only the sprites whose pixels changed. */
    private static final Map<AnchorVariant, int[]> WRITTEN = new EnumMap<>(AnchorVariant.class);
    private static int composedStamp = Integer.MIN_VALUE;

    private AtlasSkin() {
    }

    /** A new block atlas: whatever was written is gone, and the resource packs may have changed. */
    public static void onAtlasUploaded(TextureAtlas atlas) {
        if (!TextureAtlas.LOCATION_BLOCKS.equals(atlas.location())) {
            return;
        }
        texturesStale = true;
        loadFailed = false;
        written = false;
        writtenStamp = Integer.MIN_VALUE;
        composedStamp = Integer.MIN_VALUE;
        WRITTEN.clear();
    }

    /** Vanilla made the animation of a sprite; the anchor top's is kept to rewrite its frames. */
    public static void onAnimationState(SpriteContents contents, Object state) {
        if (AnchorVariant.TOP.sprite().equals(contents.name())) {
            topAnimation = new WeakReference<>(state);
        }
    }

    /**
     * The anchor textures of the active resource packs, analysed, or {@code null} when they cannot
     * be read. Loaded the first time something asks after each resource reload.
     */
    public static AnchorTextures textures() {
        if (texturesStale && !loadFailed) {
            TextureAtlas atlas = blockAtlas();
            if (atlas == null) {
                return null;
            }
            textures = AnchorTextures.load(Minecraft.getInstance(), atlas);
            composer = textures == null ? null : new SkinComposer(textures);
            loadFailed = textures == null;
            texturesStale = false;
            generation++;
            COMPOSED.clear();
            COMPOSED_ENEMY.clear();
            composedStamp = Integer.MIN_VALUE;
        }
        return textures;
    }

    public static SkinComposer composer() {
        return textures() == null ? null : composer;
    }

    /** Grows whenever the textures or their skinned pixels change. */
    public static int generation() {
        return generation;
    }

    /** The skinned pixels of {@code variant}, all frames; the originals with the skin off. */
    public static int[] composed(AnchorVariant variant) {
        SkinComposer current = composer();
        if (current == null) {
            return null;
        }
        refreshComposed();
        return COMPOSED.computeIfAbsent(variant, key -> current.compose(key, AnchorsConfig.settings().skin));
    }

    /** The enemy's skinned pixels of {@code variant}, all frames; the originals with their skin off. */
    public static int[] composedEnemy(AnchorVariant variant) {
        SkinComposer current = composer();
        if (current == null) {
            return null;
        }
        refreshComposed();
        return COMPOSED_ENEMY.computeIfAbsent(variant, key -> current.compose(key, AnchorsConfig.settings().enemySkin, true));
    }

    /** One profile's pixels: the player's, or the enemy's. */
    public static int[] composed(AnchorVariant variant, boolean enemy) {
        return enemy ? composedEnemy(variant) : composed(variant);
    }

    private static void refreshComposed() {
        int stamp = stamp();
        if (stamp != composedStamp) {
            COMPOSED.clear();
            COMPOSED_ENEMY.clear();
            composedStamp = stamp;
            generation++;
        }
    }

    /** Once a client tick: writes the skin when it changed, or the originals when it was turned off. */
    public static void tick(Minecraft minecraft) {
        AnchorsConfig.Skin skin = AnchorsConfig.settings().skin;
        int stamp = stamp();
        if (!skin.enabled && !written) {
            writtenStamp = stamp;
            return;
        }
        if (stamp == writtenStamp) {
            return;
        }
        if (Mc.overlay(minecraft) != null) {
            // Resources are loading: the atlas may not exist yet, or be about to be replaced.
            return;
        }
        TextureAtlas atlas = blockAtlas();
        if (atlas == null || textures() == null) {
            writtenStamp = stamp;
            return;
        }
        try {
            int mips = AtlasWriter.mipLevels(atlas);
            for (AnchorVariant variant : AnchorVariant.values()) {
                int[] pixels = composed(variant);
                if (pixels == null || Arrays.equals(pixels, WRITTEN.get(variant))) {
                    continue;
                }
                if (!written && !skin.enabled && pixels == textures.get(variant).pixels) {
                    continue;
                }
                write(atlas, variant, pixels, mips);
                WRITTEN.put(variant, pixels);
            }
            written = skin.enabled;
        } catch (RuntimeException exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not write the anchor skin into the block atlas", exception);
            loadFailed = true;
            textures = null;
            composer = null;
        }
        writtenStamp = stamp;
    }

    private static int stamp() {
        return AnchorsConfig.revision() * 31 + SkinPaint.revision() * 7 + generationOfTextures();
    }

    private static int generationOfTextures() {
        return texturesStale ? -1 : (textures == null ? -2 : System.identityHashCode(textures));
    }

    private static void write(TextureAtlas atlas, AnchorVariant variant, int[] pixels, int mips) {
        AnchorTextures.Texture texture = textures.get(variant);
        TextureAtlasSprite sprite = atlas.getSprite(variant.sprite());
        if (sprite == null || sprite.contents().width() != texture.width || sprite.contents().height() != texture.height) {
            return;
        }
        if (sprite.contents().isAnimated()) {
            Object state = topAnimation.get();
            if (variant == AnchorVariant.TOP && state != null) {
                writeFrames(texture, pixels, state);
            }
            return;
        }
        int padding = ((TextureAtlasSpriteAccessor) sprite).kohsAnchors$padding();
        int[][] levels = Mipmaps.generate(pixels, 0, texture.width, texture.height, mips);
        for (int mip = 0; mip < levels.length; mip++) {
            int width = Math.max(1, texture.width >> mip);
            int height = Math.max(1, texture.height >> mip);
            int pad = padding >> mip;
            try (NativeImage image = new NativeImage(width + pad * 2, height + pad * 2, false)) {
                for (int y = 0; y < height + pad * 2; y++) {
                    int sourceY = Math.max(0, Math.min(height - 1, y - pad));
                    for (int x = 0; x < width + pad * 2; x++) {
                        int sourceX = Math.max(0, Math.min(width - 1, x - pad));
                        image.setPixel(x, y, levels[mip][sourceY * width + sourceX]);
                    }
                }
                AtlasWriter.write(atlas, image, mip, sprite.getX() >> mip, sprite.getY() >> mip);
            }
        }
    }

    private static void writeFrames(AnchorTextures.Texture texture, int[] pixels, Object state) {
        Int2ObjectMap<?> frames = ((AnimationStateAccessor) state).kohsAnchors$frames();
        int area = texture.area();
        for (Int2ObjectMap.Entry<?> entry : frames.int2ObjectEntrySet()) {
            int frame = entry.getIntKey();
            if (frame < 0 || frame >= texture.frames) {
                continue;
            }
            Object view = entry.getValue();
            int mips = AtlasWriter.frameMipLevels(view);
            int[][] levels = Mipmaps.generate(pixels, frame * area, texture.width, texture.height, mips);
            for (int mip = 0; mip < levels.length; mip++) {
                int width = Math.max(1, texture.width >> mip);
                int height = Math.max(1, texture.height >> mip);
                try (NativeImage image = new NativeImage(width, height, false)) {
                    for (int y = 0; y < height; y++) {
                        for (int x = 0; x < width; x++) {
                            image.setPixel(x, y, levels[mip][y * width + x]);
                        }
                    }
                    AtlasWriter.writeFrame(view, image, mip);
                }
            }
        }
    }

    private static TextureAtlas blockAtlas() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getTextureManager() == null) {
            return null;
        }
        AbstractTexture texture = minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
        return texture instanceof TextureAtlas atlas ? atlas : null;
    }

    /** The block atlas's name, for the mixin that hears about uploads. */
    public static Identifier blockAtlasLocation() {
        return TextureAtlas.LOCATION_BLOCKS;
    }
}
