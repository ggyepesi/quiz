package work;

public record QueryEvent(
        long id,
        Query<?> query,
        QueryStatus status,
        int rowCount,
        long timeMs,
        String error) {

    public String purpose() {
        return query == null ? "" : query.purpose();
    }
}
