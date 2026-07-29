package flag;

import java.util.*;

public final class PrefixAndState {
    private static final Map<String, String> STATE_NAMES = new TreeMap<>();
    private static final List<String> PREFIXES = new ArrayList<>();

    private static final String[] FLAG_AND_COAT_OF_ARMS_PREFIXES = {
            "Armories ", "Proposed flag of ", "Flag of ", "Seal of ",
            "Royal coat of arms of ", "Coat of arms of ", "Badge of ",
            "State arms of ", "National Emblem of ", "Emblem of ",
            "Royal Arms of ", "Arms of ", "Bandera de ", "Bandera ",
            "Escudo de ", "War Ensign of ", "Naval sign of "
    };

    private static final String[] PREFIX_PREFIXES = {
            "Greater ", "Lesser ", "Great ", ""
    };

    private static final String[] PREFIX_SUFFIXES = {
            "the state of ", "the state ", "the ", ""
    };

    private static final String[] FLAG_VERSIONS = {
            "\\(reverse\\)", "\\(Reverse\\)", "\\(state\\)",
            "\\(unofficial\\)", "\\(Unofficial\\)"
    };

    static {
        STATE_NAMES.put("Minnesota_(2023_redesign)", "Minnesota");
        STATE_NAMES.put("_Minnesota_(2023_redesign)", "Minnesota");
        STATE_NAMES.put("Kansas Colored", "Kansas");
        STATE_NAMES.put("California Colored", "California");
        STATE_NAMES.put("State of Maine", "Maine");
        STATE_NAMES.put("Afghanistan (2013–2021)", "Afghanistan");
        STATE_NAMES.put("Australia (converted)", "Australia");
        STATE_NAMES.put("Dominica (variant 6)", "Dominica");
        STATE_NAMES.put("Togo (3-2)", "Togo");

        STATE_NAMES.put("Vatican City (2023–present)", "Vatican City");
        STATE_NAMES.put("Holy See (Vatican City State)", "Vatican City");
        STATE_NAMES.put("Transnistria (state)", "Transnistria");
        STATE_NAMES.put("Commonwealth of Puerto Rico", "Puerto Rico");
        STATE_NAMES.put("Mississippi 2014", "Mississippi");
        STATE_NAMES.put("New York State", "New York");
        STATE_NAMES.put("Ohio (B&W)", "Ohio");
        STATE_NAMES.put("Yemen (2)", "Yemen");
        STATE_NAMES.put("Côte d'Ivoire", "Ivory Coast");
        STATE_NAMES.put("Réunion (VAR)", "Réunion");

        STATE_NAMES.put("Juan Fernández", "Juan Fernández Islands");
        STATE_NAMES.put("Macau", "Macao");
        STATE_NAMES.put("Turkish Republic of Northern Cyprus", "Northern Cyprus");
        STATE_NAMES.put("Saba (island)", "Saba");
        STATE_NAMES.put("Republic of China", "China");
        STATE_NAMES.put("Denmark (2024)", "Denmark");
        STATE_NAMES.put("Sark (bordered)", "Sark");
        STATE_NAMES.put("Bolivia (Estado)", "Bolivia");
        STATE_NAMES.put("Canary Islands (simple)", "Canary Islands");

        STATE_NAMES.put("Syria", "Syrian Arab Republic");
        STATE_NAMES.put("Brunei", "Brunei Darussalam");
        STATE_NAMES.put("Iran", "Islamic Republic of Iran");

        List<String> suffixed = new ArrayList<>();
        for (String base : FLAG_AND_COAT_OF_ARMS_PREFIXES) {
            for (String suffix : PREFIX_SUFFIXES) {
                suffixed.add(base + suffix);
            }
        }

        for (String suffix : suffixed) {
            for (String prefix : PREFIX_PREFIXES) {
                PREFIXES.add(prefix + suffix);
            }
        }

        List<String> underscores = new ArrayList<>();
        for (String prefix : PREFIXES) {
            underscores.add(prefix.replace(" ", "_"));
        }
        PREFIXES.addAll(underscores);

        // Important: "Flag of the " before "Flag of ".
        PREFIXES.sort((a, b) -> Integer.compare(b.length(), a.length()));
    }

