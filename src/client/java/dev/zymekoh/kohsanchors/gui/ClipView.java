package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.compat.VideoTexture;
import dev.zymekoh.kohsanchors.video.LoopingClip;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.resources.Identifier;
import org.lwjgl.system.MemoryUtil;

/**
 * A {@link LoopingClip} on the screen: the frame that is due by the real clock, in a texture.
 *
 * <p>Playback follows {@code System.nanoTime}, not the frame rate: at 144 Hz a frame stays up for
 * two or three screen frames, at 30 Hz every other frame is skipped. It starts once a few frames
 * are ready, so the first second does not stutter while the decoder warms up, and when the decoder
 * falls behind the clock waits for it instead of jumping ahead afterwards. A new frame is copied
 * and uploaded once, whatever the number of screen frames it stays up.</p>
 */
final class ClipView {
    private static final Identifier TEXTURE = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "video/clip");
    /** Frames decoded before the clock starts. */
    private static final int PREROLL = 3;
    /** The clock starts anyway after this, with whatever is ready. */
    private static final long PREROLL_LIMIT_NANOS = 500_000_000L;

    private final LoopingClip clip;
    private VideoTexture texture;
    private int play = -1;
    private long startedAt;
    /** When the play's frame 0 is, on the real clock; below zero until the clock starts. */
    private long clockStart = -1L;
    private LoopingClip.Frame shown;
    private long shownNumber = -1L;

    ClipView(LoopingClip clip) {
        this.clip = clip;
    }

    /** Starts the clip from its first frame. */
    void start() {
        stop();
        this.play = this.clip.start();
        this.startedAt = System.nanoTime();
        this.clockStart = -1L;
        this.shownNumber = -1L;
    }

    /** Stops decoding; the last frame stays in the texture. */
    void stop() {
        if (this.play >= 0) {
            this.clip.stop();
            this.clip.recycle(this.shown);
            this.shown = null;
            this.play = -1;
        }
    }

    /** Stops and frees the texture. */
    void close() {
        stop();
        if (this.texture != null) {
            TextureManager textures = Minecraft.getInstance().getTextureManager();
            textures.release(TEXTURE);
            this.texture = null;
        }
    }

    boolean failed() {
        return this.clip.failed();
    }

    /** Whether a frame has been shown yet. */
    boolean hasPicture() {
        return this.texture != null && this.shownNumber >= 0L;
    }

    /** The clip's aspect, width over height; its known shape before the first frame. */
    float aspect() {
        int width = this.clip.width();
        int height = this.clip.height();
        return width > 0 && height > 0 ? width / (float) height : 854.0F / 480.0F;
    }

    /** Where the shown frame is in its loop, 0 to 1. */
    float loopProgress(int framesPerLoop) {
        return this.shownNumber < 0L ? 0.0F : (this.shownNumber % framesPerLoop) / (float) framesPerLoop;
    }

    /** How many times the clip has started over. */
    long loops(int framesPerLoop) {
        return this.shownNumber < 0L ? 0L : this.shownNumber / framesPerLoop;
    }

    /** Takes the frame that is due now and puts it in the texture. Call once per screen frame. */
    void update(long now) {
        if (this.play < 0) {
            return;
        }
        long frameNanos = 1_000_000_000L / Math.max(1, this.clip.frameRate());
        LoopingClip.Frame next;
        while ((next = this.clip.peek()) != null) {
            if (!LoopingClip.of(next, this.play)) {
                // Left over from an earlier play.
                this.clip.recycle(this.clip.take());
                continue;
            }
            if (this.clockStart < 0L) {
                if (this.clip.buffered() < PREROLL && now - this.startedAt < PREROLL_LIMIT_NANOS) {
                    break;
                }
                this.clockStart = now - next.number * frameNanos;
            }
            if (this.clockStart + next.number * frameNanos > now) {
                break;
            }
            // Due: it replaces the frame shown, and any frame due after it replaces it in turn.
            this.clip.recycle(this.shown);
            this.shown = this.clip.take();
        }
        if (this.shown != null && this.clockStart >= 0L && this.clip.peek() == null) {
            // Nothing decoded yet for the next frame: the clock waits instead of running ahead.
            long late = now - (this.clockStart + (this.shown.number + 1L) * frameNanos);
            if (late > frameNanos) {
                this.clockStart += late - frameNanos;
            }
        }
        if (this.shown != null && this.shown.number != this.shownNumber) {
            upload(this.shown);
            this.shownNumber = this.shown.number;
        }
    }

    private void upload(LoopingClip.Frame frame) {
        int width = this.clip.width();
        int height = this.clip.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        if (this.texture == null) {
            this.texture = new VideoTexture("KoHs Anchor's clip", width, height);
            Minecraft.getInstance().getTextureManager().register(TEXTURE, this.texture);
        }
        MemoryUtil.memCopy(MemoryUtil.memAddress(frame.pixels), this.texture.getPixels().getPointer(),
                (long) width * height * 4L);
        this.texture.upload();
    }

    /** The current frame, stretched over the rectangle, with {@code alpha} and a tint. */
    void draw(GuiGraphicsExtractor graphics, int x, int y, int width, int height, float alpha, int tint) {
        if (!hasPicture() || width <= 0 || height <= 0 || alpha <= 0.01F) {
            return;
        }
        int clipWidth = this.clip.width();
        int clipHeight = this.clip.height();
        int color = Math.round(255.0F * Math.min(1.0F, alpha)) << 24 | (tint & 0xFFFFFF);
        graphics.blit(RenderPipelines.GUI_TEXTURED, TEXTURE, x, y, 0.0F, 0.0F, width, height, clipWidth, clipHeight,
                clipWidth, clipHeight, color);
    }
}
