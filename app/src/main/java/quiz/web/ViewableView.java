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

    /** A chip. {@code inline} is the embedded expansion for an object that is not
     *  fetched by id; null for an entity, whose chip fetches lazily. {@code via} is set
     *  on a collection member's chip: the member renders under its collection's config,
     *  so the fetch names the card whose config that is and the config path below it.
     *  A chip with neither id nor inline is a caption alone. */
    public record Ref(String id, String name, String type, String thumb,
                      ViewableView inline, Via via) {
        public Ref(String id, String name, String type, String thumb, ViewableView inline) {
            this(id, name, type, thumb, inline, null);
        }
    }

    /** The config a lazily fetched member renders under: the child config at
     *  {@code path} of the card {@code type}/{@code id}. */
    public record Via(String type, String id, String path) {}

    /**
     * One rendered field. {@code kind} selects which payload is populated:
     * <ul>
     *   <li>{@code text}   — {@code value}</li>
     *   <li>{@code list}   — {@code values} (collection of scalars)</li>
     *   <li>{@code link}   — {@code label} + {@code url}</li>
     *   <li>{@code ref}    — {@code ref} (single nested Viewable)</li>
     *   <li>{@code refs}   — {@code refs} (collection/map of Viewables)</li>
     *   <li>{@code inline} — {@code nodes} (fully expanded nested views)</li>
     *   <li>{@code empty}  — nothing: a ticked object with nothing ticked under it shows
     *       its field name alone</li>
     * </ul>
     * {@code size} and {@code open} are set on a collection: it always shows
     * {@code name (size)}, and its members show once it is unfolded.
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
            List<ViewableView> nodes,
            Integer size,
            Boolean open) {

        public Field(String name, String kind, String value, List<String> values,
                     String label, String url, Ref ref, List<Ref> refs,
                     List<ViewableView> nodes) {
            this(name, kind, value, values, label, url, ref, refs, nodes, null, null);
        }

        /** This field as a collection of {@code size} members that starts unfolded
         *  when {@code open}. */
        public Field collection(int size, boolean open) {
            return new Field(name, kind, value, values, label, url, ref, refs, nodes,
                    size, open);
        }

        public static Field empty(String name) {
            return new Field(name, "empty", null, null, null, null, null, null, null);
        }

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
