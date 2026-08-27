package wikidata;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

/** One definition of Wikimedia page types which are not domain entities. */
public final class WikimediaInternalTypes {
    private static final Set<String> QIDS = Set.of(
            "Q4167410",    // Wikimedia disambiguation page
            "Q22808320",   // Wikimedia human name disambiguation page
            "Q17362920",   // Wikimedia duplicated page
            "Q4167836",    // Wikimedia category
            "Q13406463");  // Wikimedia list article

    private WikimediaInternalTypes() { }

    /** A typed item is internal only when every known P31 says so. */
    public static boolean exclusivelyInternal(Collection<String> typeQids) {
        if (typeQids == null) return false;
        Set<String> known = new LinkedHashSet<>();
        typeQids.stream().filter(WikidataIds::isQid).forEach(known::add);
        return !known.isEmpty() && known.stream().allMatch(QIDS::contains);
    }

    /** SPARQL filter retaining untyped, ordinary, and mixed-purpose entities. */
    public static String excludeExclusivelyInternal(String entityVariable) {
        String entity = entityVariable == null ? "" : entityVariable.trim();
        if (!entity.startsWith("?")) {
            throw new IllegalArgumentException("SPARQL entity variable is required");
        }
        String values = QIDS.stream().sorted().map(qid -> "wd:" + qid)
                .collect(java.util.stream.Collectors.joining(", "));
        return "  FILTER NOT EXISTS {\n"
                + "    " + entity + " wdt:P31 ?internalType .\n"
                + "    FILTER(?internalType IN (" + values + "))\n"
                + "    FILTER NOT EXISTS {\n"
                + "      " + entity + " wdt:P31 ?ordinaryType .\n"
                + "      FILTER(?ordinaryType NOT IN (" + values + "))\n"
                + "    }\n"
                + "  }\n";
    }
}
