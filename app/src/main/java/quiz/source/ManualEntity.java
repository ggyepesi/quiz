package quiz.source;

import objectview.ViewableAdapter;

/**
 * Base for hand-authored domain entities (a {@code State}, a {@code Laureate}, …).
 *
 * <p>A manual entity is authored, not produced from a source, so it carries no
 * source field at all. If a curation later resolves it to a Wikidata entity or
 * fills a field from a source, that is recorded in the curation history — never
 * as state on the instance.</p>
 */
public abstract class ManualEntity extends ViewableAdapter {
}
