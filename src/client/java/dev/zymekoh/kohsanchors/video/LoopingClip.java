package dev.zymekoh.kohsanchors.video;

import dev.zymekoh.kohsanchors.KoHsAnchorsClient;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.jcodec.api.specific.AVCMP4Adaptor;
import org.jcodec.common.SeekableDemuxerTrack;
import org.jcodec.common.io.ByteBufferSeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.common.model.Picture;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

/**
 * A short H.264 clip from the mod's jar, decoded in a background thread and played in a loop.
 *
 * <p>The decoder is JCodec, pure Java: it reads the clip as it is stored (CABAC main profile, 60
 * frames a second) and its output matches FFmpeg's bit for bit. Each frame is converted to RGBA on
 * the decoding thread and handed over through a ring of {@link #SLOTS} frames; the render thread
 * takes the one due, copies it into a texture and gives the previous one back. Nothing is
 * allocated per frame, and the render thread never waits: when the decoder falls behind, the
 * last frame simply stays up a little longer.</p>
 *
 * <p>JCodec's decoder starts a pool of threads the first time a frame has several slices and
 * never stops it, so each clip keeps one decoder, and one daemon thread that sleeps between
 * plays, for the whole session. The frames themselves are dropped when a play ends.</p>
 */
public final class LoopingClip {
    public static final LoopingClip SAFE_ANCHOR = new LoopingClip("/assets/kohs_anchors/video/safe_anchor.mp4");

    /** Decoded frames in flight: about 0.1 s of lead at 60 frames a second. */
    private static final int SLOTS = 6;
    /** A NativeImage keeps R, G, B, A in that order in memory, whatever the machine's int order. */
    private static final boolean LITTLE_ENDIAN = ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN;

    private final String resource;
    private final Object lock = new Object();
    private final ArrayBlockingQueue<Frame> free = new ArrayBlockingQueue<>(SLOTS);
    private final ArrayBlockingQueue<Frame> ready = new ArrayBlockingQueue<>(SLOTS);
    private Thread worker;
    /** Grows with every play; frames of an earlier play are recognised by it and recycled. */
    private volatile int play;
    private volatile boolean playing;
    private volatile boolean failed;
    /** Grows whenever the frames are dropped, so a frame given back late is not reused. */
    private volatile int generation;
    private volatile int width;
    private volatile int height;
    private volatile int frameRate = 60;

    // Owned by the worker thread.
    private MP4Demuxer demuxer;
    private SeekableDemuxerTrack track;
    private AVCMP4Adaptor adaptor;
    private byte[][] planes;
    private int[] converted;
    private int allocated;

    private LoopingClip(String resource) {
        this.resource = resource;
    }

    /** One decoded frame: RGBA bytes, in the order a {@code NativeImage} keeps them. */
    public static final class Frame {
        public final ByteBuffer pixels;
        final int generation;
        /** Its place in the play: 0, 1, 2… across loops. */
        public long number;
        int play;

