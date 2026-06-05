package wikidata.explore.qid;

import wikidata.WikidataSparqlClient;

import javax.swing.*;

public class TopQidFinderMain {

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WikidataSparqlClient client =
                    new WikidataSparqlClient(
                            "QuizProject/1.0 (ggyepesi@gmail.com)",
                            1);

            new TopQidFinderFrame(client).setVisible(true);
        });
    }
}
