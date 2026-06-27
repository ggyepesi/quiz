package benchmark;

import oscar.OscarNomination;
import quiz.ui.viewconfig.QuizablePanelConfig;
import quiz.ui.viewconfig.QuizablePanelConfigAdapter;
import quiz.ui.QuizablePanel;
import wikidata.explore.extract.WikidataDynamicObject;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {
    private static final int TARGET_COUNT = 5000;

    public static void main(String[] args) {
        System.out.println("=== Fabricating 5,000 Production-Grade Test Records ===");

        WikidataDynamicObject mockActor = WikidataDynamicObject.canonical("Marlon Brando", "Q16122");
        WikidataDynamicObject mockAward = WikidataDynamicObject.canonical("Best Actor", "Q103916");
        WikidataDynamicObject mockMovie = WikidataDynamicObject.canonical("The Godfather", "Q47703");

        List<OscarNomination> dataset = new ArrayList<>(TARGET_COUNT);
        for (int i = 0; i < TARGET_COUNT; i++) {
            OscarNomination nom = new OscarNomination();
            nom.setNominee(mockActor);
            nom.setAward(mockAward);
            nom.setWork(mockMovie);
            nom.setCeremonyYear(1973);
            nom.setFilmYear(1972);
            nom.setWinner(i % 5 == 0);
            dataset.add(nom);
        }

        // Generate standard layout configurations for the legacy panel
        QuizablePanelConfig fullConfig = QuizablePanelConfigAdapter.fromOldArgs(
                dataset.get(0), true, true, true
        );

        System.out.println("Dataset Ready. Commencing Execution Profile Trace...");


        // =================================================================
        // Pass 1: Your Live Runtime QuizablePanel Loop (Reflection)
        // =================================================================
        resetHeapMemory();
        long memBeforeRuntime = getUsedMemory();
        long startTimeRuntime = System.currentTimeMillis();

        List<QuizablePanel> runtimePanels = new ArrayList<>(TARGET_COUNT);
        for (OscarNomination nom : dataset) {
            // Continually performs reflective scanning and metadata lookups per object instance
            runtimePanels.add(new QuizablePanel(nom, fullConfig, false));
        }

        long endTimeRuntime = System.currentTimeMillis();
        long memAfterRuntime = getUsedMemory();

        // Debug detail — printed AFTER timing so console I/O isn't counted as
        // render time (35k println calls here dwarf the actual rendering).
        QuizablePanel sample = runtimePanels.get(0);
        System.out.println(sample.getComponentCount() + " " + sample.hasRenderedConfiguredContent());
        for (Component c : sample.getComponents()) {
            System.out.println(c.getClass().getName());
        }
        System.out.println(fullConfig);
        System.out.println(fullConfig.visibleFieldsFor(OscarNomination.class));

        QuizablePanel.RenderStats.print();

        System.out.println("\n=================================================");
        System.out.println("        QuizablePanel RENDER PERFORMANCE TRACE    ");
        System.out.println("=================================================");
        System.out.printf("Runtime QuizablePanel Loop : %4d ms | Heap Alloc: %3d MB\n",
                (endTimeRuntime - startTimeRuntime), Math.max(0, (memAfterRuntime - memBeforeRuntime) / 1024 / 1024));
        System.out.println("=================================================");
    }

    private static void resetHeapMemory() {
        for (int i = 0; i < 3; i++) {
            System.gc();
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
    }

    private static long getUsedMemory() {
        return Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
    }
}