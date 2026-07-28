package process;

/** A typed pause owned and named by the process; the UI only supplies its answer. */
public interface ProcessInputRequest<T> {
    String title();
    String prompt();
    Class<T> responseType();
}