    private static final Map<String, String> SPECIAL_IMAGE_TO_STATE =
            Map.of(
                    "Flag of Georgia", "Georgia (country)",
                    "Seal of Georgia", "Georgia (U.S. state)",
                    "Flag of the state of Georgia", "Georgia (U.S. state)"
            );

    private final String prefix;
    private String state;
    private String fullState;
    private final String imageKey;
    private final String originalImageKey;
    private final String versionIdentityKey;

    public PrefixAndState(String prefix, String state, String fullState) {
        this.prefix = normalizePrefix(prefix);

        String rawFullState = normalizeRawName(fullState);

        this.state = canonicalStateName(state);
        this.fullState = canonicalStateName(fullState);

        this.imageKey = this.prefix + this.fullState;
        this.originalImageKey = this.prefix + rawFullState;
        // Normalize equivalent prefix spellings ("Flag of the" / "Flag of")
        // while retaining the raw version suffix. The old prefix-only identity
        // incorrectly collapsed every historical/variant image of one state.
        this.versionIdentityKey =
                getCanonicalPrefixForDuplicateCheck() + '\0' + rawFullState;

        String forcedState = SPECIAL_IMAGE_TO_STATE.get(this.originalImageKey);
        if (forcedState != null) {
            this.state = forcedState;
            this.fullState = forcedState;
        }
    }

    public static PrefixAndState findPrefix(String line) {
        if (line == null) {
            return null;
        }

        String normalized = cleanWikiBrackets(line).replace("_", " ").trim();
        String lower = normalized.toLowerCase(Locale.ROOT);

        for (String p : PREFIXES) {
            String prefix = p.replace("_", " ");
            String lowerPrefix = prefix.toLowerCase(Locale.ROOT);

            if (!lower.startsWith(lowerPrefix)) {
                continue;
            }

            String fullState = normalized.substring(prefix.length()).trim();
            return assemble(prefix, fullState);
        }

        return null;
    }

    public static PrefixAndState assemble(String prefix, String fullState) {
        String state = capitalizeFirst(canonicalStateName(fullState));
        return new PrefixAndState(prefix, state, fullState);
    }

    public static boolean isFlagPrefix(String prefix) {
        if (prefix == null) {
            return false;
        }

        return prefix.replace("_", " ").startsWith("Flag of ");
    }

    public static boolean startsWithNormalized(String text, String prefix) {
        if (text == null || prefix == null) {
            return false;
        }

        String t = text.replace("_", " ").toLowerCase(Locale.ROOT);
        String p = prefix.replace("_", " ").toLowerCase(Locale.ROOT);

        return t.startsWith(p);
    }

    public static String canonicalStateName(String s) {
        if (s == null) {
            return null;
        }

        String state = normalizeRawName(s);

        state = STATE_NAMES.getOrDefault(state, state);

        for (String v : FLAG_VERSIONS) {
            state = state.replaceAll(v, "");
        }

        state = state.strip();
        state = removeLeadingStateArticle(state);
        state = STATE_NAMES.getOrDefault(state, state);

        return state.strip();
    }

    public boolean isFlag() {
        return isFlagPrefix(prefix);
    }

    public String getCanonicalPrefixForDuplicateCheck() {
        return prefix.replace(" of the ", " of ").strip();
    }

    public String getPrefix() {
        return prefix;
    }

    public String getState() {
        return state;
    }

    public String getFullState() {
        return fullState;
    }

    public String getImageKey() {
        return imageKey;
    }

    public String getOriginalImageKey() {
        return originalImageKey;
    }

    public String getVersionIdentityKey() {
        return versionIdentityKey;
    }

    private static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return "";
        }

        // Do NOT trim. Trailing space matters.
        return prefix.replace("_", " ");
    }

    private static String normalizeRawName(String s) {
        return s == null ? "" : s.replace("_", " ").strip();
    }

    private static String cleanWikiBrackets(String s) {
        return s.replace("[[", "").replace("]]", "").trim();
    }

    private static String removeLeadingStateArticle(String s) {
        String lower = s.toLowerCase(Locale.ROOT);

        if (lower.startsWith("the state of ")) {
            return s.substring("the state of ".length()).strip();
        }

        if (lower.startsWith("the state ")) {
            return s.substring("the state ".length()).strip();
        }

        if (lower.startsWith("the ")) {
            return s.substring("the ".length()).strip();
        }

        return s;
    }

    private static String capitalizeFirst(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }

        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
