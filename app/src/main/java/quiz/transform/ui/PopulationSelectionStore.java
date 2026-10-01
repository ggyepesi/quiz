package quiz.transform.ui;

import domain.DomainCapability;

import java.io.File;
import java.util.List;

/** Adds an explicitly chosen set of class-instance identities to the domain's working
 *  model. Save writes it to {@link #modelFile()}; nothing is written before that. */
public interface PopulationSelectionStore extends DomainCapability {
    File modelFile();
    void createPopulationSelection(String name, String className, List<String> qids);
}
