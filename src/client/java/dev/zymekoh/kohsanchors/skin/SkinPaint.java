package dev.zymekoh.kohsanchors.skin;

import com.mojang.blaze3d.platform.NativeImage;
import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * What the player painted pixel by pixel, and the pixels they moved to the other layer.
 *
 * <p>Each face (top, sides, bottom) has one grid per resolution, because a 16x painting does not
 * fit a 32x resource pack, and one paint per layer. A pixel painted on a layer applies to every
 * texture of its face where that pixel belongs to that layer: a frame pixel painted on the sides
 * colours all five charge states, and a charge light painted on the full anchor stays dark on the
 * states where it is off, because there it belongs to the frame.</p>
 *
 * <p>The grids are saved as PNG files under {@code config/kohs_anchors/skin/}, readable and
 * editable with any image editor: {@code side_16_frame.png} and {@code side_16_glow.png} hold the
 * paint (transparent where nothing was painted) and {@code side_16_layers.png} the moved pixels
 * (red 85 frame, 170 glow).</p>
 */
public final class SkinPaint {
    public static final byte AUTO = 0;
    public static final byte TO_FRAME = 1;
    public static final byte TO_GLOW = 2;

    private static final Map<String, Grid> GRIDS = new HashMap<>();
    private static int revision;

    private SkinPaint() {
    }

    /** Grows with every edit, so the skin knows when to compose again. */
    public static int revision() {
        return revision;
    }

    public static void touch() {
        revision++;
    }

    /** The grid of {@code face} at {@code resolution}, loaded from its files the first time. */
    public static Grid grid(AnchorVariant.Face face, int resolution) {
        String key = key(face, resolution);
        Grid grid = GRIDS.get(key);
        if (grid == null) {
            grid = load(face, resolution);
            GRIDS.put(key, grid);
        }
        return grid;
    }

