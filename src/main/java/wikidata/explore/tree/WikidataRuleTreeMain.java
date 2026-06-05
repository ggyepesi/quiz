package wikidata.explore.tree;

import wikidata.WikidataSparqlClient;

import javax.swing.*;

public class WikidataRuleTreeMain {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            WikidataSparqlClient client = new WikidataSparqlClient(
                    "QuizProject/1.0 (ggyepesi@gmail.com)",
                    1);
            new WikidataRuleTreeFrame(client).setVisible(true);
        });
    }
}