        private Frame(int bytes, int generation) {
            this.pixels = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder());
            this.generation = generation;
        }
    }

    /** Starts playing from the first frame; returns the play's number. */
    public int start() {
        synchronized (this.lock) {
            this.play++;
            this.playing = true;
            if (this.worker == null) {
                this.worker = new Thread(this::run, "KoHs Anchor's clip decoder");
                this.worker.setDaemon(true);
                this.worker.setPriority(Thread.NORM_PRIORITY - 1);
                this.worker.start();
            }
            this.lock.notifyAll();
            return this.play;
        }
    }

    /** Stops decoding; the worker drops the frames and goes back to sleep. */
    public void stop() {
        synchronized (this.lock) {
            this.playing = false;
            this.play++;
            this.lock.notifyAll();
        }
    }

    public boolean failed() {
        return this.failed;
    }

    /** The clip's size, 0 until its first frame is decoded. */
    public int width() {
        return this.width;
    }

    public int height() {
        return this.height;
    }

    public int frameRate() {
        return this.frameRate;
    }

    /** The oldest decoded frame, without taking it; {@code null} when none is waiting. */
    public Frame peek() {
        return this.ready.peek();
    }

    /** Takes the frame {@link #peek()} showed. */
    public Frame take() {
        return this.ready.poll();
    }

    /** How many decoded frames are waiting. */
    public int buffered() {
        return this.ready.size();
    }

    /** Gives a frame back to the decoder once it is no longer shown. */
    public void recycle(Frame frame) {
        if (frame != null && frame.generation == this.generation) {
            this.free.offer(frame);
        }
    }

    /** Whether {@code frame} belongs to play {@code play}. */
    public static boolean of(Frame frame, int play) {
        return frame.play == play;
    }

    private void run() {
        while (true) {
            int current;
            synchronized (this.lock) {
                while (!this.playing) {
                    dropFrames();
                    try {
                        this.lock.wait();
                    } catch (InterruptedException interrupted) {
                        return;
                    }
                }
                current = this.play;
            }
            try {
                decode(current);
            } catch (InterruptedException interrupted) {
                return;
            } catch (IOException | RuntimeException | LinkageError failure) {
                this.failed = true;
                synchronized (this.lock) {
                    this.playing = false;
                }
                KoHsAnchorsClient.LOGGER.warn("Could not play {}", this.resource, failure);
            }
        }
    }

    /** Decodes play {@code current} until it ends or another starts. */
    private void decode(int current) throws IOException, InterruptedException {
        open();
        this.track.gotoFrame(0);
        long number = 0L;
        while (this.playing && this.play == current) {
            Frame frame = this.free.poll(40L, TimeUnit.MILLISECONDS);
            if (frame == null) {
                continue;
            }
            Packet packet = this.track.nextFrame();
            if (packet == null) {
                // The end of the clip: its first frame is a key frame, so decoding simply goes on.
                this.track.gotoFrame(0);
                packet = this.track.nextFrame();
                if (packet == null) {
                    throw new IOException("the clip has no frames");
                }
            }
            Picture picture = this.adaptor.decodeFrame(packet, this.planes);
            convert(picture, frame.pixels);
            frame.number = number++;
            frame.play = current;
            this.ready.put(frame);
        }
    }

    /** The demuxer and the decoder, built once; and the frames, built for each play. */
    private void open() throws IOException {
        if (this.demuxer == null) {
            byte[] bytes;
            try (InputStream stream = LoopingClip.class.getResourceAsStream(this.resource)) {
                if (stream == null) {
                    throw new IOException(this.resource + " is missing from the jar");
                }
                bytes = stream.readAllBytes();
            }
            this.demuxer = MP4Demuxer.createMP4Demuxer(ByteBufferSeekableByteChannel.readFromByteBuffer(ByteBuffer.wrap(bytes)));
            if (!(this.demuxer.getVideoTrack() instanceof SeekableDemuxerTrack seekable)) {
                throw new IOException(this.resource + " has no seekable video track");
            }
            this.track = seekable;
            this.adaptor = new AVCMP4Adaptor(this.track.getMeta());
            this.planes = this.adaptor.allocatePicture();
            double seconds = this.track.getMeta().getTotalDuration();
            int frames = this.track.getMeta().getTotalFrames();
            if (seconds > 0.0D && frames > 0) {
                this.frameRate = Math.max(1, (int) Math.round(frames / seconds));
            }
            // The first frame gives the size.
            Picture first = this.adaptor.decodeFrame(this.track.nextFrame(), this.planes);
            this.width = first.getCroppedWidth();
            this.height = first.getCroppedHeight();
            this.converted = new int[this.width * this.height];
        }
        while (this.allocated < SLOTS) {
            this.free.offer(new Frame(this.width * this.height * 4, this.generation));
            this.allocated++;
        }
    }

    private void dropFrames() {
        this.free.clear();
        this.ready.clear();
        this.allocated = 0;
        this.generation++;
    }

    /**
     * YUV 4:2:0 to RGBA with BT.601 limited-range coefficients, the clip's own. JCodec keeps
     * samples as signed bytes, 128 below their value.
     */
    private void convert(Picture picture, ByteBuffer target) {
        int width = this.width;
        int height = this.height;
        byte[] luma = picture.getPlaneData(0);
        byte[] blue = picture.getPlaneData(1);
        byte[] red = picture.getPlaneData(2);
        int lumaStride = picture.getPlaneWidth(0);
        int chromaStride = picture.getPlaneWidth(1);
        int[] out = this.converted;
        for (int y = 0; y < height; y++) {
            int lumaRow = y * lumaStride;
            int chromaRow = (y >> 1) * chromaStride;
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int c = 298 * (luma[lumaRow + x] + 112) + 128;
                int u = blue[chromaRow + (x >> 1)];
                int v = red[chromaRow + (x >> 1)];
                int r = (c + 409 * v) >> 8;
                int g = (c - 100 * u - 208 * v) >> 8;
                int b = (c + 516 * u) >> 8;
                r = r < 0 ? 0 : Math.min(r, 255);
                g = g < 0 ? 0 : Math.min(g, 255);
                b = b < 0 ? 0 : Math.min(b, 255);
                out[row + x] = LITTLE_ENDIAN ? 0xFF000000 | b << 16 | g << 8 | r : r << 24 | g << 16 | b << 8 | 0xFF;
            }
        }
        IntBuffer ints = target.clear().asIntBuffer();
        ints.put(out, 0, width * height);
    }
}
