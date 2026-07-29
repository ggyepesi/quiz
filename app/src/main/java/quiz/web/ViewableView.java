package quiz.web;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Backend-agnostic, JSON-serializable render model for a {@link
 * objectview.Viewable} — the web counterpart of what {@code Card}
 * computes for the Swing UI. The frontend draws a card straight from this.
 *
 * <p>References are <i>lazy</i>: a {@code ref}/{@code refs} field carries
 * only the target's id/name/type, and the client fetches the full view by
 * id when the user expands it (the web equivalent of chip expand/collapse).
 * {@code inline} fields ({@code @Inline}) embed the full nested
 * view, since those are meant to be shown expanded.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ViewableView(
        String id,
        String name,
        String type,
        List<Field> fields) {

    /** A reference to another Viewable, resolved lazily by the client. */
    /** A chip. {@code inline} is the embedded expansion for a VALUE object (not pooled,
     *  so it can't be fetched by id); null for an entity, whose chip fetches lazily. */
    public record Ref(String id, String name, String type, String thumb,
                      ViewableView inline) {}

    /**
     * One rendered field. {@code kind} selects which payload is populated:
     * <ul>
     *   <li>{@code text}   — {@code value}</li>
     *   <li>{@code list}   — {@code values} (collection of scalars)</li>
     *   <li>{@code link}   — {@code label} + {@code url}</li>
     *   <li>{@code ref}    — {@code ref} (single nested Viewable)</li>
     *   <li>{@code refs}   — {@code refs} (collection/map of Viewables)</li>
     *   <li>{@code inline} — {@code nodes} (fully expanded nested views)</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Field(
            String name,
            String kind,
            String value,
            List<String> values,
            String label,
            String url,
            Ref ref,
            List<Ref> refs,
            List<ViewableView> nodes) {

        public static Field text(String name, String value) {
            return new Field(name, "text", value, null, null, null, null, null, null);
        }

        public static Field list(String name, List<String> values) {
            return new Field(name, "list", null, values, null, null, null, null, null);
        }

        public static Field link(String name, String label, String url) {
            return new Field(name, "link", null, null, label, url, null, null, null);
        }

        /** {@code url} points at the backend image endpoint that serves bytes. */
        public static Field image(String name, String url) {
            return new Field(name, "image", null, null, null, url, null, null, null);
        }

        /** A collection of images; {@code values} are the per-image endpoint URLs. */
        public static Field images(String name, List<String> urls) {
            return new Field(name, "images", null, urls, null, null, null, null, null);
        }

        public static Field ref(String name, Ref ref) {
            return new Field(name, "ref", null, null, null, null, ref, null, null);
        }

        public static Field refs(String name, List<Ref> refs) {
            return new Field(name, "refs", null, null, null, null, null, refs, null);
        }

        public static Field inline(String name, List<ViewableView> nodes) {
            return new Field(name, "inline", null, null, null, null, null, null, nodes);
        }
    }
}
