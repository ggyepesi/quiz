package wikidata.explore.workbench;

import objectview.annotations.Reference;
import objectview.Viewable;
import objectview.ViewableAdapter;

import java.util.List;

/**
 * One name shared by several distinct entities (e.g. 5 different "Agenor"s).
 * Rendered as a small card — the {@code name} plus the colliding {@code entities}
 * — so the ambiguity is inspectable in the generated instances window rather
 * than only in the query log.
 *
 * <p>Each entry is the actual generated instance where one exists (a typed
 * {@code Character}/{@code Episode} you can click through to), otherwise a
 * a {@link quiz.source.WikidataViewable} carrying just the QID + wiki link.
 */
public class NameCollision extends ViewableAdapter {

    public String name = "";

    @Reference
    public List<Viewable> entities = List.of();

    public NameCollision() {
    }

    public NameCollision(String name, List<Viewable> entities) {
        this.name = name == null ? "" : name;
        this.entities = entities == null ? List.of() : entities;
    }

    @Override
    public String getIdentifier() {
        return name;
    }

    @Override
    public String getDisplayName() {
        return name;
    }

    @Override
    public String typeName() {
        return "NameCollision";
    }
}