    /** Whether any face at {@code resolution} has paint or moved pixels. */
    public static boolean anyAt(int resolution) {
        for (AnchorVariant.Face face : AnchorVariant.Face.values()) {
            if (!grid(face, resolution).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Writes every grid that changed since it was loaded. */
    public static void saveAll() {
        for (Grid grid : GRIDS.values()) {
            if (grid.dirty) {
                save(grid);
                grid.dirty = false;
            }
        }
    }

    /** Clears the paint and the moved pixels of every face at {@code resolution}. */
    public static void clear(int resolution) {
        for (AnchorVariant.Face face : AnchorVariant.Face.values()) {
            Grid grid = grid(face, resolution);
            java.util.Arrays.fill(grid.frame, 0);
            java.util.Arrays.fill(grid.glow, 0);
            java.util.Arrays.fill(grid.layer, AUTO);
            grid.dirty = true;
        }
        touch();
    }

    private static String key(AnchorVariant.Face face, int resolution) {
        return face.name().toLowerCase(java.util.Locale.ROOT) + "_" + resolution;
    }

    private static Path folder() {
        return AnchorsConfig.directory().resolve("skin");
    }

    private static Grid load(AnchorVariant.Face face, int resolution) {
        Grid grid = new Grid(face, resolution);
        Path frame = folder().resolve(key(face, resolution) + "_frame.png");
        Path glow = folder().resolve(key(face, resolution) + "_glow.png");
        Path layers = folder().resolve(key(face, resolution) + "_layers.png");
        readInto(frame, resolution, (index, argb) -> grid.frame[index] = ((argb >>> 24) & 255) == 0 ? 0 : argb);
        readInto(glow, resolution, (index, argb) -> grid.glow[index] = ((argb >>> 24) & 255) == 0 ? 0 : argb);
        readInto(layers, resolution, (index, argb) -> {
            int red = (argb >> 16) & 255;
            grid.layer[index] = red > 127 ? TO_GLOW : red > 42 ? TO_FRAME : AUTO;
        });
        return grid;
    }

    private interface PixelSink {
        void accept(int index, int argb);
    }

    private static void readInto(Path file, int resolution, PixelSink sink) {
        if (!Files.isRegularFile(file)) {
            return;
        }
        try (InputStream stream = Files.newInputStream(file); NativeImage image = NativeImage.read(stream)) {
            if (image.getWidth() != resolution || image.getHeight() != resolution) {
                KoHsAnchorsClient.LOGGER.warn("{} is {}x{}, not {}x{}; ignored", file, image.getWidth(), image.getHeight(),
                        resolution, resolution);
                return;
            }
            for (int y = 0; y < resolution; y++) {
                for (int x = 0; x < resolution; x++) {
                    sink.accept(y * resolution + x, image.getPixel(x, y));
                }
            }
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not read {}", file, exception);
        }
    }

    private static void save(Grid grid) {
        Path frame = folder().resolve(key(grid.face, grid.resolution) + "_frame.png");
        Path glow = folder().resolve(key(grid.face, grid.resolution) + "_glow.png");
        Path layers = folder().resolve(key(grid.face, grid.resolution) + "_layers.png");
        try {
            Files.createDirectories(folder());
            if (grid.isEmpty()) {
                Files.deleteIfExists(frame);
                Files.deleteIfExists(glow);
                Files.deleteIfExists(layers);
                return;
            }
            writePaint(grid, grid.frame, frame);
            writePaint(grid, grid.glow, glow);
            try (NativeImage image = new NativeImage(grid.resolution, grid.resolution, true)) {
                for (int index = 0; index < grid.layer.length; index++) {
                    int red = grid.layer[index] == TO_GLOW ? 170 : grid.layer[index] == TO_FRAME ? 85 : 0;
                    image.setPixel(index % grid.resolution, index / grid.resolution, 0xFF000000 | red << 16);
                }
                image.writeToFile(layers);
            }
        } catch (Exception exception) {
            KoHsAnchorsClient.LOGGER.warn("Could not save the anchor paint to {}", frame, exception);
        }
    }

    private static void writePaint(Grid grid, int[] paint, Path file) throws java.io.IOException {
        try (NativeImage image = new NativeImage(grid.resolution, grid.resolution, true)) {
            for (int index = 0; index < paint.length; index++) {
                image.setPixel(index % grid.resolution, index / grid.resolution, paint[index]);
            }
            image.writeToFile(file);
        }
    }

    /** One face's painted pixels at one resolution. */
    public static final class Grid {
        public final AnchorVariant.Face face;
        public final int resolution;
        /** The frame layer's paint, ARGB per pixel; 0 where nothing is painted. */
        public final int[] frame;
        /** The glow layer's paint. */
        public final int[] glow;
        /** {@link #AUTO}, {@link #TO_FRAME} or {@link #TO_GLOW} per pixel. */
        public final byte[] layer;
        private boolean dirty;

        Grid(AnchorVariant.Face face, int resolution) {
            this.face = face;
            this.resolution = resolution;
            this.frame = new int[resolution * resolution];
            this.glow = new int[resolution * resolution];
            this.layer = new byte[resolution * resolution];
        }

        /** The paint of {@code layer} ({@link AnchorTextures#FRAME} or {@link AnchorTextures#GLOW}). */
        public int[] paint(byte layer) {
            return layer == AnchorTextures.GLOW ? this.glow : this.frame;
        }

        public boolean isEmpty() {
            for (int index = 0; index < this.frame.length; index++) {
                if (this.frame[index] != 0 || this.glow[index] != 0) {
                    return false;
                }
            }
            for (byte value : this.layer) {
                if (value != AUTO) {
                    return false;
                }
            }
            return true;
        }

        public void setPaint(byte layer, int index, int argb) {
            int[] paint = paint(layer);
            if (paint[index] != argb) {
                paint[index] = argb;
                this.dirty = true;
                touch();
            }
        }

        public void setLayer(int index, byte value) {
            if (this.layer[index] != value) {
                this.layer[index] = value;
                this.dirty = true;
                touch();
            }
        }
    }
}
