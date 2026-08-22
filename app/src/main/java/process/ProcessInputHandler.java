package process;
import work.CancellationToken;

@FunctionalInterface
public interface ProcessInputHandler {
    <T> T request(ProcessInputRequest<T> request, CancellationToken cancellation)
            throws Exception;

    static ProcessInputHandler unsupported() {
        return new ProcessInputHandler() {
            @Override public <T> T request(
                    ProcessInputRequest<T> request, CancellationToken cancellation) {
                throw new IllegalStateException(
                        "No input handler for: " + request.title());
            }
        };
    }
}
