package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

/**
 * Prints {@link EntityStatementSummary} for one or more QIDs — the read-only
 * slice-1 verification of the example-first statement view.
 *
 * <pre>java ... EntityStatementSummaryMain Q137855674 Q103474</pre>
 */
public final class EntityStatementSummaryMain {

    public static void main(String[] args) {
        String[] qids = args.length > 0 ? args : new String[]{"Q137855674", "Q103474"};
        try (WikidataSparqlClient client =
                     new WikidataSparqlClient("quiz-statement-summary/1.0 (ggyepesi@gmail.com)")) {
            for (String qid : qids) {
                System.out.println("================ " + qid + " ================");
                try {
                    System.out.println(EntityStatementSummary.fetch(qid, client).concise());
                } catch (Exception e) {
                    System.out.println("  failed: " + e.getMessage());
                }
            }
            if (qids.length > 1) {
                System.out.println("================ MERGED COVERAGE ================");
                System.out.println(MergedStatementSummary.fetch(java.util.List.of(qids), client).concise());
            }
        }
    }
}
