package wikidata.explore.tree;

import wikidata.WikidataSparqlClient;

import javax.swing.*;
import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * First extraction action/service layer.
 *
 * Intended to move extraction/count/save actions out of WikidataRuleTreeFrame.
 */
public class RuleTreeExtractionActions {

    private final RuleTreeExtractor extractor;
    private final WikidataDynamicObjectJsonStore store =
            new WikidataDynamicObjectJsonStore();

    public RuleTreeExtractionActions(WikidataSparqlClient client) {
        this.extractor = new RuleTreeExtractor(client);
    }

    public RuleTreeExtractor extractor() {
        return extractor;
    }

    public void saveObjects(List<WikidataDynamicObject> objects, File file)
            throws Exception {
        store.save(objects, file);
    }

    public GeneratedKnowledgeSet loadGeneratedSet(String name, File file)
            throws Exception {
        return new GeneratedKnowledgeSet(name, file);
    }
}
