package wikidata.countries;

import java.util.List;

public final class CountryGeoRules {
    private CountryGeoRules() {}

    public static final CountryGeoRule NEIGHBOURS =
            new CountryGeoRule(
                    "neighbours",
                    "item",
                    List.of("Q3624078"),
                    List.of("$country wdt:P47 $item ."),
                    true
            );

    public static final CountryGeoRule RIVERS =
            new CountryGeoRule(
                    "rivers",
                    "item",
                    List.of("Q4022"),
                    List.of("$item wdt:P17 $country ."),
                    false
            );

    public static final CountryGeoRule LAKES =
            new CountryGeoRule(
                    "lakes",
                    "item",
                    List.of("Q23397"),
                    List.of(
                            "$item wdt:P17 $country .",
                            "$item wdt:P205 $country .",
                            "$item wdt:P206 $country ."
                           ),
                    true
            );

    public static final CountryGeoRule SEAS =
            new CountryGeoRule(
                    "seas",
                    "item",
                    List.of("Q165"),
                    List.of(
                            "$item wdt:P206 $country .",
                            "$country wdt:P206 $item ."
                           ),
                    false
            );

    public static final CountryGeoRule GULFS =
            new CountryGeoRule(
                    "gulfs",
                    "item",
                    List.of("Q1322134"),
                    List.of(
                            "$item wdt:P206 $country .",
                            "$country wdt:P206 $item ."
                           ),
                    false
            );

    public static final CountryGeoRule OCEANS =
            new CountryGeoRule(
                    "oceans",
                    "item",
                    List.of("Q9430"),
                    List.of(
                            "$item wdt:P206 $country .",
                            "$country wdt:P206 $item ."
                           ),
                    false
            );

    public static List<CountryGeoRule> defaultRules() {
        return List.of(
                NEIGHBOURS,
                RIVERS,
                LAKES,
                SEAS,
                GULFS,
                OCEANS);
    }
}