package quiz.transform.ui;

import quiz.Quizable;

import java.util.List;

/**
 * A class produced by a PROJECT operation: a new type name, the fields it carries
 * (the projected columns), and its materialized instances (one per source member).
 * Added to the {@link WorkingDomain} so its fields feed back into the pool and
 * later operations can consume it — the composable transform graph.
 */
public record DerivedClass(String type, List<DomainField> fields,
                           List<? extends Quizable> instances) {}
