package dev.zymekoh.kohsanchors.input;

/**
 * The order in which the player pressed use and the hotbar keys since the last input pass.
 *
 * <p>Minecraft keeps only a counter per key, so by the time a tick handles input it knows that
 * "use" was pressed twice and "2" once, but not in which order. This journal keeps the order and
 * nothing else: it never creates, removes or repeats a press, and the counters stay the only
 * source of truth for whether a press is still pending.</p>
 */
final class InputJournal {
    /** The action code for the use key; hotbar slots are recorded as 0 to 8. */
    static final int USE = -1;
    /** No real burst is this long; anything longer is not worth reordering and is left to Vanilla. */
    static final int CAPACITY = 32;
    /**
     * A press this old was not handled by the next tick, so input handling was interrupted (a
     * screen, a hitch, a world change). The whole pass is then left to Vanilla.
     */
    private static final long MAX_AGE_NANOS = 250_000_000L;

    private final int[] actions = new int[CAPACITY];
    private final long[] times = new long[CAPACITY];
    private int size;
    private boolean unusable;

    void record(int action, long now) {
        if (this.size == CAPACITY) {
            this.unusable = true;
            return;
        }
        this.actions[this.size] = action;
        this.times[this.size] = now;
        this.size++;
    }

    /** Forgets the latest use press: it was run at once and is no longer pending. */
    void forgetLastUse() {
        for (int index = this.size - 1; index >= 0; index--) {
            if (this.actions[index] == USE) {
                System.arraycopy(this.actions, index + 1, this.actions, index, this.size - index - 1);
                System.arraycopy(this.times, index + 1, this.times, index, this.size - index - 1);
                this.size--;
                return;
            }
        }
    }

    /**
     * Puts back, in front and in order, presses of a burst that wait for the next tick. They are
     * still pending in Vanilla's counters; only their order is kept here.
     */
    void carry(int[] burst, int from, int count, long now) {
        int keep = Math.min(count, CAPACITY - this.size);
        System.arraycopy(this.actions, 0, this.actions, keep, this.size);
        System.arraycopy(this.times, 0, this.times, keep, this.size);
        for (int index = 0; index < keep; index++) {
            this.actions[index] = burst[from + index];
            this.times[index] = now;
        }
        this.size += keep;
        if (keep < count) {
            this.unusable = true;
        }
    }

    /**
     * Moves the pending actions into {@code out} and empties the journal.
     *
     * @return how many actions were copied, or {@code -1} when the journal cannot be trusted for
     *         this pass and Vanilla should handle every press on its own
     */
    int drainInto(int[] out, long now) {
        int count = this.size;
        boolean usable = !this.unusable;
        for (int index = 0; index < count && usable; index++) {
            if (now - this.times[index] > MAX_AGE_NANOS) {
                usable = false;
            }
            out[index] = this.actions[index];
        }
        this.size = 0;
        this.unusable = false;
        return usable ? count : -1;
    }
}
