package dev.zymekoh.kohsanchors.input;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Where the crosshair was at each use press. Minecraft reads the crosshair once per client tick,
 * 20 times a second, so every click of a tick goes where the camera points when the tick runs: a
 * hand that clicks one anchor and is already on the next one 30 ms later sends both clicks to the
 * second. Measured in the lab (bench FLICK): below about 50 ms between two targets, Vanilla, Herzium
 * and Anchor Optimizer all put both charges on one anchor.
 *
 * <p>Each use press keeps the player's rotation at that moment; when the use runs, it aims along
 * that rotation instead, if the camera has turned since and the ray still finds a block within
 * reach with no entity in front of it. The rotations the server receives are the player's own,
 * unchanged: the click goes where the player was looking when they clicked.</p>
 */
final class PressAim {
    private static final int CAPACITY = 16;
    /** A press older than this belongs to a moment that has passed. */
    private static final long MAX_AGE_NANOS = 1_000_000_000L;
    /** Below this turn, in degrees, the tick's own target is the press's. */
    private static final float TURN = 0.5F;

    private static final float[] YAW = new float[CAPACITY];
    private static final float[] PITCH = new float[CAPACITY];
    private static final long[] AT = new long[CAPACITY];
    private static int head;
    private static int count;
    private static boolean current;
    private static float currentYaw;
    private static float currentPitch;
    /** The press of the use being decided, aimed or not: kept with it if it waits for the next tick. */
    private static boolean pressValid;
    private static float pressYaw;
    private static float pressPitch;
    private static HitResult tickHit;
    private static boolean swapped;

    private PressAim() {
    }

    /** A use press, counted now: the rotation it was made with. */
    static void pressed(LocalPlayer player) {
        if (count == CAPACITY) {
            head = (head + 1) % CAPACITY;
            count--;
        }
        int slot = (head + count) % CAPACITY;
        YAW[slot] = player.getYRot();
        PITCH[slot] = player.getXRot();
        AT[slot] = System.nanoTime();
        count++;
    }

    /** A use press was taken by the game: the oldest one waiting is the one about to run. */
    static void consumed() {
        current = false;
        long now = System.nanoTime();
        while (count > 0) {
            int slot = head;
            head = (head + 1) % CAPACITY;
            count--;
            if (now - AT[slot] <= MAX_AGE_NANOS) {
                currentYaw = YAW[slot];
                currentPitch = PITCH[slot];
                current = true;
                return;
            }
        }
    }

    /**
     * Start of a tick's input pass: only the last {@code pending} presses are still waiting to be
     * taken (the game drops presses without taking them, for example when a screen opens). Older
     * ones go, so each use still gets its own press's rotation.
     */
    static void sync(int pending) {
        while (count > Math.max(0, pending)) {
            head = (head + 1) % CAPACITY;
            count--;
        }
    }

    static void clear() {
        count = 0;
        current = false;
    }

    /**
     * Before a use runs: aims it along its press's rotation when the camera has turned since and
     * that ray hits a block in reach first. Returns whether the use was aimed so.
     */
    static boolean aim(Minecraft minecraft) {
        if (swapped) {
            // Each use starts from the crosshair's own target: only its own press may move it.
            minecraft.hitResult = tickHit;
        }
        pressValid = current;
        pressYaw = currentYaw;
        pressPitch = currentPitch;
        if (!current) {
            return false;
        }
        current = false;
        LocalPlayer player = minecraft.player;
        if (player == null || minecraft.level == null || minecraft.hitResult instanceof EntityHitResult) {
            return false;
        }
        if (Math.abs(Mth.wrapDegrees(currentYaw - player.getYRot())) < TURN && Math.abs(currentPitch - player.getXRot()) < TURN) {
            return false;
        }
        Vec3 eye = player.getEyePosition();
        Vec3 view = Vec3.directionFromRotation(currentPitch, currentYaw);
        double reach = player.blockInteractionRange();
        BlockHitResult hit = minecraft.level.clip(new ClipContext(eye, eye.add(view.scale(reach)), ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE, player));
        if (hit.getType() != HitResult.Type.BLOCK) {
            return false;
        }
        // Something in front of the block along that ray: the press was aimed at it, not the block.
        double blockDistance = hit.getLocation().distanceToSqr(eye);
        Vec3 end = eye.add(view.scale(Math.sqrt(blockDistance)));
        AABB sweep = player.getBoundingBox().expandTowards(view.scale(Math.sqrt(blockDistance))).inflate(1.0D);
        EntityHitResult entity = ProjectileUtil.getEntityHitResult(player, eye, end, sweep,
                (Entity candidate) -> !candidate.isSpectator() && candidate.isPickable(), blockDistance);
        if (entity != null) {
            return false;
        }
        if (!swapped) {
            tickHit = minecraft.hitResult;
            swapped = true;
        }
        minecraft.hitResult = hit;
        AnchorStats.pressAimed();
        return true;
    }

    /** A click that waited for the next tick, aimed along the rotation its press had. */
    static boolean aimAt(Minecraft minecraft, float yaw, float pitch) {
        current = true;
        currentYaw = yaw;
        currentPitch = pitch;
        return aim(minecraft);
    }

    static boolean pressValid() {
        return pressValid;
    }

    static float pressYaw() {
        return pressYaw;
    }

    static float pressPitch() {
        return pressPitch;
    }

    /** End of the tick's input pass: the crosshair's own target again, for drawing and attacks. */
    static void restore(Minecraft minecraft) {
        if (swapped) {
            minecraft.hitResult = tickHit;
            tickHit = null;
            swapped = false;
        }
    }
}
