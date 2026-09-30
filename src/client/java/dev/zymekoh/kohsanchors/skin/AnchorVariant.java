package dev.zymekoh.kohsanchors.skin;

import net.minecraft.resources.Identifier;

/**
 * The respawn anchor's textures, as the resource pack names them. Every one of them belongs to one
 * of three faces a player can paint: the top, the four identical sides, the bottom.
 */
public enum AnchorVariant {
    TOP_OFF("respawn_anchor_top_off", Face.TOP, 0),
    TOP("respawn_anchor_top", Face.TOP, -1),
    SIDE0("respawn_anchor_side0", Face.SIDE, 0),
    SIDE1("respawn_anchor_side1", Face.SIDE, 1),
    SIDE2("respawn_anchor_side2", Face.SIDE, 2),
    SIDE3("respawn_anchor_side3", Face.SIDE, 3),
    SIDE4("respawn_anchor_side4", Face.SIDE, 4),
    BOTTOM("respawn_anchor_bottom", Face.BOTTOM, -1);

    /** The faces a painted pixel applies to; each face's texel grid is shared by its variants. */
    public enum Face {
        TOP, SIDE, BOTTOM
    }

    private final String name;
    private final Face face;
    /** The charge this texture shows: 0 to 4 for the sides and the unlit top, -1 when any. */
    private final int charge;
    private final Identifier sprite;
    private final Identifier file;

    AnchorVariant(String name, Face face, int charge) {
        this.name = name;
        this.face = face;
        this.charge = charge;
        this.sprite = Identifier.withDefaultNamespace("block/" + name);
        this.file = Identifier.withDefaultNamespace("textures/block/" + name + ".png");
    }

    public Face face() {
        return this.face;
    }

    public int charge() {
        return this.charge;
    }

    /** The sprite's name in the block atlas. */
    public Identifier sprite() {
        return this.sprite;
    }

    /** The texture file in the resource packs. */
    public Identifier file() {
        return this.file;
    }

    public String textureName() {
        return this.name;
    }

    /** The side texture for {@code charge}. */
    public static AnchorVariant side(int charge) {
        return switch (Math.max(0, Math.min(4, charge))) {
            case 0 -> SIDE0;
            case 1 -> SIDE1;
            case 2 -> SIDE2;
            case 3 -> SIDE3;
            default -> SIDE4;
        };
    }

    /** The top texture for {@code charge}: dark while empty, the portal once charged. */
    public static AnchorVariant top(int charge) {
        return charge > 0 ? TOP : TOP_OFF;
    }
}
