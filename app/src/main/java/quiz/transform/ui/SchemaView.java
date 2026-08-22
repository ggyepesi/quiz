package quiz.transform.ui;

/**
 * A domain that can show a Swing view of its compiled schema (and, where it has
 * one, the declared model it was compiled from — so the transformation is
 * visible). A capability seam: the generic workbench offers a "Schema…" action
 * only when the backing domain implements this, without knowing what's inside.
 */
public interface SchemaView extends domain.DomainCapability {

    /** A component visualizing this domain's schema, or null if there's none. */
    javax.swing.JComponent schemaView();
}
