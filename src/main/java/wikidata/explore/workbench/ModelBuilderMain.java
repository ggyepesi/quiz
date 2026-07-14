package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

import javax.swing.*;

public class ModelBuilderMain {

    public static void main(String[] args) throws Exception {

        // A descriptive User-Agent with a contact (WDQS etiquette — a generic or
        // URL-shaped UA invites throttling), and a small concurrency budget so
        // qualifier-load / property-discovery batches actually overlap. The client
        // self-throttles (Retry-After + backoff), so a few in flight is safe.
        WikidataSparqlClient client =
                new WikidataSparqlClient(
                        "quiz-modelbuilder/1.0 (ggyepesi@gmail.com)",
                        4);

        SwingUtilities.invokeLater(() ->
                                           new ModelBuilderFrame(client).setVisible(true));
    }
}