package process.swing.workflow;

/** Small explicit state machine shared by every curation action host. */
public final class ProcessWorkflowState {
    public enum Stage { PLAN, RUNNING, RESULTS, APPLYING, COMPLETE }
    private Stage stage = Stage.PLAN;
    public Stage stage() { return stage; }
    public void execute() { move(Stage.PLAN, Stage.RUNNING); }
    /** A prior process produced the state being reviewed; there is no new run stage. */
    public void review() { move(Stage.PLAN, Stage.RESULTS); }
    public void results() { move(Stage.RUNNING, Stage.RESULTS); }
    public void apply() { move(Stage.RESULTS, Stage.APPLYING); }
    public void applied() { move(Stage.APPLYING, Stage.COMPLETE); }
    public void retryApply() { move(Stage.APPLYING, Stage.RESULTS); }
    private void move(Stage expected, Stage next) {
        if (stage != expected) throw new IllegalStateException(
                "Cannot move from " + stage + " to " + next);
        stage = next;
    }
}
