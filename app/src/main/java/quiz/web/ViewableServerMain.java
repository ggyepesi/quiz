package quiz.web;

import quiz.web.sources.GeneratedSource;

/**
 * Launches the read-only Viewable JSON API.
 *
 * <pre>{@code
 *   mvn -o exec:java -Dexec.mainClass=quiz.web.ViewableServerMain
 *   curl http://localhost:7070/api/types
 *   curl 'http://localhost:7070/api/viewables?type=OscarNomination'
 *   curl http://localhost:7070/api/viewable/OscarNomination/<id>
 * }</pre>
 */
public class ViewableServerMain {

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 7070;

        // Don't render images during dataset load — the image endpoint
        // produces them on demand. Keeps the (image-heavy) State load fast.
        System.setProperty("objectview.lazyImages", "true");

        ViewableStore store = new ViewableStore();

        // Serve ONLY snapshots listed in the DatasetRegistry — no bespoke per-type
        // sources. Every domain is a snapshot now: generated-from-Wikidata datasets AND
        // the hand-built ones (State, SportTeams, Mythology, Nobel, …) saved via the
        // transform app's "Save as domain…". registerAll serves every stamped class in
        // each snapshot (e.g. Constellation AND its Stars).
        quiz.DatasetRegistry registry = quiz.DatasetRegistry.load();
        if (registry.datasets().isEmpty()) {
            // First run / no registry yet: fall back to the constellations file.
            GeneratedSource.registerAll(store, "Constellation",
                    new java.io.File(aux.Constants.constellationsDataDirectory
                            + "constellations.snapshot.json"));
        } else {
            for (quiz.DatasetRegistry.Dataset d : registry.datasets()) {
                java.io.File snap = new java.io.File(d.snapshotPath());
                if (snap.isFile()) {
                    java.io.File model = d.modelPath().isBlank()
                            ? null : new java.io.File(d.modelPath());
                    GeneratedSource.registerAll(store,
                            d.rootClass().isBlank() ? d.name() : d.rootClass(),
                            snap, model);
                } else {
                    System.err.println("Dataset '" + d.name()
                            + "': snapshot missing at " + d.snapshotPath() + " (skipped)");
                }
            }
        }

        // Group served types by domain so the web client can list classes per
        // domain (it grows past a flat list). Registry datasets give the grouping;
        // any type not claimed by a dataset's declared types goes under "Other".
        java.util.LinkedHashMap<String, java.util.List<String>> domains =
                new java.util.LinkedHashMap<>();
        java.util.Set<String> claimed = new java.util.HashSet<>();
        for (quiz.DatasetRegistry.Dataset d : registry.datasets()) {
            java.util.List<String> ts = new java.util.ArrayList<>();
            for (String t : d.types()) {
                if (store.types().contains(t)) {
                    ts.add(t);
                    claimed.add(t);
                }
            }
            if (!ts.isEmpty()) {
                domains.put(d.name(), ts);
            }
        }
        java.util.List<String> other = new java.util.ArrayList<>();
        for (String t : store.types()) {
            if (!claimed.contains(t)) {
                other.add(t);
            }
        }
        if (!other.isEmpty()) {
            domains.put("Other", other);
        }

        new ViewableHttpServer(store).domains(domains).start(port);

        // keep the JVM alive
        Thread.currentThread().join();
    }
}
