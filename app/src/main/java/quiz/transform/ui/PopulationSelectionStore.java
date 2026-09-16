package quiz.transform.ui;

import domain.DomainCapability;

import java.io.File;
import java.util.List;

/** Persists an explicitly chosen set of class-instance identities in its source model. */
public interface PopulationSelectionStore extends DomainCapability {
    File modelFile();
    void savePopulationSelection(String name, String className, List<String> qids)
            throws Exception;
}
