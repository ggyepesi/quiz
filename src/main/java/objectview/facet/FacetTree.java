package objectview.facet;

import java.util.ArrayList;
import java.util.List;

/**
 * A grouping dimension as a TREE: a {@link Facet} plus the sub-dimensions applied
 * <em>within each of its buckets</em>. Sibling children are PARALLEL sub-dimensions
 * (every bucket shows all of them side by side); a lone child is a nested drill-down.
 *
 * <p>This is the structural counterpart to a flat facet list: {@code category ->
 * [year]} drills year within each category bucket, while {@code category -> [year,
 * language]} shows both year and language as parallel breakdowns inside every
 * category. {@link FacetGrouper#graftTree} renders it.
 */
public record FacetTree(Facet facet, List<FacetTree> children) {

    public FacetTree(Facet facet) {
        this(facet, new ArrayList<>());
    }
}
