package dev.zymekoh.kohsanchors.gui;

import com.mojang.blaze3d.platform.InputConstants;

/**
 * The keys, mouse buttons and modifiers the settings screen answers to, as the Minecraft version
 * the jar is built for numbers them: GLFW's codes up to 26.2, SDL's scancodes from 26.3 (where the
 * left button is 1, Escape is 41 and Ctrl is 192). Every value is Minecraft's own constant, so each
 * jar carries its version's numbers.
 */
final class Keys {
    static final int ESCAPE = InputConstants.KEY_ESCAPE;
    static final int ENTER = InputConstants.KEY_RETURN;
    static final int KEYPAD_ENTER = InputConstants.KEY_NUMPADENTER;
    static final int BACKSPACE = InputConstants.KEY_BACKSPACE;
    static final int SPACE = InputConstants.KEY_SPACE;
    static final int LEFT = InputConstants.KEY_LEFT;
    static final int RIGHT = InputConstants.KEY_RIGHT;
    static final int UP = InputConstants.KEY_UP;
    static final int DOWN = InputConstants.KEY_DOWN;
    static final int HOME = InputConstants.KEY_HOME;
    static final int END = InputConstants.KEY_END;
    static final int F12 = InputConstants.KEY_F12;
    static final int Y = InputConstants.KEY_Y;
    static final int Z = InputConstants.KEY_Z;

    static final int LEFT_BUTTON = InputConstants.MOUSE_BUTTON_LEFT;
    static final int RIGHT_BUTTON = InputConstants.MOUSE_BUTTON_RIGHT;

    static final int CONTROL = InputConstants.MOD_CONTROL;

    private Keys() {
    }

    /** Enter on the main keyboard or the keypad. */
    static boolean confirms(int key) {
        return key == ENTER || key == KEYPAD_ENTER;
    }
}
