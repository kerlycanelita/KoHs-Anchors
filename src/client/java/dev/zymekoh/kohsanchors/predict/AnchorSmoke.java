package dev.zymekoh.kohsanchors.predict;

import dev.zymekoh.kohsanchors.config.AnchorsConfig;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleTypes;

/**
 * The smoke of an anchor's explosion. Vanilla's emitter puffs a cloud for a second that hides the
 * other player in a chain of explosions; the light setting puts one small puff where it was, and
 * none puts nothing. Sound, light flash and knockback are not particles and do not change; other
 * explosions keep their smoke.
 */
public final class AnchorSmoke {
    public static final int VANILLA = 0;
    public static final int LIGHT = 1;
    public static final int NONE = 2;

    private AnchorSmoke() {
    }

    /** Whether Vanilla's own flash particle should be drawn for an anchor's explosion. */
    public static boolean vanilla() {
        return AnchorsConfig.settings().anchorSmoke == VANILLA;
    }

    /** The anchor explosion's smoke at a centre, as the setting wants it. */
    public static void flash(ClientLevel level, double x, double y, double z) {
        switch (AnchorsConfig.settings().anchorSmoke) {
            case LIGHT -> level.addParticle(ParticleTypes.EXPLOSION, x, y, z, 1.0D, 0.0D, 0.0D);
            case NONE -> {
            }
            default -> level.addParticle(ParticleTypes.EXPLOSION_EMITTER, x, y, z, 1.0D, 0.0D, 0.0D);
        }
    }
}
