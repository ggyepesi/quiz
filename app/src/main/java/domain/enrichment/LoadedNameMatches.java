package domain.enrichment;

import objectview.Viewable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Finds the loaded instances a text names, as review candidates only.
 *
 * <p>Matching only: a name is a whole-word, case-insensitive occurrence of one of an
 * instance's names. It nominates an instance only when exactly one loaded instance carries
 * that name; a name several instances share (Odin's brother Vili and Loki's son Vili) is
 * reported as ambiguous and never guessed (directive 9). Which names an instance has, which
 * text is read and what a hit becomes are the caller's: an introduction, a story passage or
 * any later text source use this one matcher.</p>
 *
 * <p>Names are indexed by their first word, so a text is read once however many instances
 * are loaded; the index changes lookup, never matching.</p>
 */
public final class LoadedNameMatches {

    /** One occurrence of a name that identifies exactly one loaded instance. */
    public record Hit(Viewable candidate, String phrase, int offset) { }

    /** A name that several loaded instances share, at its first occurrence. */
    public record Ambiguity(String phrase, int offset, List<Viewable> candidates) {
        public Ambiguity { candidates = List.copyOf(candidates); }
    }

    /** Each named instance once, at its first occurrence, and each ambiguous name once. */
    public record Result(List<Hit> hits, List<Ambiguity> ambiguous) {
        public Result {
            hits = List.copyOf(hits);
            ambiguous = List.copyOf(ambiguous);
        }
    }

    /** A text range that is already answered elsewhere, such as an article link. */
    public record Skip(int start, int end) { }

    private record Name(Viewable candidate, String phrase) { }

    private record Identity(String type, String identifier) {
        static Identity of(Viewable value) {
            return new Identity(value.identityTypeName(), value.getIdentifier());
        }
    }

    private final Map<String, List<Name>> byFirstWord;

    private LoadedNameMatches(Map<String, List<Name>> byFirstWord) {
        this.byFirstWord = byFirstWord;
    }

    /** Indexes every candidate under each of its names, keyed by complete typed identity. */
    public static LoadedNameMatches of(Collection<? extends Viewable> candidates,
            Function<Viewable, List<String>> names) {
        Map<Identity, Viewable> unique = new LinkedHashMap<>();
        if (candidates != null) {
            for (Viewable candidate : candidates) {
                if (candidate == null || candidate.getIdentifier() == null
                        || candidate.getIdentifier().isBlank()) continue;
                unique.putIfAbsent(Identity.of(candidate), candidate);
            }
        }
        Map<String, List<Name>> index = new LinkedHashMap<>();
        for (Viewable candidate : unique.values()) {
            Set<String> phrases = new LinkedHashSet<>();
            for (String name : names.apply(candidate)) {
                String phrase = name == null ? "" : name.trim();
                if (!phrase.isEmpty() && phrases.add(phrase.toLowerCase(Locale.ROOT))) {
                    String first = firstWord(phrase);
                    if (first.isEmpty()) continue;
                    index.computeIfAbsent(first.toLowerCase(Locale.ROOT), key -> new ArrayList<>())
                            .add(new Name(candidate, phrase));
                }
            }
        }
        Map<String, List<Name>> immutable = new LinkedHashMap<>();
        index.forEach((key, value) -> immutable.put(key, List.copyOf(value)));
        return new LoadedNameMatches(Map.copyOf(immutable));
    }

    /** The instance's label and its Wikidata aliases. */
    public static List<String> labelAndAliases(Viewable candidate) {
        List<String> names = new ArrayList<>();
        names.add(candidate.getDisplayName());
        if (candidate instanceof domain.runtime.WikidataDynamicObject wikidata) {
            names.addAll(wikidata.aliases());
        }
        return names;
    }

    public boolean isEmpty() { return byFirstWord.isEmpty(); }

    /** Whether one of the indexed names is exactly this phrase. */
    public boolean names(String phrase) {
        String clean = phrase == null ? "" : phrase.trim();
        return byFirstWord.getOrDefault(firstWord(clean).toLowerCase(Locale.ROOT), List.of())
                .stream().anyMatch(name -> name.phrase().equalsIgnoreCase(clean));
    }

    public Result find(String text) {
        return find(text, List.of());
    }

    public Result find(String text, List<Skip> skipped) {
        Map<Identity, Hit> unique = new LinkedHashMap<>();
        var ambiguous = scan(text, skipped,
                hit -> unique.putIfAbsent(Identity.of(hit.candidate()), hit));
        return new Result(List.copyOf(unique.values()), ambiguous);
    }

    /** The same matches as discovery, but every occurrence is needed to render links. */
    public List<Hit> occurrences(String text) {
        List<Hit> hits = new ArrayList<>();
        scan(text, List.of(), hits::add);
        return List.copyOf(hits);
    }

    private List<Ambiguity> scan(String text, List<Skip> skipped, Consumer<Hit> hits) {
        if (text == null || text.isBlank() || byFirstWord.isEmpty()) return List.of();
        Map<String, Ambiguity> ambiguous = new LinkedHashMap<>();
        for (int start = 0; start < text.length();) {
            if (!Character.isLetterOrDigit(text.charAt(start))
                    || start > 0 && Character.isLetterOrDigit(text.charAt(start - 1))) {
                start++;
                continue;
            }
            int end = start + 1;
            while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) end++;
            if (inside(start, skipped)) {
                start = end;
                continue;
            }
            List<Name> names = byFirstWord.getOrDefault(
                    text.substring(start, end).toLowerCase(Locale.ROOT), List.of());
            if (names.isEmpty()) {
                start = end;
                continue;
            }
            Set<String> examined = new LinkedHashSet<>();
            for (Name name : names) {
                String phrase = name.phrase();
                if (!occursAt(text, start, phrase) || !examined.add(phrase.toLowerCase(Locale.ROOT))) continue;
                List<Viewable> carriers = carriers(names, phrase);
                if (carriers.size() == 1) {
                    hits.accept(new Hit(carriers.getFirst(), phrase, start));
                } else {
                    ambiguous.putIfAbsent(phrase.toLowerCase(Locale.ROOT),
                            new Ambiguity(phrase, start, carriers));
                }
            }
            start = end;
        }
        return List.copyOf(ambiguous.values());
    }

    private static List<Viewable> carriers(List<Name> names, String phrase) {
        Map<Identity, Viewable> carriers = new LinkedHashMap<>();
        for (Name name : names) {
            if (name.phrase().equalsIgnoreCase(phrase)) {
                carriers.putIfAbsent(Identity.of(name.candidate()), name.candidate());
            }
        }
        return List.copyOf(carriers.values());
    }

    private static boolean occursAt(String text, int start, String phrase) {
        int end = start + phrase.length();
        return end <= text.length()
                && text.regionMatches(true, start, phrase, 0, phrase.length())
                && (end == text.length() || !Character.isLetterOrDigit(text.charAt(end)));
    }

    private static boolean inside(int offset, List<Skip> skipped) {
        if (skipped == null) return false;
        for (Skip skip : skipped) {
            if (offset >= skip.start() && offset < skip.end()) return true;
        }
        return false;
    }

    private static String firstWord(String phrase) {
        if (phrase == null || phrase.isEmpty() || !Character.isLetterOrDigit(phrase.charAt(0))) {
            return "";
        }
        int end = 1;
        while (end < phrase.length() && Character.isLetterOrDigit(phrase.charAt(end))) end++;
        return phrase.substring(0, end);
    }
}
