package quiz.source;

/** A source kind without a specialized implementation. */
public final class ExternalSource extends Source {
    public ExternalSource(String kind, String sourceId, String recordUrl) {
        super(kind, sourceId, recordUrl);
    }

    public ExternalSource(
            String kind, String sourceId, String recordUrl, String name) {
        super(kind, sourceId, recordUrl, name);
    }
}
