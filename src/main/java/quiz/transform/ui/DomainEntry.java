package quiz.transform.ui;

/** A catalog entry for the {@link DomainNavigator}: a display name, a source tag,
 *  and a lazy {@link DomainSource}. Backing-agnostic — the catalog is assembled
 *  outside this package so the UI stays independent of any data source. */
public record DomainEntry(String name, String source, DomainSource opener) {
    @Override public String toString() {
        return "[" + source + "]  " + name;
    }
}
