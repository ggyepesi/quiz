package wikidata.stellar;

import wikidata.WikidataDownloadRule;
import wikidata.query.WikidataQueryBuilder;

import java.util.ArrayList;
import java.util.List;

public record StellarRule(
        String name,
        String itemVar,
        List<String> typeQids,
        List<String> wherePatterns,
        boolean useSubclassClosure
) implements WikidataDownloadRule {

    @Override
    public String queryFor(String rootQid) {
        String typePath = useSubclassClosure
                ? "wdt:P31/wdt:P279*"
                : "wdt:P31";

        WikidataQueryBuilder b = new WikidataQueryBuilder()
                .select(itemVar, itemVar + "Label")
                .label(itemVar);

        if (typeQids != null && !typeQids.isEmpty()) {
            b.values("type", typeQids.toArray(String[]::new));
            b.where("?" + itemVar + " " + typePath + " ?type .");
        }

        List<String> parts = new ArrayList<>();

        for (String pattern : wherePatterns) {
            parts.add(pattern
                              .replace("$root", "wd:" + rootQid)
                              .replace("$item", "?" + itemVar));
        }

        b.where("{ " + String.join(" } UNION { ", parts) + " }");

        return b.build();
    }
}