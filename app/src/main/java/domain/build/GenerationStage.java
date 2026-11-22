package domain.build;

/** Executable stage metadata shared by orchestration and pipeline presentation. */
public interface GenerationStage {
    String id();
    String title();
    void execute() throws Exception;
}
