package wikidata.stellar;

import java.util.List;

public final class StellarRules {
    private StellarRules() {}

    public static final StellarRule STARS =
            new StellarRule(
                    "stars",
                    "item",
                    List.of("Q523"),
                    List.of(
                            "$item wdt:P59 $root .",
                            "$root wdt:P398 $item ."
                           ),
                    false
            );

    public static final StellarRule PLANETS =
            new StellarRule(
                    "planets",
                    "item",
                    List.of("Q634"),
                    List.of(
                            "$item wdt:P397 $root .",
                            "$root wdt:P398 $item ."
                           ),
                    false
            );

    public static final StellarRule MOONS =
            new StellarRule(
                    "moons",
                    "item",
                    List.of("Q2537"),
                    List.of(
                            "$item wdt:P397 $root .",
                            "$root wdt:P398 $item ."
                           ),
                    true
            );

    public static final StellarRule CONSTELLATION_OBJECTS =
            new StellarRule(
                    "constellationObjects",
                    "item",
                    List.of(
                            "Q523",
                            "Q318",
                            "Q204194",
                            "Q634"
                           ),
                    List.of("$item wdt:P59 $root ."),
                    false
            );

    public static List<StellarRule> defaultRules() {
        return List.of(
                // STARS,
                //PLANETS,
                // MOONS,
                CONSTELLATION_OBJECTS);
    }
}