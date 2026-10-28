package wikidata.explore.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The saved population producer of one materialized class.
 *
 * <p>The containing {@link GeneratedClassModel} supplies the producer's identity, name and
 * output class.  This source therefore records only what has to be executed: the input class
 * and the ordered elementary steps.  It is the population-source sibling of
 * {@link GraphClassSource}, {@link StatementClassSource} and {@link AggregateClassSource}, not
 * a second project-level construct held in agreement with its output class.</p>
 */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public final class TransformationClassSource {
    private String inputClassId = "";
    private String inputClassName = "";
    private final List<TransformationStep> steps = new ArrayList<>();

    public TransformationClassSource() { }

    public String inputClassId() { return DeclarationIds.clean(inputClassId); }
    public String inputClassName() { return clean(inputClassName); }

    public void inputClassReference(String id, String name) {
        inputClassId = DeclarationIds.clean(id);
        inputClassName = clean(name);
    }

    public List<TransformationStep> steps() {
        return Collections.unmodifiableList(steps);
    }

    public void steps(List<TransformationStep> values) {
        steps.clear();
        if (values != null) values.stream().filter(java.util.Objects::nonNull)
                .map(TransformationStep::copy).forEach(steps::add);
    }

    public void addStep(TransformationStep value) {
        if (value != null) steps.add(value.copy());
    }

    public boolean configured() {
        return !inputClassName().isBlank() && !steps.isEmpty()
                && steps.stream().allMatch(TransformationStep::configured);
    }

    public TransformationClassSource copy() {
        TransformationClassSource copy = new TransformationClassSource();
        copy.inputClassId = inputClassId;
        copy.inputClassName = inputClassName;
        copy.steps(steps);
        return copy;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
