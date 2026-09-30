package dev.zymekoh.kohsanchors.gui;

/**
 * Geometry of the settings screen for one logical GUI size. Pure arithmetic with no Minecraft
 * state, so every size can be checked without starting the game.
 *
 * <p>Three shapes: STANDARD shows the options beside the 3D anchor and the session numbers;
 * COMPACT drops the anchor column and tightens the spacing; TIGHT keeps only what is needed to
 * change a setting and leave. The tab bar always sits between the header and the options, and the
 * options always scroll before anything overlaps. The warning modal and the anchor workshop have
 * their own geometry.</p>
 */
final class AnchorsLayout {
    static final int MAX_PANEL_WIDTH = 620;
    static final int MAX_PANEL_HEIGHT = 380;
    /** Room on the right of the options for the scrollbar. */
    static final int SCROLL_GUTTER = 6;
    static final int TAB_COUNT = 5;
    /** Below this a tab shows its icon only. */
    static final int MIN_TAB_WIDTH = 24;

    final int screenWidth;
    final int screenHeight;
    final Rect panel;
    final Rect header;
    final Rect tabs;
    final Rect options;
    final Rect preview;
    final Rect footer;
    final Rect resetButton;
    final Rect doneButton;
    final boolean compact;
    final boolean tight;
    final int padding;

