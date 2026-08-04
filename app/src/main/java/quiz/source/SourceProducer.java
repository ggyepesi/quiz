package quiz.source;

import objectview.Viewable;

/**
 * Pulls data for an instance from its resolved {@link Source} and writes the
 * result onto the instance — the "pull" half of the datasource construct.
 *
 * <p>Bound to a source kind {@code S}: a {@code SourceProducer<WikidataSource>}
 * reads the resolved qid and fetches a field (e.g. population, a portrait). It is
 * fed the {@code Source} a {@link SourceFactory} produced and resolution anchored,
 * so identify and pull share one handle. A producer typically carries its own
 * field spec ("property → field"); the population producer knows it writes
 * {@code population} from {@code P1082}.</p>
 *
 * @param <S> the concrete {@link Source} kind this producer consumes
 */
public interface SourceProducer<S extends Source> {

    /** The source kind this producer consumes — lets a pipeline dispatch by type. */
    Class<S> sourceType();

    /** Pull from {@code source} and write the produced value(s) onto {@code instance}. */
    void produce(Viewable instance, S source) throws Exception;
}
