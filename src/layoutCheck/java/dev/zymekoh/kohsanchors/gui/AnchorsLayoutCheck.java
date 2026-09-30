package dev.zymekoh.kohsanchors.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Fits the settings screen to every logical GUI size from 240 x 140 to 1600 x 1000 and fails the
 * build if any of them puts a region outside the screen, lets two regions overlap, or leaves no
 * room for the options. Runs without Minecraft: {@code gradlew checkLayout}.
 */
public final class AnchorsLayoutCheck {
    private AnchorsLayoutCheck() {
    }

    public static void main(String[] arguments) {
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (int width = 240; width <= 1600; width += 7) {
            for (int height = 140; height <= 1000; height += 7) {
                check(width, height, failures);
                checked++;
            }
        }
        // Real windows at every GUI scale Minecraft offers for them: it never picks a scale that
        // leaves less than 320 x 240 logical pixels.
        int[][] windows = {{1280, 720}, {1366, 768}, {1920, 1080}, {1920, 1001}, {2560, 1440}, {854, 480}};
        for (int[] window : windows) {
            for (int scale = 1; window[0] / scale >= 320 && window[1] / scale >= 240; scale++) {
                check(window[0] / scale, window[1] / scale, failures);
                checked++;
            }
        }
        if (!failures.isEmpty()) {
            failures.stream().limit(40).forEach(System.err::println);
            throw new IllegalStateException(failures.size() + " layout problems in " + checked + " sizes");
        }
        System.out.println("Layout OK in " + checked + " sizes");
    }

    private static void check(int width, int height, List<String> failures) {
        AnchorsLayout layout = AnchorsLayout.fit(width, height);
        String size = width + "x" + height + ": ";
        AnchorsLayout.Rect screen = new AnchorsLayout.Rect(0, 0, width, height);
        AnchorsLayout.Rect panel = layout.panel;

        inside(size + "panel", panel, screen, failures);
        inside(size + "header", layout.header, panel, failures);
        inside(size + "options", layout.options, panel, failures);
        inside(size + "footer", layout.footer, panel, failures);
        inside(size + "done", layout.doneButton, layout.footer, failures);
        inside(size + "reset", layout.resetButton, layout.footer, failures);
        if (layout.header.bottom() > layout.options.y()) {
            failures.add(size + "header overlaps the options");
        }
        if (layout.options.bottom() > layout.footer.y()) {
            failures.add(size + "options overlap the footer");
        }
        if (layout.doneButton.intersects(layout.resetButton)) {
            failures.add(size + "footer buttons overlap");
        }
        if (layout.doneButton.width() < 40 || layout.doneButton.height() < 12) {
            failures.add(size + "done button too small: " + layout.doneButton);
        }
        if (layout.showsPreview()) {
            inside(size + "preview", layout.preview, panel, failures);
            if (layout.preview.intersects(layout.options)) {
                failures.add(size + "preview overlaps the options");
            }
            if (layout.options.width() < 230) {
                failures.add(size + "options too narrow beside the preview: " + layout.options.width());
            }
        }
        // At least one full option row (about 50 px with a three-line description) must fit.
        if (height >= 180 && layout.options.height() < 50) {
            failures.add(size + "options area too short: " + layout.options.height());
        }
        if (layout.rowWidth() < 150) {
            failures.add(size + "rows too narrow: " + layout.rowWidth());
        }

        inside(size + "tabs", layout.tabs, panel, failures);
        if (layout.header.bottom() > layout.tabs.y() || layout.tabs.bottom() > layout.options.y()) {
            failures.add(size + "tabs overlap the header or the options");
        }
        // The player's seven tabs, and the enemy anchors' three.
        for (int count : new int[] {AnchorsLayout.TAB_COUNT, AnchorsLayout.ENEMY_TAB_COUNT}) {
            for (int index = 0; index < count; index++) {
                AnchorsLayout.Rect tab = layout.tab(index, count);
                String name = size + "tab " + index + "/" + count;
                inside(name, tab, layout.tabs, failures);
                if (tab.width() < AnchorsLayout.MIN_TAB_WIDTH || tab.height() < 12) {
                    failures.add(name + " too small for its icon: " + tab);
                }
                if (index > 0 && tab.intersects(layout.tab(index - 1, count))) {
                    failures.add(name + " overlaps the one before");
                }
            }
        }

        // The anchor workshop shares the body between its two rails and the stage.
        AnchorsLayout.Rect body = layout.body();
        inside(size + "body", body, panel, failures);
        AnchorsLayout.Workshop workshop = AnchorsLayout.workshop(body);
        inside(size + "workshop left", workshop.left(), body, failures);
        inside(size + "workshop stage", workshop.stage(), body, failures);
        inside(size + "workshop right", workshop.right(), body, failures);
        if (workshop.left().intersects(workshop.stage()) || workshop.stage().intersects(workshop.right())
                || workshop.left().intersects(workshop.right())) {
            failures.add(size + "workshop regions overlap");
        }
        if (workshop.stage().width() < 56) {
            failures.add(size + "workshop stage too narrow for the anchor: " + workshop.stage());
        }
        if (workshop.right().width() < 104) {
            failures.add(size + "workshop colour rail too narrow for the picker: " + workshop.right());
        }

        AnchorsLayout.Modal modal = AnchorsLayout.modal(width, height);
        inside(size + "modal", modal.box(), screen, failures);
        inside(size + "modal cancel", modal.cancel(), modal.box(), failures);
        inside(size + "modal confirm", modal.confirm(), modal.box(), failures);
        if (modal.cancel().intersects(modal.confirm())) {
            failures.add(size + "modal buttons overlap");
        }
        if (modal.confirm().width() < 60 || modal.cancel().width() < 40 || modal.confirm().height() < 12) {
            failures.add(size + "modal buttons too small: " + modal.cancel() + " " + modal.confirm());
        }
        // A left button widened for a long translation stays inside, apart and never the narrower one.
        AnchorsLayout.Modal wide = AnchorsLayout.modal(width, height, 400);
        inside(size + "wide modal cancel", wide.cancel(), wide.box(), failures);
        inside(size + "wide modal confirm", wide.confirm(), wide.box(), failures);
        if (wide.cancel().intersects(wide.confirm()) || wide.cancel().width() < modal.cancel().width()
                || wide.confirm().width() < 40) {
            failures.add(size + "widened modal buttons: " + wide.cancel() + " " + wide.confirm());
        }
        // The warning needs room for its title and at least three lines above the buttons.
        if (modal.cancel().y() - modal.box().y() < 70) {
            failures.add(size + "modal too short for the warning: " + modal.box());
        }

        // The explaining windows (Herzium): title, content and buttons apart and inside the box.
        AnchorsLayout.InfoModal info = AnchorsLayout.infoModal(width, height);
        inside(size + "info", info.box(), screen, failures);
        inside(size + "info title", info.title(), info.box(), failures);
        inside(size + "info content", info.content(), info.box(), failures);
        inside(size + "info cancel", info.cancel(), info.box(), failures);
        inside(size + "info confirm", info.confirm(), info.box(), failures);
        if (info.cancel().intersects(info.confirm()) || info.content().intersects(info.cancel())
                || info.content().intersects(info.confirm()) || info.content().intersects(info.title())) {
            failures.add(size + "info window regions overlap");
        }
        if (info.confirm().width() < 60 || info.cancel().width() < 40 || info.confirm().height() < 12) {
            failures.add(size + "info buttons too small: " + info.cancel() + " " + info.confirm());
        }
        // Room for a link line and a few lines of text on every screen.
        if (info.content().height() < 60 || info.content().width() < 200) {
            failures.add(size + "info content too small: " + info.content());
        }

        // The glowstone guard warning, with its text wrapped to one to five lines.
        for (int lines = 1; lines <= 5; lines++) {
            checkGuard(size + "guard (" + lines + " lines) ", AnchorsLayout.guardModal(width, height, lines, 854.0F / 480.0F),
                    screen, lines, failures);
        }
    }

