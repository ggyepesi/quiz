package quiz.transform.ui;

/** Opens (loads) a {@link DomainModel} lazily — implemented per backing (a
 *  Wikidata snapshot, a built-in Quizable domain, …) outside this package. */
public interface DomainSource {
    DomainModel open() throws Exception;
}
