package quiz.web.sources;

import objectview.Viewable;
import objectview.Viewable;
import quiz.ViewableGroup;
import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import quiz.transform.ui.DomainModel;
import quiz.web.ViewableSource;
import quiz.web.ViewableStore;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * A generic {@link ViewableSource} over a {@link DomainModel} — serves the domain's
 * instances of one type. The unified seam: the SAME abstraction that powers the
 * transform workbench serves the web, so any domain (a Wikidata snapshot, a
 * built-in Viewable domain, an in-memory transform result) is servable without a
 * bespoke source or a generated model. Loads lazily and shares one loaded domain
 * across its per-type sources.
 *
 * <p>Grouping is CONFIGURED, not hardcoded: pass the {@link Facet}s (from a saved
 * view / declared facets) and {@link #rootGroup()} groups the members by them —
 * the path to retiring the domain-specific wired groupings in the bespoke sources.
 */
public final class DomainModelSource implements ViewableSource {

    /** Loads the domain on first access (may be slow / throw). */
    public interface Loader {
        DomainModel get() throws Exception;
    }

    /** How the configured facets shape the tree: FLAT lays every facet out as a
     *  parallel dimension off the root; NESTED drills down facet by facet. */
    public enum GroupMode { FLAT, NESTED }

    private final String type;
    private final Loader loader;
    private final List<Facet<Viewable>> facets;
    private final GroupMode mode;
    private final String rootName;
    private DomainModel cached;

    public DomainModelSource(String type, Loader loader) {
        this(type, loader, List.of(), GroupMode.NESTED, null);
    }

    /** @param facets configured grouping for {@link #rootGroup()} (empty = no groups). */
    public DomainModelSource(String type, Loader loader, List<Facet<Viewable>> facets) {
        this(type, loader, facets, GroupMode.NESTED, null);
    }

    public DomainModelSource(String type, Loader loader, List<Facet<Viewable>> facets,
                             GroupMode mode, String rootName) {
        this.type = type;
        this.loader = loader;
        this.facets = facets == null ? List.of() : facets;
        this.mode = mode == null ? GroupMode.NESTED : mode;
        this.rootName = rootName == null || rootName.isBlank() ? type : rootName;
    }

    @Override public String type() { return type; }

    @Override public Collection<? extends Viewable> load() throws Exception {
        List<Viewable> out = new ArrayList<>();
        for (Viewable q : domain().instances()) {
            if (q != null && type.equals(q.typeName())) {
                out.add(q);
            }
        }
        return out;
    }

    @Override public ViewableGroup rootGroup() throws Exception {
        if (facets.isEmpty()) {
            return null;
        }
        return mode == GroupMode.FLAT
                ? FacetGrouper.group(ViewableGroup::new, rootName, load(), facets)
                : FacetGrouper.groupNested(ViewableGroup::new, rootName, load(), facets);
    }

    private synchronized DomainModel domain() throws Exception {
        if (cached == null) {
            cached = loader.get();
        }
        return cached;
    }

    /** Register one source per type of an ALREADY-LOADED domain (shared instance). */
    public static void register(ViewableStore store, DomainModel domain) {
        for (String t : domain.types()) {
            store.register(new DomainModelSource(t, () -> domain));
        }
    }
}
