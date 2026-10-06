package dev.zymekoh.kohsanchors.glow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.AnchorVeil;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Safe anchor view: once the player's own anchor is charged, a square on the ground blinks where
 * one block would cover them from its explosion, the block of a "safe anchor".
 *
 * <p>It is Vanilla's own arithmetic, done on the client with what the player can already see: an
 * explosion hurts an entity by the share of rays, from points spread over its hitbox to the
 * explosion's centre, that no block stops ({@code ServerExplosion.getSeenPercent}), and the damage
 * falls with the distance. The anchor is gone by then, so it stops nothing. Every free block within
 * reach is tried as if a block stood there; the one that stops the most rays wins, if the player
 * can still reach a face of the anchor to detonate it past that block. Nothing is marked when the
 * explosion would barely touch the player as they stand (an anchor under a block, the player above
 * it, out of range), or when no single block helps much.</p>
 *
 * <p>Information only: it places nothing, aims at nothing and sends nothing. Armour counts, not its
 * enchantments, which the client cannot weigh, so it errs on the side of marking.</p>
 */
public final class SafeAnchorView {
    /** Anchor explosions: power 5, damage falling to nothing at twice that. */
    private static final float DIAMETER = 10.0F;
    private static final double SEARCH_SQR = 8.0D * 8.0D;
    /** Below this, after armour, the explosion as the player stands is not worth a block. */
    private static final float WORTH_DAMAGE = 3.0F;

    private static BlockPos anchor;
    private static BlockPos marker;
    private static float damageNow;
    private static float damageCovered;
    private static long lastCompute;

    private SafeAnchorView() {
    }

    /** The block to place, or null; for developer mode. */
    public static BlockPos marker() {
        return marker;
    }

    /** "anchor x y z · 22.4 → 6.1" for developer mode, or what it is waiting for. */
    public static String describe() {
        if (!AnchorsConfig.settings().safeAnchorView) {
            return "off";
        }
        if (anchor == null) {
            return "no charged anchor of yours within 8 blocks";
        }
        return "anchor " + anchor.toShortString() + " · damage " + String.format(java.util.Locale.ROOT, "%.1f", damageNow)
                + (marker == null ? " · nothing to mark" : " → " + String.format(java.util.Locale.ROOT, "%.1f", damageCovered)
                        + " with a block at " + marker.toShortString());
    }

    /** Once a client tick: looks again at most ten times a second. */
    public static void tick(Minecraft minecraft) {
        long now = System.nanoTime();
        if (now - lastCompute < 95_000_000L) {
            return;
        }
        lastCompute = now;
        anchor = null;
        marker = null;
        LocalPlayer player = minecraft.player;
        ClientLevel level = minecraft.level;
        if (!AnchorsConfig.settings().safeAnchorView || player == null || level == null || player.isSpectator()
                || player.isCreative()) {
            return;
        }
        BlockPos nearest = null;
        double nearestDistance = SEARCH_SQR;
        for (Long2ByteMap.Entry entry : AnchorTracker.anchors().long2ByteEntrySet()) {
            if (entry.getByteValue() != AnchorTracker.OWN) {
                continue;
            }
            BlockPos position = BlockPos.of(entry.getLongKey());
            double distance = position.distToCenterSqr(player.position());
            if (distance < nearestDistance && charged(level, position)) {
                nearest = position;
                nearestDistance = distance;
            }
        }
        if (nearest == null || DetonationPredictor.anchorsWork(level, nearest)) {
            return;
        }
        anchor = nearest;
        compute(level, player, nearest);
    }

    private static boolean charged(ClientLevel level, BlockPos position) {
        BlockState drawn = AnchorVeil.predicted(position);
        BlockState state = drawn != null ? drawn : level.getBlockState(position);
        return state.is(Blocks.RESPAWN_ANCHOR) && state.getValue(RespawnAnchorBlock.CHARGE) > 0;
    }

