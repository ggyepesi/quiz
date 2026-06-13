package wikidata.explore.workbench;

import wikidata.WikidataSparqlClient;

import javax.swing.*;

public class ModelBuilderMain {

    public static void main(String[] args) throws Exception {

        WikidataSparqlClient client =
                new WikidataSparqlClient(
                        "https://query.wikidata.org/sparql",
                        1);

        SwingUtilities.invokeLater(() ->
                                           new ModelBuilderFrame(client).setVisible(true));
    }
}