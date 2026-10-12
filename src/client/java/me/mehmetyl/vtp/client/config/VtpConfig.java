package me.mehmetyl.vtp.client.config;

import me.mehmetyl.vtp.client.filter.FilterRule;

import java.util.ArrayList;
import java.util.List;

/**
 * The persisted client config.
 *
 * <p>Grouped into sections so the JSON stays readable and so the filter screen
 * maps one tab per section. Every field has an initialiser and the class has an
 * explicit no-arg constructor: Gson instantiates through it and only overwrites
 * keys present in the file, which is what makes loading forward-compatible.
 */
public final class VtpConfig {
    /**
     * Schema history:
     * <ul>
     *   <li>1 - initial</li>
     *   <li>2 - colours stored as {@code #AARRGGBB} strings instead of signed
     *       decimal ints, which were unreadable when hand-editing</li>
     *   <li>5 - dropped {@code flatIcons}; label icons are always drawn the
     *       see-through way, so they cannot hide behind terrain</li>
     *   <li>4 - dropped {@code includeWanderingTraders}; wandering traders are
     *       always tracked and are chosen in the profession picker instead</li>
     *   <li>3 - dropped {@code showUnknownVillagers} (always on) and
     *       {@code debugOverlay}; a rule's {@code minStock} number became the
     *       {@code onlyInStock} toggle</li>
     * </ul>
     */
    public static final int CURRENT_SCHEMA = 5;

    public int schemaVersion = CURRENT_SCHEMA;

    /** Master on/off, toggled by the keybind and persisted across sessions. */
    public boolean previewEnabled = true;

    public General general = new General();
    public Appearance appearance = new Appearance();
    public Alerts alerts = new Alerts();
    public List<FilterRule> rules = new ArrayList<>();

    public VtpConfig() {
    }

    public static final class General {
        /** Blocks. Culled by squared distance in the hot path. */
        public int scanRadius = 48;

        /**
         * Ticks the scanner idles between cycles.
         *
         * <p>Not the period. A cycle is split across four buckets on
         * consecutive ticks, so the time between one scan and the next is this
         * plus three: at 8 that is 11 ticks, a little over half a second.
         */
        public int scanIntervalTicks = 8;

        /** Hard cap on simultaneously labelled villagers. */
        public int maxHighlighted = 32;

        public General() {
        }
    }

    public static final class Appearance {
        public boolean glowEnabled = true;
        public boolean labelEnabled = true;
        public boolean labelBackground = true;
        public float labelScale = 1.0f;

        /** Labels beyond this distance are dropped; never exceeds scanRadius. */
        public double maxRenderDistance = 48.0;


        /** Matched and already traded -- profession is locked in. */
        public String colorLocked = "#FF55FF55";

        /** Matched and never traded -- profession can still be re-rolled. */
        public String colorRerollable = "#FFFFFF55";

        /**
         * Matched on profession but offers are unknown, or the lock state cannot
         * be determined. Not optional: on a vanilla server every level-1 villager
         * lands here because villager xp is not synced.
         */
        public String colorUnknown = "#FFAAAAAA";

        // Parsed once in normalize() so the scan never parses a string.
        private transient int lockedArgb = 0xFF55FF55;
        private transient int rerollableArgb = 0xFFFFFF55;
        private transient int unknownArgb = 0xFFAAAAAA;

        public Appearance() {
        }

        public int lockedArgb() {
            return lockedArgb;
        }

        public int rerollableArgb() {
            return rerollableArgb;
        }

        public int unknownArgb() {
            return unknownArgb;
        }

        void parseColours() {
            lockedArgb = parseColor(colorLocked, 0xFF55FF55);
            rerollableArgb = parseColor(colorRerollable, 0xFFFFFF55);
            unknownArgb = parseColor(colorUnknown, 0xFFAAAAAA);

            // Write back in canonical form so a legacy or sloppy value is tidied
            // up the next time the file is saved.
            colorLocked = formatColor(lockedArgb);
            colorRerollable = formatColor(rerollableArgb);
            colorUnknown = formatColor(unknownArgb);
        }
    }

