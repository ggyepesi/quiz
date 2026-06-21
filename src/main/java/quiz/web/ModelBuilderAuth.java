package quiz.web;

import com.sun.net.httpserver.BasicAuthenticator;

/**
 * Password gate for the model-builder endpoints. Credentials come from the
 * {@code MODELBUILDER_USER} / {@code MODELBUILDER_PASSWORD} environment
 * variables (default {@code builder}/{@code builder} for local dev).
 *
 * <p>Basic Auth only protects content over a trusted channel: bind the server
 * to localhost or put it behind a reverse proxy with TLS before exposing it.
 */
public class ModelBuilderAuth extends BasicAuthenticator {

    private final String user;
    private final String password;

    public ModelBuilderAuth() {
        super("modelbuilder");
        this.user = envOr("MODELBUILDER_USER", "builder");
        this.password = envOr("MODELBUILDER_PASSWORD", "builder");
    }

    @Override
    public boolean checkCredentials(String u, String p) {
        return user.equals(u) && password.equals(p);
    }

    private static String envOr(String key, String dflt) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? dflt : v;
    }
}
