package quiz.transform.ui;

import domain.DomainCapability;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;

/** The saved ModelBuilder project that owns an opened TransformApp domain. */
public interface ProjectBacking extends DomainCapability {
    String projectName();
    GeneratedProjectModel.ProjectKind projectKind();
    File modelFile();
    File snapshotFile();
}
