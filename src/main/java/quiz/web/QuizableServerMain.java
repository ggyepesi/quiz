package quiz.web;

import quiz.web.sources.OscarSource;
import quiz.web.sources.SportTeamSource;
import quiz.web.sources.StateSource;

/**
 * Launches the read-only Quizable JSON API.
 *
 * <pre>{@code
 *   mvn -o exec:java -Dexec.mainClass=quiz.web.QuizableServerMain
 *   curl http://localhost:7070/api/types
 *   curl 'http://localhost:7070/api/quizables?type=OscarNomination'
 *   curl http://localhost:7070/api/quizable/OscarNomination/<id>
 * }</pre>
 */
public class QuizableServerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7070;

        QuizableStore store = new QuizableStore();
        store.register(new SportTeamSource());
        store.register(new StateSource());
        store.register(new OscarSource());

        new QuizableHttpServer(store).start(port);

        // keep the JVM alive
        Thread.currentThread().join();
    }
}
