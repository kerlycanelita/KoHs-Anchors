package dev.zymekoh.kohsanchors.gui;

/**
 * Geometry of the settings screen for one logical GUI size. Pure arithmetic with no Minecraft
 * state, so every size can be checked without starting the game.
 *
 * <p>Three shapes: STANDARD shows the options beside the 3D anchor and the session numbers;
 * COMPACT drops the anchor column and tightens the spacing; TIGHT keeps only what is needed to
 * change a setting and leave. The tab bar always sits between the header and the options, and the
 * options always scroll before anything overlaps. The warning modal has its own geometry.</p>
 */
final class AnchorsLayout {
    static final int MAX_PANEL_WIDTH = 620;
    static final int MAX_PANEL_HEIGHT = 380;
    /** Room on the right of the options for the scrollbar. */
    static final int SCROLL_GUTTER = 6;
    static final int TAB_COUNT = 4;
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

    static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Modal(Rect box, Rect cancel, Rect confirm, int padding) {
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
