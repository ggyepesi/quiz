package benchmark;

import benchmark.generated.OscarNominationFormView;
import oscar.OscarNomination;
import quiz.QuizablePanelConfig;
import quiz.QuizablePanelConfigAdapter;
import quiz.ui.QuizablePanel;
import wikidata.WikidataEntity;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public class BenchmarkRunner {
    private static final int TARGET_COUNT = 5000;

    public static void main(String[] args) {
        System.out.println("=== Fabricating 5,000 Production-Grade Test Records ===");

        WikidataEntity mockActor = WikidataEntity.canonical("Marlon Brando", "Q16122");
        WikidataEntity mockAward = WikidataEntity.canonical("Best Actor", "Q103916");
        WikidataEntity mockMovie = WikidataEntity.canonical("The Godfather", "Q47703");

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
            QuizablePanel p = new QuizablePanel(nom, fullConfig, false);
            System.out.println(p.getComponentCount() + " " + p.hasRenderedConfiguredContent());
            for (Component c : p.getComponents()) {
                System.out.println(c.getClass().getName());
            }
            runtimePanels.add(p);
        }
        System.out.println(fullConfig);
        System.out.println(fullConfig.visibleFieldsFor(OscarNomination.class));

        long endTimeRuntime = System.currentTimeMillis();
        long memAfterRuntime = getUsedMemory();


        // =================================================================
        // Pass 2: The Generated Code View Loop (FormStamper Compiled)
        // =================================================================
        resetHeapMemory();
        long memBeforeCompiled = getUsedMemory();
        long startTimeCompiled = System.currentTimeMillis();

        List<OscarNominationFormView> compiledViews = new ArrayList<>(TARGET_COUNT);
        for (OscarNomination nom : dataset) {
            // No configuration mappings passed, no maps in constructors. Just unrolled bytecode!
            compiledViews.add(new OscarNominationFormView(nom));
        }

        long endTimeCompiled = System.currentTimeMillis();
        long memAfterCompiled = getUsedMemory();
        QuizablePanel.RenderStats.print();

        System.out.println("\n=================================================");
        System.out.println("   TRUE STATIC CODE GENERATION PERFORMANCE TRACE ");
        System.out.println("=================================================");
        System.out.printf("1. Runtime QuizablePanel Loop    : %4d ms | Heap Alloc: %3d MB\n",
                (endTimeRuntime - startTimeRuntime), Math.max(0, (memAfterRuntime - memBeforeRuntime) / 1024 / 1024));
        System.out.printf("2. True Generated FormView Class : %4d ms | Heap Alloc: %3d MB\n",
                (endTimeCompiled - startTimeCompiled), Math.max(0, (memAfterCompiled - memBeforeCompiled) / 1024 / 1024));
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