    private AnchorsLayout(int screenWidth, int screenHeight, Rect panel, Rect header, Rect tabs, Rect options,
            Rect preview, Rect footer, Rect resetButton, Rect doneButton, boolean compact, boolean tight, int padding) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.panel = panel;
        this.header = header;
        this.tabs = tabs;
        this.options = options;
        this.preview = preview;
        this.footer = footer;
        this.resetButton = resetButton;
        this.doneButton = doneButton;
        this.compact = compact;
        this.tight = tight;
        this.padding = padding;
    }

    static AnchorsLayout fit(int width, int height) {
        int marginX = clamp(width / 20, 4, 28);
        int marginY = clamp(height / 16, 3, 22);
        int panelWidth = Math.min(MAX_PANEL_WIDTH, Math.max(0, width - marginX * 2));
        int panelHeight = Math.min(MAX_PANEL_HEIGHT, Math.max(0, height - marginY * 2));
        Rect panel = new Rect((width - panelWidth) / 2, (height - panelHeight) / 2, panelWidth, panelHeight);

        boolean compact = panelWidth < 440 || panelHeight < 260;
        boolean tight = panelWidth < 300 || panelHeight < 190;
        int padding = tight ? 4 : compact ? 6 : 10;

        // Header, tabs and footer never take more than their share of a short screen.
        int headerHeight = Math.min(compact ? 24 : 36, Math.max(16, panelHeight / 6));
        int tabsHeight = tight ? 13 : compact ? 16 : 19;
        int footerHeight = Math.min(compact ? 22 : 28, Math.max(16, panelHeight / 7));
        Rect header = new Rect(panel.x(), panel.y(), panel.width(), headerHeight);
        Rect tabs = new Rect(panel.x() + padding, header.bottom() + (tight ? 1 : 3), Math.max(0, panel.width() - padding * 2),
                tabsHeight);
        Rect footer = new Rect(panel.x(), panel.bottom() - footerHeight, panel.width(), footerHeight);

        int bodyX = panel.x() + padding;
        int bodyY = tabs.bottom() + (compact ? 3 : 6);
        int bodyWidth = Math.max(0, panel.width() - padding * 2);
        int bodyHeight = Math.max(0, footer.y() - bodyY - (compact ? 2 : 4));

        // The anchor column is decoration and statistics: it only appears when the options keep
        // a comfortable width beside it.
        boolean showPreview = bodyWidth >= 390 && bodyHeight >= 150;
        int previewWidth = showPreview ? clamp(Math.round(bodyWidth * 0.34F), 130, 210) : 0;
        int gap = showPreview ? 8 : 0;
        Rect options = new Rect(bodyX, bodyY, bodyWidth - previewWidth - gap, bodyHeight);
        Rect preview = showPreview ? new Rect(options.right() + gap, bodyY, previewWidth, bodyHeight) : Rect.EMPTY;

        int buttonHeight = clamp(footerHeight - 8, 12, 20);
        int buttonWidth = clamp(panel.width() / 5, 50, 96);
        int buttonY = footer.y() + (footerHeight - buttonHeight) / 2;
        Rect doneButton = new Rect(footer.right() - padding - buttonWidth, buttonY, buttonWidth, buttonHeight);
        Rect resetButton = new Rect(footer.x() + padding, buttonY, buttonWidth, buttonHeight);

        return new AnchorsLayout(width, height, panel, header, tabs, options, preview, footer, resetButton, doneButton,
                compact, tight, padding);
    }

    boolean showsPreview() {
        return this.preview.width() > 0;
    }

    /** The whole body under the tabs: the options and the anchor column together. */
    Rect body() {
        int right = showsPreview() ? this.preview.right() : this.options.right();
        return new Rect(this.options.x(), this.options.y(), right - this.options.x(), this.options.height());
    }

    /**
     * The anchor workshop inside {@code body}: a rail of modes and tools on the left, the stage
     * with the anchor in the middle and a rail with the layers and the colour on the right. On a
     * narrow body the left rail shows icons only.
     */
    static Workshop workshop(Rect body) {
        boolean narrow = body.width() < 420;
        int gap = narrow ? 4 : 6;
        int leftWidth = narrow ? 26 : clamp(Math.round(body.width() * 0.19F), 88, 112);
        int rightWidth = narrow ? clamp(Math.round(body.width() * 0.42F), 104, 176)
                : clamp(Math.round(body.width() * 0.33F), 150, 204);
        Rect left = new Rect(body.x(), body.y(), leftWidth, body.height());
        Rect right = new Rect(body.right() - rightWidth, body.y(), rightWidth, body.height());
        Rect stage = new Rect(left.right() + gap, body.y(), Math.max(0, right.x() - gap - left.right() - gap),
                body.height());
        return new Workshop(left, stage, right, narrow);
    }

    /** Width of an option row inside the scrolling area. */
    int rowWidth() {
        return Math.max(0, this.options.width() - SCROLL_GUTTER);
    }

    /** One tab of the bar, the bar split into equal parts with a 3px gap. */
    Rect tab(int index) {
        int gap = 3;
        int width = (this.tabs.width() - gap * (TAB_COUNT - 1)) / TAB_COUNT;
        return new Rect(this.tabs.x() + index * (width + gap), this.tabs.y(), Math.max(0, width), this.tabs.height());
    }

    /** The warning modal and its two buttons, for a screen of this size. */
    static Modal modal(int width, int height) {
        int modalWidth = Math.min(Math.max(0, width - 12), clamp(width - 40, 220, 390));
        int modalHeight = Math.min(Math.max(0, height - 10), clamp(height - 30, 150, 236));
        Rect box = new Rect((width - modalWidth) / 2, (height - modalHeight) / 2, modalWidth, modalHeight);
        int padding = modalHeight < 180 ? 6 : 12;
        int buttonHeight = modalHeight < 180 ? 14 : 18;
        int buttonY = box.bottom() - padding - buttonHeight;
        int cancelWidth = clamp(modalWidth / 3, 56, 110);
        Rect cancel = new Rect(box.x() + padding, buttonY, cancelWidth, buttonHeight);
        int confirmX = cancel.right() + 8;
        Rect confirm = new Rect(confirmX, buttonY, Math.max(0, box.right() - padding - confirmX), buttonHeight);
        return new Modal(box, cancel, confirm, padding);
    }

    /** Below this height the guard warning's clip is left out and the text stays. */
    static final int MIN_CLIP_HEIGHT = 40;

    /**
     * The glowstone guard's warning: a title, the clip at its own aspect ({@code aspect} is width
     * over height), the text under it in {@code bodyLines} lines, and two buttons. The clip takes
     * whatever room the rest leaves, and is dropped before the text when that is too little.
     */
    static GuardModal guardModal(int width, int height, int bodyLines, float aspect) {
        int boxWidth = Math.min(Math.max(0, width - 12), clamp(width - 40, 240, 470));
        int boxHeight = Math.min(Math.max(0, height - 10), clamp(height - 24, 170, 340));
        Rect box = new Rect((width - boxWidth) / 2, (height - boxHeight) / 2, boxWidth, boxHeight);
        boolean small = boxHeight < 210;
        int padding = small ? 6 : 12;
        int buttonHeight = small ? 14 : 18;
        int buttonY = box.bottom() - padding - buttonHeight;
        int cancelWidth = clamp(boxWidth / 3, 56, 130);
        Rect cancel = new Rect(box.x() + padding, buttonY, cancelWidth, buttonHeight);
        int confirmX = cancel.right() + 8;
        Rect confirm = new Rect(confirmX, buttonY, Math.max(0, box.right() - padding - confirmX), buttonHeight);

        Rect title = new Rect(box.x() + padding, box.y() + padding, boxWidth - padding * 2, small ? 20 : 31);
        int innerWidth = boxWidth - padding * 2;
        int bodyHeight = Math.max(1, bodyLines) * 10;
        int top = title.bottom() + (small ? 3 : 6);
        int bottom = buttonY - (small ? 4 : 8);
        int room = bottom - top - bodyHeight - 5;
        int clipWidth = Math.min(innerWidth, Math.round(room * aspect));
        int clipHeight = Math.round(clipWidth / aspect);
        Rect clip = Rect.EMPTY;
        int contentHeight = bodyHeight;
        if (clipHeight >= MIN_CLIP_HEIGHT && clipHeight <= room) {
            clip = new Rect(box.centerX() - clipWidth / 2, 0, clipWidth, clipHeight);
            contentHeight += clipHeight + 5;
        }
        // The clip and the text sit together in the middle of the room between title and buttons.
        int contentTop = top + Math.max(0, (bottom - top - contentHeight) / 2);
        if (clip.width() > 0) {
            clip = new Rect(clip.x(), contentTop, clip.width(), clip.height());
            contentTop = clip.bottom() + 5;
        }
        Rect body = new Rect(box.x() + padding, contentTop, innerWidth, Math.min(bodyHeight, Math.max(0, bottom - contentTop)));
        return new GuardModal(box, title, clip, body, cancel, confirm, padding, small);
    }

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Modal(Rect box, Rect cancel, Rect confirm, int padding) {
    }

    /** The glowstone guard warning: its box, title, clip (possibly empty), text and buttons. */
    record GuardModal(Rect box, Rect title, Rect clip, Rect body, Rect cancel, Rect confirm, int padding, boolean small) {
    }

    record Workshop(Rect left, Rect stage, Rect right, boolean narrow) {
    }

    record Rect(int x, int y, int width, int height) {
        static final Rect EMPTY = new Rect(0, 0, 0, 0);

        int right() {
            return this.x + this.width;
        }

        int bottom() {
            return this.y + this.height;
        }

        int centerX() {
            return this.x + this.width / 2;
        }

        int centerY() {
            return this.y + this.height / 2;
        }

        boolean contains(double pointX, double pointY) {
            return pointX >= this.x && pointX < right() && pointY >= this.y && pointY < bottom();
        }

        boolean intersects(Rect other) {
            return this.x < other.right() && other.x < right() && this.y < other.bottom() && other.y < bottom();
        }
    }
}
