package domain.workbench.execution.ui;

import dataset.DomainStorage;
import domain.build.CompiledPipelineRun;
import domain.build.GenerateDomainPipeline;
import domain.build.GenerateDomainProcess;
import domain.build.GenerationExecutionSettings;
import domain.build.GenerationExecutionSettingsPanel;
import domain.build.GenerationOperationProcess;
import domain.build.GenerationRecoveryStore;
import domain.build.GenerationRun;
import domain.build.PipelineRequest;
import domain.build.RemapScope;
import domain.build.RuleEffects;
import domain.build.RunSteps;
import domain.build.WikidataGraphDiscoveryState;
import domain.config.GeneratedClassModel;
import domain.config.GeneratedProjectModel;
import domain.datasource.graph.GraphDiscoveryState;
import domain.transform.DynamicViewable;
import objectview.Viewable;
import objectview.field.RecordTypes;
import process.Process;
import process.ProcessOutcome;
import process.ProcessStatus;
import process.ProcessWorkflowPipeline;
import process.swing.workflow.ProcessWorkflowAction;
import process.swing.workflow.ProcessWorkflowPlan;
import process.swing.workflow.ProcessWorkflowResults;
import wikidata.explore.query.logical.EnrichInstancesQuery;
import wikidata.explore.query.logical.GenerateInstancesQuery;
import wikidata.explore.query.logical.RecoverFinalGraphQuery;
import wikidata.explore.query.logical.RemapInstancesQuery;
import wikidata.ui.WikidataLinks;
import work.Query;

import javax.swing.JComponent;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Plans and presents the actions that produce an accepted generation run. The host
 * supplies the current working configuration and run, settles edits and user choices,
 * and owns installing the result. All four actions use the same Plan → Execute →
 * Results workflow and require an explicit Accept.
 */
public final class GenerationWorkflows {
    public enum RecoveryChoice { RESUME, GENERATE, CANCEL }

    static final String OPERATION = "Domain operation";
    static final String GENERATED_CLASS = "Generated class";
    static final String RULE_EFFECT = "Rule effect";
    static final String RESULT = "Generation result";

    // A runtime record has exactly the fields its producer declares (#363); without
    // these, the plan and result cards show their captions alone.
    static {
        RecordTypes.declare(OPERATION,
                RecordTypes.number("Existing objects"), RecordTypes.number("Classes"),
                RecordTypes.text("Selected class"), RecordTypes.number("Depth"),
                RecordTypes.text("Recovery file"), RecordTypes.text("Self-reference rule"));
        RecordTypes.declare(GENERATED_CLASS,
                RecordTypes.number("Depth"), RecordTypes.number("Fields"),
                RecordTypes.number("Pending QIDs from applied graph results"));
        RecordTypes.declare(RULE_EFFECT,
                RecordTypes.text("Action"), RecordTypes.number("Instances"));
        RecordTypes.declare(RESULT,
                RecordTypes.text("What the rules account for"),
                RecordTypes.text("Self-reference audit"), RecordTypes.text("Owned composition"),
                RecordTypes.text("Kind classification"), RecordTypes.text("Projections"),
                RecordTypes.number("Objects in the pool"));
    }

    private final Consumer<GenerationRun> acceptRun;
    private final Runnable showInstances;

    public GenerationWorkflows(Consumer<GenerationRun> acceptRun, Runnable showInstances) {
        this.acceptRun = acceptRun;
        this.showInstances = showInstances;
    }

    public Action generateClass(GeneratedProjectModel rooted, int depth, GenerationRun previous) {
        String className = rooted.rootClass().className();
        var settings = new GenerationExecutionSettings();
        var compiled = CompiledPipelineRun.compile(
                PipelineRequest.generateClassPreview(rooted, className, depth));
        var pipeline = new ProcessWorkflowPipeline(List.of(
                new ProcessWorkflowPipeline.Phase("generate-class", "Generate selected class",
                        "Discover and materialize the selected class with its configured depth and fields.",
                        List.of(className, "Depth " + depth))));
        return operation("Generate class — " + className,
                "Generate the selected class, inspect the result, then explicitly "
                        + "accept it into the instances view.",
                "generate-class", new GenerateInstancesQuery(compiled, settings, depth),
                pipeline, rooted, previous, settings, true, false, scope -> {
                    scope.put("Selected class", className);
                    scope.put("Depth", depth);
                });
    }

