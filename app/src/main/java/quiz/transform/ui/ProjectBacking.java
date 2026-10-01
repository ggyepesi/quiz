package quiz.transform.ui;

import domain.DomainCapability;
import wikidata.explore.model.GeneratedProjectModel;

import java.io.File;
import java.util.List;

/**
 * The saved ModelBuilder project that owns an opened TransformApp domain.
 *
 * <p>It is read once, when the domain opens, and kept as the WORKING model: every read
 * in this session sees it, and every model edit made here (a promoted rule, an added
 * population) changes it. Nothing writes the model file except {@link
 * #writeProjectModel()}, which Save calls — so Save is the one persistence boundary, and
 * closing without saving leaves the file as it was.
 */
public interface ProjectBacking extends DomainCapability {
    String projectName();
    GeneratedProjectModel.ProjectKind projectKind();
    File modelFile();
    File snapshotFile();

    /** The working model: read once when the domain opened, edited in memory since. */
    GeneratedProjectModel projectModel();

    /** Model edits made in this session and not yet written, in the order made. */
    List<String> unsavedModelChanges();

    /**
     * Why the working model cannot be written now, or "" when it can. The file is
     * refused when it changed on disk since it was read — another application (usually
     * ModelBuilder) saved it, and writing would silently discard that save.
     */
    String modelWriteConflict();

    /** Writes the working model to {@link #modelFile()}. The only model writer. */
    void writeProjectModel() throws Exception;
}