    private static void compute(ClientLevel level, LocalPlayer player, BlockPos anchorPos) {
        Vec3 center = Vec3.atCenterOf(anchorPos);
        double distance = Math.sqrt(player.distanceToSqr(center)) / DIAMETER;
        if (distance > 1.0D) {
            return;
        }
        List<Vec3> open = new ArrayList<>();
        int rays = openRays(level, player, anchorPos, center, open);
        if (rays == 0) {
            return;
        }
        damageNow = damage(level, player, distance, open.size() / (float) rays);
        if (damageNow < WORTH_DAMAGE) {
            return;
        }
        // Every free block within reach, between the player and the anchor and a step around.
        Vec3 eye = player.getEyePosition();
        double reach = player.blockInteractionRange();
        AABB around = new AABB(anchorPos).minmax(player.getBoundingBox()).inflate(1.0D);
        BlockPos best = null;
        float bestDamage = damageNow;
        double bestToAnchor = Double.MAX_VALUE;
        for (BlockPos candidate : BlockPos.betweenClosed(Mth.floor(around.minX), Mth.floor(around.minY), Mth.floor(around.minZ),
                Mth.floor(around.maxX), Mth.floor(around.maxY), Mth.floor(around.maxZ))) {
            if (candidate.equals(anchorPos) || !placeable(level, player, candidate, anchorPos, eye, reach)) {
                continue;
            }
            AABB block = new AABB(candidate);
            int stopped = 0;
            for (Vec3 from : open) {
                if (block.clip(from, center).isPresent() || block.contains(from)) {
                    stopped++;
                }
            }
            if (stopped == 0) {
                continue;
            }
            float covered = damage(level, player, distance, (open.size() - stopped) / (float) rays);
            double toAnchor = candidate.distSqr(anchorPos);
            if (covered < bestDamage - 0.01F || Math.abs(covered - bestDamage) <= 0.01F && toAnchor < bestToAnchor) {
                if (canStillDetonate(level, eye, anchorPos, block)) {
                    best = candidate.immutable();
                    bestDamage = covered;
                    bestToAnchor = toAnchor;
                }
            }
        }
        if (best != null && damageNow - bestDamage >= Math.max(2.0F, damageNow * 0.3F)) {
            marker = best;
            damageCovered = bestDamage;
        }
    }

    /**
     * The rays Vanilla casts from the player's hitbox to the explosion: how many there are, and the
     * starting points of those no block stops. The anchor itself is gone when it explodes.
     */
    private static int openRays(ClientLevel level, LocalPlayer player, BlockPos anchorPos, Vec3 center, List<Vec3> open) {
        AABB box = player.getBoundingBox();
        double stepX = 1.0D / ((box.maxX - box.minX) * 2.0D + 1.0D);
        double stepY = 1.0D / ((box.maxY - box.minY) * 2.0D + 1.0D);
        double stepZ = 1.0D / ((box.maxZ - box.minZ) * 2.0D + 1.0D);
        double offsetX = (1.0D - Math.floor(1.0D / stepX) * stepX) / 2.0D;
        double offsetZ = (1.0D - Math.floor(1.0D / stepZ) * stepZ) / 2.0D;
        CollisionContext context = CollisionContext.of(player);
        int rays = 0;
        for (double x = 0.0D; x <= 1.0D; x += stepX) {
            for (double y = 0.0D; y <= 1.0D; y += stepY) {
                for (double z = 0.0D; z <= 1.0D; z += stepZ) {
                    Vec3 from = new Vec3(Mth.lerp(x, box.minX, box.maxX) + offsetX, Mth.lerp(y, box.minY, box.maxY),
                            Mth.lerp(z, box.minZ, box.maxZ) + offsetZ);
                    rays++;
                    if (!blocked(level, from, center, anchorPos, context)) {
                        open.add(from);
                    }
                }
            }
        }
        return rays;
    }

    /** Whether a block other than {@code ignore} stops the segment, by its collision shape. */
    private static boolean blocked(ClientLevel level, Vec3 from, Vec3 to, BlockPos ignore, CollisionContext context) {
        Boolean hit = BlockGetter.traverseBlocks(from, to, context, (shapeContext, position) -> {
            if (position.equals(ignore)) {
                return null;
            }
            BlockState state = level.getBlockState(position);
            BlockHitResult result = state.getCollisionShape(level, position, shapeContext).clip(from, to, position);
            return result != null ? Boolean.TRUE : null;
        }, shapeContext -> Boolean.FALSE);
        return Boolean.TRUE.equals(hit);
    }

    /**
     * The damage Vanilla deals at {@code distance} (a share of the explosion's reach) with
     * {@code seen} of the rays open, after difficulty, armour and Resistance.
     */
    private static float damage(ClientLevel level, LocalPlayer player, double distance, float seen) {
        double exposure = (1.0D - distance) * seen;
        float damage = (float) ((exposure * exposure + exposure) / 2.0D * 7.0D * DIAMETER + 1.0D);
        Difficulty difficulty = level.getDifficulty();
        if (difficulty == Difficulty.PEACEFUL) {
            return 0.0F;
        }
        if (difficulty == Difficulty.EASY) {
            damage = Math.min(damage / 2.0F + 1.0F, damage);
        } else if (difficulty == Difficulty.HARD) {
            damage *= 1.5F;
        }
        float armor = player.getArmorValue();
        float toughness = (float) player.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        float effective = Mth.clamp(armor - damage / (2.0F + toughness / 4.0F), armor * 0.2F, 20.0F);
        damage *= 1.0F - effective / 25.0F;
        MobEffectInstance resistance = player.getEffect(MobEffects.RESISTANCE);
        if (resistance != null) {
            damage *= Math.max(0.0F, 1.0F - 0.2F * (resistance.getAmplifier() + 1));
        }
        return damage;
    }

