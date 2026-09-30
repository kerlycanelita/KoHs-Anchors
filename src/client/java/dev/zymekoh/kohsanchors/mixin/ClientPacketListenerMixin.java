package dev.zymekoh.kohsanchors.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import dev.zymekoh.kohsanchors.predict.AnchorSmoke;
import dev.zymekoh.kohsanchors.predict.DetonationPredictor;
import dev.zymekoh.kohsanchors.sound.AnchorSounds;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ExplosionParticleInfo;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.random.WeightedList;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * The server's explosion: its sound, then its flash, then its block debris, then knockback. The
 * first two are skipped when a prediction already showed them; the sound of an anchor's explosion
 * is the player's own when they chose one; debris is only skipped for anchors with anchor debris
 * turned off; knockback is never touched. The server's charge sound is swapped the same way.
 *
 * <p>Each call is wrapped where it happens instead of at the head of the handler, which first
 * runs on the network thread only to be rescheduled onto the client thread.</p>
 */
@Mixin(ClientPacketListener.class)
abstract class ClientPacketListenerMixin {
    @WrapOperation(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;playLocalSound(DDDLnet/minecraft/sounds/SoundEvent;Lnet/minecraft/sounds/SoundSource;FFZ)V"))
    private void kohsAnchors$explosionSound(ClientLevel level, double x, double y, double z, SoundEvent sound,
            SoundSource source, float volume, float pitch, boolean distanceDelay, Operation<Void> original) {
        if (!DetonationPredictor.shouldPlayServerSound(x, y, z)) {
            return;
        }
        AnchorsConfig.AnchorSound custom = AnchorSounds.explosionReplacement(level, x, y, z);
        if (custom == null) {
            original.call(level, x, y, z, sound, source, volume, pitch, distanceDelay);
            return;
        }
        original.call(level, x, y, z, AnchorSounds.event(custom.sound), source, volume * custom.volume / 100.0F,
                pitch * custom.pitch / 100.0F, distanceDelay);
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;addParticle(Lnet/minecraft/core/particles/ParticleOptions;DDDDDD)V"))
    private boolean kohsAnchors$explosionFlash(ClientLevel level, ParticleOptions particle, double x, double y, double z,
            double xSpeed, double ySpeed, double zSpeed) {
        if (!DetonationPredictor.shouldDrawServerFlash()) {
            return false;
        }
        if (AnchorSmoke.vanilla() || !DetonationPredictor.isAnchorExplosion(level, new Vec3(x, y, z))) {
            return true;
        }
        // An anchor's explosion with less smoke: the lighter puff, or none, in place of Vanilla's cloud.
        AnchorSmoke.flash(level, x, y, z);
        return false;
    }

    @WrapWithCondition(method = "handleExplosion", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;trackExplosionEffects(Lnet/minecraft/world/phys/Vec3;FILnet/minecraft/util/random/WeightedList;)V"))
    private boolean kohsAnchors$explosionDebris(ClientLevel level, Vec3 center, float radius, int blockCount,
            WeightedList<ExplosionParticleInfo> particles) {
        return DetonationPredictor.shouldDrawDebris(level, center);
    }

    /** A sound the server played at a position: an anchor's charge becomes the player's choice. */
    @WrapOperation(method = "handleSoundEvent", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;playSeededSound(Lnet/minecraft/world/entity/Entity;DDDLnet/minecraft/core/Holder;Lnet/minecraft/sounds/SoundSource;FFJ)V"))
    private void kohsAnchors$chargeSound(ClientLevel level, Entity entity, double x, double y, double z,
            Holder<SoundEvent> sound, SoundSource source, float volume, float pitch, long seed,
            Operation<Void> original) {
        AnchorsConfig.AnchorSound custom = AnchorSounds.chargeReplacement(sound);
        if (custom == null) {
            original.call(level, entity, x, y, z, sound, source, volume, pitch, seed);
            return;
        }
        original.call(level, entity, x, y, z, AnchorSounds.holder(custom.sound), source,
                volume * custom.volume / 100.0F, pitch * custom.pitch / 100.0F, seed);
    }
}
