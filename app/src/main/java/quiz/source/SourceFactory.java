package quiz.source;

import objectview.Viewable;

import java.util.List;

/**
 * Identifies an instance in an underlying datasource and <b>produces</b> the
 * {@link Source} handle(s) for it — the "identify" half of the datasource
 * construct.
 *
 * <p>Each implementation is one identification <em>strategy</em>: a manual-link
 * factory yields the exact {@code Source} from a curation link or a user pick; a
 * label-search factory yields candidate sources from a name lookup; an xref
 * factory yields a source from an external id the instance already carries.</p>
 *
 * <p>Its output is fed to resolution (auto when there is a single candidate,
 * otherwise a user pick); the chosen {@code Source} is then consumed by a
 * {@link SourceProducer} to pull data. The source itself is not stored on the
 * instance — a resolved identity lives in the curation history.</p>
 *
 * @param <S> the concrete {@link Source} kind this factory produces
 */
public interface SourceFactory<S extends Source> {

    /**
     * Candidate source handles for {@code instance}: empty = not identified,
     * one = resolved, several = ambiguous (needs a pick).
     */
    List<S> identify(Viewable instance) throws Exception;
}
