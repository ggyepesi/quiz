package work;


import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a {@link Query} needs in order to run and be observed: somewhere to log a step,
 * something to cancel it, and whatever source access the query itself requires.
 *
 * <p>The first two are the same for every query ever written here — a Wikidata SPARQL
 * query, a Wikipedia article read, a DBpedia lookup, a process step that touches no
 * network at all. The third is not, so it is carried as a CAPABILITY rather than as
 * fields: this class used to hold a Wikidata SPARQL client map and a Wikidata API client
 * outright, which meant every component that wanted an observable unit of work imported
 * Wikidata to get one — the generic process runner, the provider-neutral enrichment
 * layer, and the Wikipedia client among them. A query now asks for exactly the access it
 * uses ({@code WikidataAccess.sparql(context, …)}), and a context that has none is a
 * useful thing rather than a broken one.
 */
public class QueryContext {

    private final Map<Class<?>, Object> capabilities;
    private final WorkflowRecorder recorder;
    private final LogNode processParent;
    private final CancellationToken cancellation;

    /** A context that can log and be cancelled, and reaches no source. */
    public QueryContext() {
        this(Map.of(), null, null, new CancellationToken());
    }

    private QueryContext(
            Map<Class<?>, Object> capabilities,
            WorkflowRecorder recorder,
            LogNode processParent,
            CancellationToken cancellation) {

        this.capabilities = capabilities;
        this.recorder = recorder;
        this.processParent = processParent;
        this.cancellation = cancellation == null ? new CancellationToken() : cancellation;
    }

    /**
     * A context that also offers {@code capability}. The key is the capability's own type,
     * so a query names what it needs and gets a clear failure when the runner did not
     * provide it — rather than a default endpoint chosen for it somewhere else.
     */
    public <T> QueryContext with(Class<T> type, T capability) {
        Objects.requireNonNull(type, "Capability type is required");
        Map<Class<?>, Object> next = new LinkedHashMap<>(capabilities);
        if (capability == null) {
            next.remove(type);
        } else {
            next.put(type, capability);
        }
        return new QueryContext(Map.copyOf(next), recorder, processParent, cancellation);
    }

    /** The capability, or a failure naming what was missing. */
    public <T> T require(Class<T> type) {
        T capability = optional(type);
        if (capability == null) {
            throw new IllegalStateException("This context offers no "
                    + (type == null ? "capability" : type.getSimpleName())
                    + "; the runner that created it did not bind one.");
        }
        return capability;
    }

    /** The capability, or null — for a query that can do something useful without it. */
    public <T> T optional(Class<T> type) {
        return type == null ? null : type.cast(capabilities.get(type));
    }

    public QueryContext withRecorder(WorkflowRecorder recorder) {
        return new QueryContext(capabilities, recorder, null, cancellation);
    }

    /** Binds adapted-query steps beneath their owning Process subprocess. */
    public QueryContext withRecorder(WorkflowRecorder recorder, LogNode processParent) {
        return new QueryContext(capabilities, recorder, processParent, cancellation);
    }

    public QueryContext withCancellation(CancellationToken token) {
        return new QueryContext(capabilities, recorder, processParent, token);
    }

    public CancellationToken cancellation() { return cancellation; }

    /**
     * Stops whatever this context's capabilities currently have in flight. The runner
     * asks; each capability knows what it owns. Asking for "active SPARQL queries" here
     * was the one place the generic runner had to know which source it was running over.
     */
    public void cancelActiveWork() {
        for (Object capability : capabilities.values()) {
            if (capability instanceof CancellableWork work) {
                work.cancelActiveWork();
            }
        }
    }

    /**
     * Runs {@code body} inside a log step nested under the current
     * workflow. The step is opened before the body runs and completed
     * after it returns (ok) or throws (failed / cancelled), so the body
     * can never leave the log tree unbalanced.
     */
    public <T> T step(
            String title,
            String queryType,
            String skeleton,
            Map<String, String> parameters,
            LogStepBody<T> body) throws Exception {

        if (recorder == null) {
            return body.run(LogStep.disabled());
        }

        return recorder.stepUnder(
                processParent, title, queryType, skeleton, parameters, body);
    }

    public void message(String text) {
        if (recorder != null) {
            recorder.messageUnder(processParent, text);
        }
    }
}
