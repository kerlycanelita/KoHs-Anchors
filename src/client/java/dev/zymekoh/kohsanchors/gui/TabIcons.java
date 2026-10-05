package dev.zymekoh.kohsanchors.gui;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.bridge.BridgeClient;
import dev.zymekoh.kohsanchors.skin.AnchorTextures;
import dev.zymekoh.kohsanchors.skin.AnchorVariant;
import dev.zymekoh.kohsanchors.skin.AtlasSkin;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import org.joml.Matrix3x2f;

/**
 * The tabs' icons, alive. Blocks of the anchor's world are drawn from their own textures as a small
 * cube, three faces through affine transforms as the workshop draws its anchor; Herzium and KoHs
 * keep their own marks; the server tab shows the server list's signal bars.
 *
 * <p>At rest a block sways a little; hovered it turns towards the pointer and lifts; selected it
 * spins and does what it does in the world: the anchor charges and flares, the skin's anchor runs
 * through colours, glowstone and shroomlight burn, a jukebox's notes rise, ancient debris and gilded
 * blackstone catch the light.
 * The bars are green on a server whose bridge approves the mod, pinging while it is asked, red
 * where it is missing. A few quads per tab, all by elapsed time; no motion with the interface's
 * motion off.</p>
 */
final class TabIcons {
    private static final Identifier HERZIUM = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID, "textures/gui/herzium.png");
    private static final Identifier KOHS = Identifier.fromNamespaceAndPath(KoHsAnchorsClient.MOD_ID,
            "textures/gui/zymery/kohs_mark.png");
    private static final Identifier[] ANCHOR_SIDES = {block("respawn_anchor_side0"), block("respawn_anchor_side1"),
            block("respawn_anchor_side2"), block("respawn_anchor_side3"), block("respawn_anchor_side4")};
    private static final Identifier ANCHOR_TOP_OFF = block("respawn_anchor_top_off");
    private static final Identifier ANCHOR_TOP = block("respawn_anchor_top");
    private static final Identifier GLOWSTONE = block("glowstone");
    private static final Identifier DEBRIS_TOP = block("ancient_debris_top");
    private static final Identifier DEBRIS_SIDE = block("ancient_debris_side");
    /** The music note jukeboxes and note blocks give off, white so it takes any colour. */
    private static final Identifier NOTE = Identifier.withDefaultNamespace("textures/particle/note.png");
    private static final Identifier SHROOMLIGHT = block("shroomlight");
    private static final Identifier GILDED_BLACKSTONE = block("gilded_blackstone");
    private static final Identifier PING_OK = Identifier.withDefaultNamespace("server_list/ping_5");
    private static final Identifier PING_UNREACHABLE = Identifier.withDefaultNamespace("server_list/unreachable");
    private static final Identifier PING_UNKNOWN = Identifier.withDefaultNamespace("icon/ping_unknown");
    private static final Identifier[] PINGING = {Identifier.withDefaultNamespace("server_list/pinging_1"),
            Identifier.withDefaultNamespace("server_list/pinging_2"), Identifier.withDefaultNamespace("server_list/pinging_3"),
            Identifier.withDefaultNamespace("server_list/pinging_4"), Identifier.withDefaultNamespace("server_list/pinging_5")};

    /** Up, north, south, west, east: the bottom never shows from above. */
    private static final int[][] FACES = {{0, 1, 0}, {0, 0, -1}, {0, 0, 1}, {-1, 0, 0}, {1, 0, 0}};
    private static final float PITCH = 30.0F;
    private static final Matrix3x2f FACE = new Matrix3x2f();
    private static final float[] ROTATION = new float[9];

    private TabIcons() {
    }

    /** For developer mode: what the tab's icon is and what it does. */
    static String describe(int tab) {
        return switch (tab) {
            case AnchorsScreen.GENERAL -> "TabIcons.anchor: respawn anchor cube, charges and flares when selected";
            case AnchorsScreen.ANCHOR -> "TabIcons.anchor: the anchor running through colours (hue tint)";
            case AnchorsScreen.GLOW -> "TabIcons.cube: glowstone, halo and sparks";
            case AnchorsScreen.SOUNDS -> "TabIcons.note: textures/particle/note.png, note block colours, rising notes";
            case AnchorsScreen.SERVER -> "TabIcons.server: server_list/ping_5 · pinging_1-5 · unreachable; BridgeClient = "
                    + BridgeClient.state();
            case AnchorsScreen.ADVANCED -> "TabIcons.cube: ancient debris, glint";
            case AnchorsScreen.HERZIUM -> "TabIcons.herzium: textures/gui/herzium.png, speed streaks";
            case AnchorsScreen.KOHS -> "TabIcons.image: zymery/kohs_mark.png, halo";
            case AnchorsScreen.ENEMY_GLOW -> "TabIcons.cube: shroomlight, embers";
            case AnchorsScreen.ENEMY_COLOURS -> "TabIcons.anchor: the anchor in crimson";
            case AnchorsScreen.ENEMY_ADVANCED -> "TabIcons.cube: gilded blackstone, glint";
            default -> "TabIcons";
        };
    }

    private static Identifier block(String name) {
        return Identifier.withDefaultNamespace("textures/block/" + name + ".png");
    }

    /**
     * The icon of {@code tab} in the {@code size} square at {@code x, y}. {@code hover} and the
     * selection come from the tab's own easing, {@code seconds} from the screen's clock.
     */
    static void draw(GuiGraphicsExtractor graphics, int tab, int x, int y, int size, float alpha, float hover, boolean selected,
            double seconds, boolean motion) {
        if (alpha <= 0.02F) {
            return;
        }
        double time = motion ? seconds : 0.0D;
        float cx = x + size / 2.0F;
        float cy = y + size / 2.0F - (motion ? hover : 0.0F);
        // Idle sway, a quick turn when hovered, a steady spin when selected; each tab its own phase.
        float yaw = 225.0F + (float) (motion ? (selected ? time * 50.0D : Math.sin(time * 0.9D + tab * 1.7D) * 9.0D) : 0.0D)
                + 28.0F * hover;
        float scale = size * 0.62F;
        float pulse = (float) (0.5D + 0.5D * Math.sin(time * 3.2D + tab));
        switch (tab) {
            case AnchorsScreen.GENERAL -> anchor(graphics, cx, cy, scale, yaw, alpha, selected, time, 0xFFFFFF, -1);
            case AnchorsScreen.ANCHOR -> {
                // The skin tab: the anchor runs through colours, faster when its tab is open.
                int tint = motion ? hue((float) (time * (selected ? 0.22D : 0.08D) + 0.72D)) : 0xD9A6FF;
                anchor(graphics, cx, cy, scale, yaw, alpha, false, time, tint, tint);
            }
            case AnchorsScreen.GLOW -> {
                if (motion) {
                    AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(size * 0.7F), Math.round(size * 0.7F),
                            0xFFD27A, alpha * (selected ? 0.35F + 0.3F * pulse : 0.12F + 0.1F * pulse));
                }
                cube(graphics, cx, cy, scale, yaw, GLOWSTONE, GLOWSTONE, 0, 0, 0xFFFFFF, alpha);
                if (selected && motion) {
                    sparks(graphics, cx, cy, size, time, 0xFFE9A8, alpha);
                }
            }
            case AnchorsScreen.SOUNDS -> note(graphics, x, y, size, alpha, hover, selected, time, motion);
            case AnchorsScreen.SERVER -> server(graphics, x, y, size, alpha, time, motion);
            case AnchorsScreen.ADVANCED -> {
                cube(graphics, cx, cy, scale, yaw, DEBRIS_TOP, DEBRIS_SIDE, 0, 0, 0xFFFFFF, alpha);
                glint(graphics, x, y, size, time, selected ? 1.4D : 4.0D, 0xFFFFFF, alpha * (selected ? 0.55F : 0.3F), motion);
            }
            case AnchorsScreen.HERZIUM -> herzium(graphics, x, y, size, alpha, hover, selected, time, motion);
            case AnchorsScreen.KOHS -> {
                if (motion) {
                    AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(size * 0.75F), Math.round(size * 0.75F),
                            AnchorsTheme.ACCENT & 0xFFFFFF, alpha * (selected ? 0.3F + 0.25F * pulse : 0.1F + 0.08F * pulse));
                }
                float breathe = motion && selected ? 1.0F + 0.06F * pulse : 1.0F;
                image(graphics, KOHS, cx, cy, size * breathe, 96, alpha, 0xFFFFFF, false);
            }
            case AnchorsScreen.ENEMY_GLOW -> {
                if (motion) {
                    AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(size * 0.7F), Math.round(size * 0.7F),
                            0xFF8A3D, alpha * (selected ? 0.35F + 0.3F * pulse : 0.12F + 0.1F * pulse));
                }
                cube(graphics, cx, cy, scale, yaw, SHROOMLIGHT, SHROOMLIGHT, 0, 0, 0xFFFFFF, alpha);
                if (selected && motion) {
                    sparks(graphics, cx, cy, size, time, 0xFFB46B, alpha);
                }
            }
            case AnchorsScreen.ENEMY_COLOURS -> {
                if (motion) {
                    AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(size * 0.7F), Math.round(size * 0.7F),
                            0xFF3B4E, alpha * (selected ? 0.3F + 0.25F * pulse : 0.1F + 0.08F * pulse));
                }
                // The enemy's anchor: the same block, in their crimson.
                anchor(graphics, cx, cy, scale, yaw, alpha, selected, time, 0xFF8A9A, 0xFF3B4E);
            }
            case AnchorsScreen.ENEMY_ADVANCED -> {
                cube(graphics, cx, cy, scale, yaw, GILDED_BLACKSTONE, GILDED_BLACKSTONE, 0, 0, 0xFFFFFF, alpha);
                glint(graphics, x, y, size, time, selected ? 1.4D : 4.0D, 0xFFE38A, alpha * (selected ? 0.5F : 0.25F), motion);
            }
            default -> {
            }
        }
    }

    /**
     * The respawn anchor. Selected, it charges one glowstone at a time, its top lighting with the
     * portal, and flares at the fourth before it starts again; otherwise it stands fully charged.
     */
    private static void anchor(GuiGraphicsExtractor graphics, float cx, float cy, float scale, float yaw, float alpha,
            boolean selected, double time, int tint, int light) {
        int charge = 4;
        float flare = 0.0F;
        if (selected && time > 0.0D) {
            double phase = time / 2.6D % 1.0D;
            charge = Math.min(4, (int) (phase * 5.5D));
            flare = phase > 0.82D ? (float) ((phase - 0.82D) / 0.18D) : 0.0F;
        }
        AnchorTextures textures = AtlasSkin.textures();
        int frames = textures == null ? 0 : textures.get(AnchorVariant.TOP).frames;
        Identifier top = charge > 0 && frames > 0 ? ANCHOR_TOP : ANCHOR_TOP_OFF;
        int frame = frames > 1 ? (int) (time * 20.0D % frames) : 0;
        int halo = light < 0 ? AnchorsTheme.PORTAL & 0xFFFFFF : light & 0xFFFFFF;
        if (charge > 0) {
            AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(scale * 1.15F), Math.round(scale * 1.15F),
                    halo, alpha * (0.1F + 0.06F * charge));
        }
        cube(graphics, cx, cy, scale, yaw, top, ANCHOR_SIDES[charge], charge > 0 && frames > 0 ? frame : 0,
                charge > 0 ? frames : 0, tint, alpha);
        if (flare > 0.0F) {
            // The fourth glowstone in: a flash of portal light that fades out.
            AnchorsUi.glowEllipse(graphics, Math.round(cx), Math.round(cy), Math.round(scale * (1.0F + flare)),
                    Math.round(scale * (1.0F + flare)), light < 0 ? 0xE9D5FF : halo, alpha * 0.6F * (1.0F - flare));
        }
    }

    /**
     * A cube of {@code top} and {@code side}, seen from above at {@code yaw}, {@code scale} pixels
     * to a block. {@code topFrames} above 0 reads the top as an animation strip of that many frames.
     */
    private static void cube(GuiGraphicsExtractor graphics, float cx, float cy, float scale, float yaw, Identifier top,
            Identifier side, int topFrame, int topFrames, int tint, float alpha) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(PITCH);
        float cosYaw = (float) Math.cos(yawRadians);
        float sinYaw = (float) Math.sin(yawRadians);
        float cosPitch = (float) Math.cos(pitchRadians);
        float sinPitch = (float) Math.sin(pitchRadians);
        ROTATION[0] = cosYaw;
        ROTATION[1] = 0.0F;
        ROTATION[2] = sinYaw;
        ROTATION[3] = sinPitch * sinYaw;
        ROTATION[4] = cosPitch;
        ROTATION[5] = -sinPitch * cosYaw;
        ROTATION[6] = -cosPitch * sinYaw;
        ROTATION[7] = sinPitch;
        ROTATION[8] = cosPitch * cosYaw;
        int alphaBits = Math.round(255 * Mth.clamp(alpha, 0.0F, 1.0F)) << 24;
        for (int[] face : FACES) {
            float normalZ = ROTATION[6] * face[0] + ROTATION[7] * face[1] + ROTATION[8] * face[2];
            if (normalZ <= 0.02F) {
                continue;
            }
            // Light from above and a little to the left, as the game lights its item icons.
            float normalX = ROTATION[0] * face[0] + ROTATION[1] * face[1] + ROTATION[2] * face[2];
            float normalY = ROTATION[3] * face[0] + ROTATION[4] * face[1] + ROTATION[5] * face[2];
            float shade = face[1] == 1 ? 1.0F : Mth.clamp(0.62F + 0.3F * normalY - 0.18F * normalX, 0.45F, 0.95F);
            boolean upper = face[1] == 1;
            float[] origin = facePoint(face, 0.0F, 0.0F);
            float[] across = facePoint(face, 1.0F, 0.0F);
            float[] down = facePoint(face, 0.0F, 1.0F);
            float ox = screenX(origin, cx, scale);
            float oy = screenY(origin, cy, scale);
            FACE.set((screenX(across, cx, scale) - ox) / 16.0F, (screenY(across, cy, scale) - oy) / 16.0F,
                    (screenX(down, cx, scale) - ox) / 16.0F, (screenY(down, cy, scale) - oy) / 16.0F, ox, oy);
            int color = alphaBits | shaded(tint, shade);
            graphics.pose().pushMatrix();
            graphics.pose().mul(FACE);
            if (upper && topFrames > 0) {
                graphics.blit(RenderPipelines.GUI_TEXTURED, top, 0, 0, 0.0F, topFrame * 16.0F, 16, 16, 16, 16, 16, 16 * topFrames, color);
            } else {
                graphics.blit(RenderPipelines.GUI_TEXTURED, upper ? top : side, 0, 0, 0.0F, 0.0F, 16, 16, 16, 16, 16, 16, color);
            }
            graphics.pose().popMatrix();
        }
    }

    /** The server list's bars: green with a bridge that approves the mod, pinging while asked, red without. */
    private static void server(GuiGraphicsExtractor graphics, int x, int y, int size, float alpha, double time, boolean motion) {
        BridgeClient.State state = BridgeClient.state();
        int barsX = x + (size - 10) / 2;
        int barsY = y + (size - 8) / 2;
        float pulse = motion ? (float) (0.5D + 0.5D * Math.sin(time * 2.6D)) : 1.0F;
        switch (state) {
            case CONNECTED, LOCAL -> {
                AnchorsUi.glowEllipse(graphics, x + size / 2, y + size / 2, Math.round(size * 0.75F), Math.round(size * 0.6F),
                        AnchorFx.GREEN & 0xFFFFFF, alpha * (0.18F + 0.22F * pulse));
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PING_OK, barsX, barsY, 10, 8, alpha);
            }
            case CHECKING -> {
                // The game's own pinging animation: the bars sweep up and down while the bridge is asked.
                int frame = (int) (time * 10.0D % 8.0D);
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PINGING[frame > 4 ? 8 - frame : Math.min(4, frame)],
                        barsX, barsY, 10, 8, alpha);
            }
            case MISSING, NO_API -> {
                AnchorsUi.glowEllipse(graphics, x + size / 2, y + size / 2, Math.round(size * 0.7F), Math.round(size * 0.55F),
                        0xFF3B4E, alpha * 0.2F * pulse);
                graphics.blitSprite(RenderPipelines.GUI_TEXTURED, PING_UNREACHABLE, barsX, barsY, 10, 8, alpha);
            }
            default -> {
                // Not on a server: the same five bars in grey, nothing to approve or refuse.
                int grey = AnchorsTheme.withAlpha(0x8E8AA0, Math.round(220 * alpha));
                int shadow = AnchorsTheme.withAlpha(0x0A0412, Math.round(160 * alpha));
                for (int bar = 0; bar < 5; bar++) {
                    int barHeight = 2 + Math.round(bar * 1.5F);
                    int left = barsX + bar * 2;
                    graphics.fill(left + 1, barsY + 8 - barHeight + 1, left + 2, barsY + 9, shadow);
                    graphics.fill(left, barsY + 8 - barHeight, left + 1, barsY + 8, grey);
                }
            }
        }
    }

    /**
     * The music note of jukeboxes and note blocks, running through the note blocks' colours and
     * bobbing on the beat; selected, more notes rise off it as off a playing jukebox.
     */
    private static void note(GuiGraphicsExtractor graphics, int x, int y, int size, float alpha, float hover, boolean selected,
            double time, boolean motion) {
        float beat = motion ? (float) Math.abs(Math.sin(time * Math.PI * 1.6D)) : 0.0F;
        int color = motion ? hue((float) (time * 0.18D)) : 0x7CFC6A;
        if (motion && (selected || hover > 0.05F)) {
            for (int rise = 0; rise < 2; rise++) {
                float phase = (float) ((time * 0.7D + rise * 0.5D) % 1.0D);
                float small = size * 0.45F;
                float nx = x + size * (0.65F + 0.25F * rise) + (float) Math.sin(phase * 6.0F + rise) * 1.2F;
                float ny = y + size * 0.4F - phase * size * 0.9F;
                image(graphics, NOTE, nx, ny, small, 8, alpha * (selected ? 1.0F : hover) * (1.0F - phase),
                        hue((float) (time * 0.18D + 0.3D + rise * 0.25D)), true);
            }
        }
        float lift = selected ? beat * 1.2F : 0.0F;
        image(graphics, NOTE, x + size / 2.0F, y + size / 2.0F - lift, size, 8, alpha, color, true);
    }

    /** Herzium's own icon; streaks of speed run off behind it when hovered or selected. */
    private static void herzium(GuiGraphicsExtractor graphics, int x, int y, int size, float alpha, float hover, boolean selected,
            double time, boolean motion) {
        float speed = selected ? 1.0F : hover;
        if (motion && speed > 0.05F) {
            for (int line = 0; line < 3; line++) {
                float phase = (float) ((time * 2.2D + line * 0.33D) % 1.0D);
                int length = 2 + Math.round(4 * (1.0F - phase));
                int lx = Math.round(x - 1 - phase * 7.0F);
                int ly = y + 3 + line * 3;
                graphics.fill(lx - length, ly, lx, ly + 1,
                        AnchorsTheme.withAlpha(0xE9D5FF, Math.round(200 * alpha * speed * (1.0F - phase))));
            }
        }
        float shake = motion && hover > 0.5F && !selected ? (float) Math.sin(time * 40.0D) * 0.4F : 0.0F;
        image(graphics, HERZIUM, x + size / 2.0F + shake, y + size / 2.0F, size, 256, alpha, 0xFFFFFF, false);
    }

    /**
     * A square image of {@code textureSize} pixels, scaled to {@code size} around a centre and
     * tinted {@code tint}; {@code crisp} snaps it to whole pixels, for pixel art.
     */
    private static void image(GuiGraphicsExtractor graphics, Identifier texture, float cx, float cy, float size, int textureSize,
            float alpha, int tint, boolean crisp) {
        if (crisp) {
            cx = Math.round(cx - size / 2.0F) + size / 2.0F;
            cy = Math.round(cy - size / 2.0F) + size / 2.0F;
        }
        graphics.pose().pushMatrix();
        graphics.pose().translate(cx - size / 2.0F, cy - size / 2.0F);
        graphics.pose().scale(size / textureSize, size / textureSize);
        graphics.blit(RenderPipelines.GUI_TEXTURED, texture, 0, 0, 0.0F, 0.0F, textureSize, textureSize, textureSize, textureSize,
                textureSize, textureSize, Math.round(255 * Mth.clamp(alpha, 0.0F, 1.0F)) << 24 | tint & 0xFFFFFF);
        graphics.pose().popMatrix();
    }

    /** Two sparks circling a burning block. */
    private static void sparks(GuiGraphicsExtractor graphics, float cx, float cy, int size, double time, int color, float alpha) {
        for (int spark = 0; spark < 2; spark++) {
            double angle = time * 3.0D + spark * Math.PI;
            int sx = Math.round(cx + (float) Math.cos(angle) * size * 0.62F);
            int sy = Math.round(cy + (float) Math.sin(angle) * size * 0.42F);
            graphics.fill(sx, sy, sx + 1, sy + 1, AnchorsTheme.withAlpha(color, Math.round(230 * alpha)));
        }
    }

    /** A thin diagonal of light crossing the icon every {@code period} seconds. */
    private static void glint(GuiGraphicsExtractor graphics, int x, int y, int size, double time, double period, int color, float alpha,
            boolean motion) {
        if (!motion) {
            return;
        }
        float phase = (float) (time / period % 1.0D);
        if (phase > 0.35F) {
            return;
        }
        int offset = Math.round(phase / 0.35F * size * 2.0F) - size;
        for (int step = 0; step < size; step++) {
            int gx = x + offset + step;
            int gy = y + size - 1 - step;
            if (gx >= x && gx < x + size) {
                graphics.fill(gx, gy, gx + 1, gy + 1, AnchorsTheme.withAlpha(color, Math.round(255 * alpha)));
            }
        }
    }

    /** A bright colour around the wheel: {@code turn} 0 to 1 is red, through green and blue, back to red. */
    private static int hue(float turn) {
        float h = (turn % 1.0F + 1.0F) % 1.0F * 6.0F;
        int sector = (int) h;
        float f = h - sector;
        float low = 0.35F;
        float q = 1.0F - (1.0F - low) * f;
        float t = low + (1.0F - low) * f;
        float r;
        float g;
        float b;
        switch (sector) {
            case 0 -> { r = 1.0F; g = t; b = low; }
            case 1 -> { r = q; g = 1.0F; b = low; }
            case 2 -> { r = low; g = 1.0F; b = t; }
            case 3 -> { r = low; g = q; b = 1.0F; }
            case 4 -> { r = t; g = low; b = 1.0F; }
            default -> { r = 1.0F; g = low; b = q; }
        }
        return Math.round(r * 255) << 16 | Math.round(g * 255) << 8 | Math.round(b * 255);
    }

    private static int shaded(int rgb, float shade) {
        int r = Math.round(((rgb >> 16) & 255) * shade);
        int g = Math.round(((rgb >> 8) & 255) * shade);
        int b = Math.round((rgb & 255) * shade);
        return r << 16 | g << 8 | b;
    }

    private static float screenX(float[] point, float cx, float scale) {
        return cx + (ROTATION[0] * (point[0] - 0.5F) + ROTATION[1] * (point[1] - 0.5F) + ROTATION[2] * (point[2] - 0.5F)) * scale;
    }

    private static float screenY(float[] point, float cy, float scale) {
        return cy - (ROTATION[3] * (point[0] - 0.5F) + ROTATION[4] * (point[1] - 0.5F) + ROTATION[5] * (point[2] - 0.5F)) * scale;
    }

    /** The workshop's mapping of a face's texture point onto the block, 0 to 1. */
    private static float[] facePoint(int[] face, float u, float v) {
        if (face[1] == 1) {
            return new float[] {u, 1.0F, v};
        }
        if (face[2] == -1) {
            return new float[] {1.0F - u, 1.0F - v, 0.0F};
        }
        if (face[2] == 1) {
            return new float[] {u, 1.0F - v, 1.0F};
        }
        if (face[0] == -1) {
            return new float[] {0.0F, 1.0F - v, u};
        }
        return new float[] {1.0F, 1.0F - v, 1.0F - u};
    }
}
