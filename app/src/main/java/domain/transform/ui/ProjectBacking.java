package domain.transform.ui;

import domain.DomainCapability;
import domain.config.GeneratedProjectModel;
import domain.config.PopulationSelection;
import domain.persistence.ProjectSaveGuard;

import java.io.File;
import java.util.List;

/**
 * The saved ModelBuilder project that owns an opened TransformApp domain.
 *
 * <p>It is read once, when the domain opens, and kept as the WORKING model: every read
 * in this session sees it, and every model edit made here (a promoted rule, an added
 * population) changes it. {@link #saveProject} is the persistence boundary, and
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

    /** Records an applied edit of the working model for Save and Close. */
    void recordModelChange(String change);

    /** Reflects the authored members in the open domain's instance scope. */
    void populationChanged(PopulationSelection population);

    /**
     * Why the working model cannot be written now, or "" when it can. The file is
     * refused when it changed on disk since it was read — another application (usually
     * ModelBuilder) saved it, and writing would silently discard that save.
     */
    String modelWriteConflict();

    /** Writes just the working model through the same project save guard. */
    void writeProjectModel() throws Exception;

    /** Save the working model, snapshot and registry under one project write guard. */
    <T> T saveProject(Runnable prepare, ProjectSaveGuard.Write<T> saveSnapshot) throws Exception;
}
