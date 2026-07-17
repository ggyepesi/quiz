package quiz.web.sources;

import mythology.MythologyEntities;
import quiz.Quizable;
import quiz.QuizableGroup;
import objectview.facet.Facet;
import objectview.facet.FacetGrouper;
import quiz.web.QuizableSource;

import java.util.Collection;
import java.util.List;

/**
 * Greek mythology — creatures (gods, heroes, monsters…) with their groups,
 * read from the bundled {@code greekmythology.txt}. No network.
 *
 * <p>The curated groups (argonauts, muses, titans…) sit flat under the root, so
 * we {@link FacetGrouper#wrapChildrenAsFacet wrap} them under one
 * "Affiliation" facet; on top we graft <i>reference</i> facets by parent, whose
 * buckets carry the parent {@code Creature} so the UI can show its card.
 */
public class MythologySource implements QuizableSource {

    private MythologyEntities entities;

    private synchronized MythologyEntities entities() throws Exception {
        if (entities == null) {
            MythologyEntities e = new MythologyEntities();
            e.buildViews();
            entities = e;
        }
        return entities;
    }

    @Override
    public String type() {
        return "GreekMythology";
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        return entities().getQuizables().values();
    }

    @Override
    public QuizableGroup rootGroup() throws Exception {
        QuizableGroup root = (QuizableGroup) entities().getGroupView().getRootGroup();
        FacetGrouper.wrapChildrenAsFacet(root, "Affiliation");
        FacetGrouper.addFacets(root, load(),
                List.of(Facet.reference("father", "Father"),
                        Facet.reference("mother", "Mother")));
        return root;
    }
}
