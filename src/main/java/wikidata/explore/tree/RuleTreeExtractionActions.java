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
    private final RuleTreeEstimator estimator;
    private final WikidataDynamicObjectJsonStore store =
            new WikidataDynamicObjectJsonStore();

    public RuleTreeExtractionActions(WikidataSparqlClient client) {
        this.extractor = new RuleTreeExtractor(client);
        this.estimator = new RuleTreeEstimator(client);
    }

    public RuleTreeExtractor extractor() {
        return extractor;
    }

    public RuleTreeEstimator estimator() {
        return estimator;
    }

    public void estimateRootCountAsync(
            RuleNode root,
            Consumer<String> log,
            Consumer<Long> done) {

        SwingWorker<Long, Void> worker = new SwingWorker<>() {
            @Override
            protected Long doInBackground() throws Exception {
                return estimator.countNodeResults(root);
            }

            @Override
            protected void done() {
                try {
                    long count = get();
                    if (log != null) log.accept("Estimated root count: " + count + "\n");
                    if (done != null) done.accept(count);
                } catch (Exception ex) {
                    if (log != null) log.accept("Count failed: " + ex.getMessage() + "\n");
                }
            }
        };

        worker.execute();
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