    /** Returns no action when the reader cancels the recovery choice. */
    public Action generateDomain(GeneratedProjectModel model,
            GraphDiscoveryState ledger, Map<String, List<String>> graphAdditions,
            DomainStorage storage, String generationReason, GenerationRun previous,
            Function<File, RecoveryChoice> chooseRecovery) {
        GeneratedProjectModel snapshot = model.copy();
        WikidataGraphDiscoveryState.applyExpansionLedger(snapshot, ledger);
        var compiled = CompiledPipelineRun.compile(
                PipelineRequest.generateDomain(snapshot, graphAdditions));
        var recovery = GenerationRecoveryStore.in(storage, snapshot.name());
        // A recovery graph predates these newly applied population inputs. Resuming
        // would materialize the old graph and silently omit them.
        if (graphAdditions.isEmpty() && recovery.canResume(snapshot)) {
            switch (chooseRecovery.apply(recovery.snapshotFile())) {
                case RESUME -> { return resume(compiled, recovery, snapshot, previous); }
                case CANCEL -> { return null; }
                case GENERATE -> { /* Plan a full generation below. */ }
            }
        }

        var pipeline = GenerateDomainPipeline.configured(compiled);
        var settings = new GenerationExecutionSettings();
        var process = new GenerateDomainProcess(compiled, pipeline, settings, recovery);
        List<Viewable> classCards = snapshot.classes().stream().<Viewable>map(clazz -> {
            var card = new DynamicViewable(clazz.className(), clazz.className());
            card.type(GENERATED_CLASS);
            card.put("Depth", clazz.generationDepth());
            card.put("Fields", clazz.fields().size());
            List<String> pending = graphAdditions.get(clazz.className());
            if (pending != null && !pending.isEmpty()) {
                card.put("Pending QIDs from applied graph results", pending.size());
            }
            return card;
        }).toList();
        String title = generationReason.isBlank()
                ? "Generate domain" : "Generate domain — " + generationReason;
        String description = generationReason.isBlank()
                ? "Generate every configured class into one shared domain snapshot."
                : "Regenerate every configured class after "
                        + generationReason.toLowerCase(Locale.ROOT)
                        + ". The selected frontier nodes are now population seeds.";
        return new Action("generate-domain", "Generate domain", "generation", snapshot.name(),
                pipeline, settings, true, null, process, () -> new ProcessWorkflowPlan(
                        title, description, List.of(new ProcessWorkflowPlan.Tab("Classes", classCards))));
    }

    private Action resume(CompiledPipelineRun compiled, GenerationRecoveryStore recovery,
            GeneratedProjectModel snapshot, GenerationRun previous) {
        String file = recovery.snapshotFile().getPath();
        String phase = GenerateDomainPipeline.MATERIALIZE;
        var pipeline = new ProcessWorkflowPipeline(List.of(new ProcessWorkflowPipeline.Phase(
                phase, "Resume materialization",
                "Load the finalized recovery graph from " + file + " and map it into instances.",
                List.of(file))));
        return operation("Resume materialization",
                "Load " + file + " and materialize its finalized objects without repeating remote generation.",
                phase, new RecoverFinalGraphQuery(compiled, recovery), pipeline, snapshot, previous,
                new GenerationExecutionSettings(), false, false,
                scope -> scope.put("Recovery file", file));
    }

    /** Reuses the last download and re-materializes it with the current model, so
     * canonicalization and display-name edits apply without a re-fetch. */
    public Action remap(GeneratedProjectModel model, GenerationRun previous, GraphDiscoveryState ledger) {
        GeneratedProjectModel snapshot = model.copy();
        var replayable = RemapScope.of(previous);
        var compiled = CompiledPipelineRun.compile(
                PipelineRequest.remap(snapshot, previous.remapCheckpoint(ledger)));
        var pipeline = GenerateDomainPipeline.configuredRemap(compiled,
                List.of(previous.dynamicObjects().size() + " existing objects",
                        snapshot.classes().size() + " configured classes"),
                replayable.retransform());
        // The remap knows what it can replay; it says so rather than leaving the
        // plan to guess from the operation's name.
        return operation("Remap domain", "Preview local model changes without downloading data.",
                "remap", new RemapInstancesQuery(previous, compiled, RunSteps.of(pipeline)),
                pipeline, snapshot, previous, new GenerationExecutionSettings(), false, true,
                scope -> scope.put("Self-reference rule", replayable.retransform()
                        ? "Will run during reification; its keep/drop decisions are shown in the results"
                        : "Will not run — this run has no pre-reification pool to replay from"));
    }