    private static void checkGuard(String name, AnchorsLayout.GuardModal guard, AnchorsLayout.Rect screen, int lines,
            List<String> failures) {
        AnchorsLayout.Rect box = guard.box();
        inside(name + "box", box, screen, failures);
        inside(name + "title", guard.title(), box, failures);
        inside(name + "cancel", guard.cancel(), box, failures);
        inside(name + "confirm", guard.confirm(), box, failures);
        inside(name + "text", guard.body(), box, failures);
        if (guard.cancel().intersects(guard.confirm())) {
            failures.add(name + "buttons overlap");
        }
        if (guard.confirm().width() < 60 || guard.cancel().width() < 40 || guard.confirm().height() < 12) {
            failures.add(name + "buttons too small: " + guard.cancel() + " " + guard.confirm());
        }
        if (guard.body().bottom() > guard.cancel().y() || guard.body().y() < guard.title().bottom()) {
            failures.add(name + "text overlaps the title or the buttons: " + guard.body());
        }
        // Up to three lines of text always fit whole; longer text may be cut on the smallest screens.
        if (lines <= 3 && guard.body().height() < lines * 10) {
            failures.add(name + "text cut: " + guard.body());
        }
        AnchorsLayout.Rect clip = guard.clip();
        if (clip.width() > 0) {
            inside(name + "clip", clip, box, failures);
            if (clip.height() < AnchorsLayout.MIN_CLIP_HEIGHT) {
                failures.add(name + "clip too small: " + clip);
            }
            if (clip.intersects(guard.title()) || clip.intersects(guard.body()) || clip.intersects(guard.cancel())
                    || clip.intersects(guard.confirm())) {
                failures.add(name + "clip overlaps the text or the buttons: " + clip);
            }
            float aspect = clip.width() / (float) clip.height();
            if (Math.abs(aspect - 854.0F / 480.0F) > 0.06F) {
                failures.add(name + "clip stretched: " + clip);
            }
        } else if (box.height() >= 250 && lines <= 3) {
            // A screen this tall always has room for the clip.
            failures.add(name + "clip dropped on a tall screen: " + box);
        }
    }

    private static void inside(String name, AnchorsLayout.Rect rect, AnchorsLayout.Rect outer, List<String> failures) {
        if (rect.width() < 0 || rect.height() < 0) {
            failures.add(name + " has a negative size: " + rect);
            return;
        }
        if (rect.x() < outer.x() || rect.y() < outer.y() || rect.right() > outer.right()
                || rect.bottom() > outer.bottom()) {
            failures.add(name + " " + rect + " leaves " + outer);
        }
    }
}
