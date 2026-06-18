package quiz.web.sources;

import flag.States;
import quiz.Quizable;
import quiz.QuizableGroup;
import quiz.facet.FacetGrouper;
import quiz.web.QuizableSource;

import java.util.Collection;

/**
 * Countries / states with flags, arms, shapes, capitals, currencies and
 * languages — read from bundled resources (no network).
 *
 * <p>Heavy first load: {@code States.buildViews()} reads several data files
 * and constructs Swing image components for every flag/arms/shape, so the
 * first request can take a while. Cached thereafter (within this source).
 *
 * <p>The curated group tree (Continents, Colors, Objects, …) already has the
 * faceted shape, so it just gets {@link FacetGrouper#assignRoles structural
 * roles}: dimension nodes become non-selectable headers, leaves stay
 * selectable buckets.
 */
public class StateSource implements QuizableSource {

    private States states;

    private synchronized States states() throws Exception {
        if (states == null) {
            States s = new States();
            s.buildViews();
            states = s;
        }
        return states;
    }

    @Override
    public String type() {
        return "State";
    }

    @Override
    public Collection<? extends Quizable> load() throws Exception {
        return states().getQuizables().values();
    }

    @Override
    public QuizableGroup rootGroup() throws Exception {
        return FacetGrouper.assignRoles(states().getGroupView().getRootGroup());
    }
}