    /** Additive: a field declared since the download needs its property fetched for the
     * entities already here, not a new extraction. */
    public Action enrich(GeneratedProjectModel model, GenerationRun previous, GraphDiscoveryState ledger) {
        GeneratedProjectModel snapshot = model.copy();
        var settings = new GenerationExecutionSettings();
        var compiled = CompiledPipelineRun.compile(
                PipelineRequest.enrich(snapshot, previous.checkpoint(ledger)));
        var pipeline = GenerateDomainPipeline.configuredEnrich(compiled, enrichmentDetails(snapshot, previous));
        return operation("Enrich domain",
                "Add newly declared values to the existing population without rediscovery.",
                "enrich", new EnrichInstancesQuery(previous, compiled, settings, RunSteps.of(pipeline)),
                pipeline, snapshot, previous, settings, true, true,
                scope -> scope.put("Self-reference rule",
                        "Will not run — Enrich adds values without reifying statements"));
    }

    private static List<String> enrichmentDetails(GeneratedProjectModel snapshot, GenerationRun previous) {
        List<String> details = new ArrayList<>();
        details.add(previous.dynamicObjects().size() + " existing objects");
        for (GeneratedClassModel clazz : snapshot.classes()) {
            List<String> properties = clazz.fields().stream()
                    .map(field -> field.mapping() == null ? "" : field.mapping().propertyPid())
                    .filter(pid -> pid != null && !pid.isBlank()).distinct().toList();
            if (!properties.isEmpty()) {
                details.add(clazz.className() + " — " + String.join(", ", properties));
            }
        }
        if (details.size() == 1) details.add("No property-backed fields configured");
        return details;
    }

    private Action operation(String title, String description, String phase, Query<GenerationRun> query,
            ProcessWorkflowPipeline pipeline, GeneratedProjectModel snapshot, GenerationRun previous,
            GenerationExecutionSettings settings, boolean networked, boolean showExistingRuleEffects,
            Consumer<DynamicViewable> describeScope) {
        var process = new GenerationOperationProcess(title, description, phase, query, pipeline, settings, networked);
        var summary = new DynamicViewable(title.toLowerCase(Locale.ROOT).replace(' ', '-'), snapshot.name());
        summary.type(OPERATION);
        summary.put("Existing objects", previous == null ? 0 : previous.size());
        summary.put("Classes", snapshot.classes().size());
        // Whether reification runs is a property of the operation, and the caller that
        // builds it knows. Deciding it here from the phase id would infer behaviour from
        // a name, and a rename would silently drop the warning.
        describeScope.accept(summary);

        return new Action(phase, title, phase, snapshot.name(), pipeline, settings, networked,
                WikidataLinks.valueLinker(), process, () -> {
                    List<ProcessWorkflowPlan.Tab> tabs = new ArrayList<>();
                    tabs.add(new ProcessWorkflowPlan.Tab("Scope", List.of(summary)));
                    // A rule that can say WHICH instances it accounts for gets its own tab,
                    // so the plan states what this run is about. Evaluated on the existing
                    // pool, not executed or applied by preview.
                    if (showExistingRuleEffects && previous != null && previous.dynamicObjects() != null) {
                        for (RuleEffects.Effect effect : RuleEffects.of(snapshot, previous.dynamicObjects())) {
                            List<Viewable> contents = new ArrayList<>();
                            contents.add(ruleEffectSummary(effect, phase + "-plan"));
                            contents.addAll(effect.instances());
                            tabs.add(new ProcessWorkflowPlan.Tab(effect.title(), contents));
                        }
                    }
                    return new ProcessWorkflowPlan(title, description, tabs);
                });
    }

    private static DynamicViewable ruleEffectSummary(RuleEffects.Effect effect, String phase) {
        var summary = new DynamicViewable(
                phase + "-rule-" + Integer.toHexString(effect.rule().hashCode()), effect.rule());
        summary.type(RULE_EFFECT);
        summary.put("Action", effect.detail());
        summary.put("Instances", effect.size());
        return summary;
    }

    /** The common workflow adapter for both query-backed operations and full generation. */
    public final class Action implements ProcessWorkflowAction<GenerationRun, GenerationRun> {
        private final String id;
        private final String title;
        private final String resultPhase;
        private final String projectName;
        private final ProcessWorkflowPipeline pipeline;
        private final GenerationExecutionSettings settings;
        private final boolean networked;
        private final Function<Object, String> valueLinker;
        private final Process<GenerationRun> process;
        private final Supplier<ProcessWorkflowPlan> plan;