    public static final class Alerts {
        // Both off by default: an alert the player did not ask for is an
        // interruption, and the glow already says a match is nearby.
        public boolean chatOnNewMatch = false;
        public boolean soundOnNewMatch = false;

        /** Per-villager cooldown so walking through a village cannot spam. */
        public int perVillagerCooldownTicks = 600;

        public Alerts() {
        }
    }

    /**
     * Accepts {@code #AARRGGBB}, {@code #RRGGBB}, bare hex, and the signed
     * decimal ints written by schema 1, so an older config upgrades silently.
     */
    public static int parseColor(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String text = value.trim();
        try {
            if (text.startsWith("#") || text.startsWith("0x") || text.startsWith("0X")) {
                String hex = text.startsWith("#") ? text.substring(1) : text.substring(2);

                // CSS-style shorthand: each digit stands for a doubled pair, so
                // #FFF is white. Expanding beats the old behaviour, which read it
                // as a 3-digit number and turned #FFF into a dark blue.
                if (hex.length() == 3 || hex.length() == 4) {
                    StringBuilder expanded = new StringBuilder(hex.length() * 2);
                    for (int i = 0; i < hex.length(); i++) {
                        expanded.append(hex.charAt(i)).append(hex.charAt(i));
                    }
                    hex = expanded.toString();
                }

                if (hex.length() != 6 && hex.length() != 8) {
                    return fallback;
                }

                long parsed = Long.parseLong(hex, 16);
                // A 6-digit value has no alpha; assume fully opaque.
                return hex.length() == 6 ? (int) (parsed | 0xFF000000L) : (int) parsed;
            }
            // Legacy schema 1 wrote colours as signed decimals.
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static String formatColor(int argb) {
        return String.format("#%08X", argb);
    }

    /**
     * Clamps everything into a sane range after loading. A hand-edited or
     * partially-corrupt config must degrade to something usable rather than
     * producing a scan radius of 100000 or a zero tick interval.
     */
    public void normalize() {
        if (general == null) {
            general = new General();
        }
        if (appearance == null) {
            appearance = new Appearance();
        }
        if (alerts == null) {
            alerts = new Alerts();
        }
        if (rules == null) {
            rules = new ArrayList<>();
        }

        general.scanRadius = clamp(general.scanRadius, 8, 128);
        general.scanIntervalTicks = clamp(general.scanIntervalTicks, 1, 100);
        general.maxHighlighted = clamp(general.maxHighlighted, 1, 256);

        appearance.labelScale = clamp(appearance.labelScale, 0.25f, 4.0f);
        // Rendering further than we scan would show stale highlights.
        appearance.maxRenderDistance = clamp(appearance.maxRenderDistance, 4.0, general.scanRadius);
        appearance.parseColours();

        // The alert path already fires only for villagers that were not matching
        // a tick ago, so this no longer has to carry the anti-spam job on its own.
        // What it still damps is flicker: a villager at the radius edge, or one
        // whose occlusion test flips as the player moves, leaves and re-enters the
        // match set repeatedly and would ding on each re-entry.
        alerts.perVillagerCooldownTicks = clamp(alerts.perVillagerCooldownTicks, 5, 24000);

        rules.removeIf(java.util.Objects::isNull);
        for (FilterRule rule : rules) {
            rule.normalize();
        }

        schemaVersion = CURRENT_SCHEMA;
    }

    /** Restores defaults in place, keeping the same instance the screens hold. */
    public void resetToDefaults() {
        previewEnabled = true;
        general = new General();
        appearance = new Appearance();
        alerts = new Alerts();
        rules.clear();
        normalize();
    }

    private static int clamp(int value, int min, int max) {
        return value < min ? min : Math.min(value, max);
    }

    private static float clamp(float value, float min, float max) {
        return value < min ? min : Math.min(value, max);
    }

    private static double clamp(double value, double min, double max) {
        return value < min ? min : Math.min(value, max);
    }
}
