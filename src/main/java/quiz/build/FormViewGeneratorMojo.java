package quiz.build;

import org.formstamper.core.engine.PathDrivenFormCompiler;
import oscar.OscarNomination;
import quiz.QuizableFieldPaths;
import quiz.QuizablePanelConfig;
import quiz.QuizablePanelConfigAdapter;

import java.io.File;

public class FormViewGeneratorMojo {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Error: No output directory provided.");
            System.exit(1);
        }

        File outputDir = new File(args[0]);
        if (!outputDir.exists()) {
            outputDir.mkdirs();
        }

        System.out.println("FormStamper: Generating optimized Swing code views...");

        try {
            // 1. Stub a model instance to extract configurations (just like in the benchmark)
            OscarNomination dummy = new OscarNomination();
            QuizablePanelConfig fullConfig = QuizablePanelConfigAdapter.fromOldArgs(dummy, true, true, true);

            // 2. Resolve the exact list of structural paths using your utility
            var collectedPaths = QuizableFieldPaths.collect(fullConfig, QuizableFieldPaths.NOT_IMAGE_PANE_FIELDS);

            // 3. Fire up the compiler engine to write out hardcoded .java files
            PathDrivenFormCompiler compiler = new PathDrivenFormCompiler();
            compiler.compileFromPaths(
                    OscarNomination.class,
                    collectedPaths,
                    "benchmark", // The target package
                    outputDir
            );

            System.out.println("FormStamper: Successfully compiled code to " + outputDir.getAbsolutePath());
        } catch (Exception e) {
            System.err.println("FormStamper Generation Failed!");
            e.printStackTrace();
            System.exit(1);
        }
    }
}