    /**
     * Whether a block can go down at {@code candidate}: free, outside the player, within reach, and
     * against a solid face that is not the anchor's (clicking the anchor would charge or detonate it).
     */
    private static boolean placeable(ClientLevel level, LocalPlayer player, BlockPos candidate, BlockPos anchorPos, Vec3 eye,
            double reach) {
        BlockState state = level.getBlockState(candidate);
        if (!state.canBeReplaced() || !state.getFluidState().isEmpty() || AnchorVeil.isVeiled(candidate)
                || player.getBoundingBox().intersects(new AABB(candidate))) {
            return false;
        }
        if (Vec3.atCenterOf(candidate).distanceTo(eye) > reach + 0.5D) {
            return false;
        }
        for (Direction direction : Direction.values()) {
            BlockPos support = candidate.relative(direction);
            if (!support.equals(anchorPos) && !level.getBlockState(support).canBeReplaced()) {
                return true;
            }
        }
        return false;
    }

    /** Whether the eye still sees some face of the anchor with a block at {@code shield}. */
    private static boolean canStillDetonate(ClientLevel level, Vec3 eye, BlockPos anchorPos, AABB shield) {
        Vec3 center = Vec3.atCenterOf(anchorPos);
        CollisionContext context = CollisionContext.empty();
        for (Direction direction : Direction.values()) {
            Vec3 face = center.add(direction.getStepX() * 0.45D, direction.getStepY() * 0.45D, direction.getStepZ() * 0.45D);
            Vec3 outside = center.add(direction.getStepX() * 0.5D, direction.getStepY() * 0.5D, direction.getStepZ() * 0.5D);
            if (eye.subtract(outside).dot(new Vec3(direction.getStepX(), direction.getStepY(), direction.getStepZ())) <= 0.0D) {
                continue;
            }
            if (shield.clip(eye, face).isEmpty() && !blocked(level, eye, outside, anchorPos, context)) {
                return true;
            }
        }
        return false;
    }

    /** The blinking square on the ground of the block to place, with the frame's block entities. */
    public static void submit(PoseStack poses, SubmitNodeCollector collector, Vec3 camera) {
        BlockPos target = marker;
        if (target == null) {
            return;
        }
        int rgb = AnchorsConfig.settings().safeAnchorColor & 0xFFFFFF;
        double seconds = System.nanoTime() / 1_000_000_000.0D;
        // A steady blink, soft at both ends: it calls the eye without flashing.
        float blink = 0.5F + 0.5F * (float) Math.sin(seconds * Math.PI * 2.0D * 2.2D);
        int fill = Math.round(40 + 110 * blink) << 24 | rgb;
        int edge = Math.round(150 + 105 * blink) << 24 | rgb;
        poses.pushPose();
        poses.translate(target.getX() - camera.x, target.getY() - camera.y, target.getZ() - camera.z);
        collector.submitCustomGeometry(poses, RenderTypes.lightning(), (pose, consumer) -> {
            float y = 0.012F;
            quad(pose, consumer, 0.08F, y, 0.08F, 0.92F, 0.92F, fill);
            float w = 0.06F;
            quad(pose, consumer, 0.0F, y, 0.0F, 1.0F, w, edge);
            quad(pose, consumer, 0.0F, y, 1.0F - w, 1.0F, 1.0F, edge);
            quad(pose, consumer, 0.0F, y, w, w, 1.0F - w, edge);
            quad(pose, consumer, 1.0F - w, y, w, 1.0F, 1.0F - w, edge);
        });
        poses.popPose();
    }

    /** A flat square from {@code x0, z0} to {@code x1, z1} at height {@code y}, seen from above. */
    private static void quad(PoseStack.Pose pose, VertexConsumer consumer, float x0, float y, float z0, float x1, float z1,
            int color) {
        consumer.addVertex(pose, x0, y, z0).setColor(color);
        consumer.addVertex(pose, x0, y, z1).setColor(color);
        consumer.addVertex(pose, x1, y, z1).setColor(color);
        consumer.addVertex(pose, x1, y, z0).setColor(color);
    }
}
