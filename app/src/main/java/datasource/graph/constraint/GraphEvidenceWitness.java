package datasource.graph.constraint;

import datasource.graph.store.GraphEdge;

/**
 * One complete reason a node was accepted: the evidence edge that reached a node, and
 * the test edge on that node which satisfied the condition.
 *
 * <p>The two halves have to be kept together. Apostolic King of Hungary reaches the
 * Kingdom of Hungary through BOTH jurisdiction and country, so a flat list of test
 * edges cannot say which evidence relation carried the verdict — and that attribution
 * is the measurement the design quotes (jurisdiction 6,417, country 2,093,
 * directs-organization 1,066 of 8,449 accepted positions, counted with overlap
 * because a node may be witnessed through several relations).
 *
 * <p>Not every acceptance has a witness: an absence test matches by there being no
 * edge, so it accepts with an empty witness list and its coverage observation as the
 * record. Acceptance is therefore the test's verdict, never the presence of a witness.
 */
public record GraphEvidenceWitness(GraphEdge evidenceEdge, GraphEdge testEdge) {
    public GraphEvidenceWitness {
        if (evidenceEdge == null || testEdge == null) {
            throw new IllegalArgumentException(
                    "A witness names both the evidence edge and the test edge");
        }
    }
}
