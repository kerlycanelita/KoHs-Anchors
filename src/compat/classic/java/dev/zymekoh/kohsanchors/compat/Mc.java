package dev.zymekoh.kohsanchors.compat;

import dev.zymekoh.kohsanchors.mixin.MinecraftPickInvoker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Overlay;
import net.minecraft.client.gui.screens.Screen;

/** 26.1, 26.1.1 and 26.1.2: the screen and overlay still live on Minecraft. */
public final class Mc {
    private Mc() {
    }

    public static Screen screen(Minecraft minecraft) {
        return minecraft.screen;
    }

    public static Overlay overlay(Minecraft minecraft) {
        return minecraft.getOverlay();
    }

    public static void setScreen(Minecraft minecraft, Screen screen) {
        minecraft.setScreen(screen);
    }

    public static void pick(Minecraft minecraft) {
        ((MinecraftPickInvoker) minecraft).kohsAnchors$pick(1.0F);
    }

    public static void extractDeferredSubtitles(Minecraft minecraft) {
        minecraft.gui.extractDeferredSubtitles();
    }
}
