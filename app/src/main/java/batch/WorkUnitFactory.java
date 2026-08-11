package batch;

/** Rebuilds executable work from a durable checkpoint descriptor. */
@FunctionalInterface
public interface WorkUnitFactory<R> {
    WorkUnit<R> restore(WorkDescriptor descriptor) throws Exception;
}
