package wikidata.explore.generation;

/** Executable stage metadata shared by orchestration and pipeline presentation. */
public interface GenerationStage {
    String id();
    String title();
    void execute() throws Exception;
}