        private Action(String id, String title, String resultPhase, String projectName,
                ProcessWorkflowPipeline pipeline, GenerationExecutionSettings settings,
                boolean networked, Function<Object, String> valueLinker,
                Process<GenerationRun> process, Supplier<ProcessWorkflowPlan> plan) {
            this.id = id;
            this.title = title;
            this.resultPhase = resultPhase;
            this.projectName = projectName;
            this.pipeline = pipeline;
            this.settings = settings;
            this.networked = networked;
            this.valueLinker = valueLinker;
            this.process = process;
            this.plan = plan;
        }

        public String projectName() { return projectName; }
        public String logTitle() { return title + " — " + projectName; }
        @Override public String id() { return id; }
        @Override public ProcessWorkflowPipeline pipeline() { return pipeline; }
        @Override public Function<Object, String> valueLinker() { return valueLinker; }
        @Override public ProcessWorkflowPlan plan() { return plan.get(); }
        @Override public Process<GenerationRun> process() { return process; }
        @Override public JComponent executionSettings() {
            return new GenerationExecutionSettingsPanel(settings, networked);
        }
        @Override public boolean applyAllowed(ProcessStatus status) {
            return status != ProcessStatus.PARTIAL || !settings.requireComplete();
        }
        @Override public ProcessWorkflowResults<GenerationRun> results(ProcessOutcome<GenerationRun> outcome) {
            return runResults(title, resultPhase, projectName, outcome.result(), outcome, pipeline, true);
        }
        @Override public void apply(List<GenerationRun> decisions) {
            if (!decisions.isEmpty()) acceptRun.accept(decisions.getFirst());
        }
        @Override public void afterApply() {
            // Reveal only after the workflow closes, so macOS does not steal focus back.
            showInstances.run();
        }
    }

    /**
     * The same result account for every generation operation and the read-only Run report:
     * a summary, the instances each rule accounts for, then the whole pool as context.
     *
     * <p>Shared because Generate once had its own copy. Two operations presenting their
     * outcome differently is how a reader learns to distrust both; it is how the rule tabs
     * reached Remap while the run that actually drops records, a generation, kept showing
     * 47036 cards and a number.
     */
    public static ProcessWorkflowResults<GenerationRun> runResults(
            String title, String phase, String projectName, GenerationRun run,
            ProcessOutcome<GenerationRun> outcome, ProcessWorkflowPipeline pipeline, boolean acceptRun) {
        var effects = RuleEffects.inPipelineOrder(RuleEffects.fromRun(
                run.fieldCoverage(), run.selfReferenceAudit(), run.ownedCompositionAudit(),
                run.kindClassificationAudit(), run.projectionAudit()), pipeline);
        var summary = new DynamicViewable(phase + "-summary", projectName);
        summary.type(RESULT);
        // The pool size is context, not a result: for a Remap it barely moves, so leading
        // with it says nothing about what the run did.
        summary.put("What the rules account for", RuleEffects.describe(effects));
        summary.put("Self-reference audit", run.selfReferenceAudit().description());
        summary.put("Owned composition", run.ownedCompositionAudit().description());
        summary.put("Kind classification", run.kindClassificationAudit().description());
        summary.put("Projections", run.projectionAudit().description());
        summary.put("Objects in the pool", run.size());

        List<ProcessWorkflowResults.Tab<GenerationRun>> tabs = new ArrayList<>();
        tabs.add(new ProcessWorkflowResults.Tab<>("Summary", List.of(
                new ProcessWorkflowResults.Card<>(summary, () -> acceptRun ? run : null, acceptRun))));
        for (RuleEffects.Effect effect : effects) {
            tabs.add(new ProcessWorkflowResults.Tab<>(
                    effect.phase().label() + " · " + effect.title(), instanceCards(effect.instances())));
        }
        tabs.add(new ProcessWorkflowResults.Tab<>("All objects (" + run.size() + ")", instanceCards(run.instances())));
        return new ProcessWorkflowResults<>(title + " — results", outcome.summary(), "Accept", tabs);
    }

    static List<ProcessWorkflowResults.Card<GenerationRun>> instanceCards(List<? extends Viewable> instances) {
        return instances.stream().map(instance ->
                new ProcessWorkflowResults.Card<GenerationRun>(instance, () -> null, false, () -> null)).toList();
    }
}
