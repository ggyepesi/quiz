package batch;

import objectview.utils.UrlOpener;
import org.junit.jupiter.api.Test;

import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A status escaping the shared HTTP path used to be FATAL whatever it was, so one 500 from
 * Wikimedia Pageviews stopped every article after it. Only a status the server may answer
 * differently later is worth the executor's unavailable budget; the rest stay FATAL.
 */
class FailureClassifierHttpStatusTest {

    @Test void aServerSideStatusIsRetriedOnTheUnavailableBudget() throws Exception {
        for (int status : new int[] {429, 500, 503}) {
            assertEquals(BatchFailure.UNAVAILABLE, classify(status), "HTTP " + status);
        }
    }

    @Test void aStatusTheServerWillNotReconsiderStaysFatal() throws Exception {
        for (int status : new int[] {400, 403, 404}) {
            assertEquals(BatchFailure.FATAL, classify(status), "HTTP " + status);
        }
    }

    private static BatchFailure classify(int status) throws Exception {
        return FailureClassifier.standard().classify(new RuntimeException("wrapped",
                new UrlOpener.HttpStatusException(status, new URL("https://example.org/"))))
                .failure();
    }
}
