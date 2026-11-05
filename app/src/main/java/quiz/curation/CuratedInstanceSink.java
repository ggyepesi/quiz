package quiz.curation;

/** A loaded domain whose ordinary instance pool can preview accepted curated instances. */
public interface CuratedInstanceSink extends domain.DomainCapability {
    CuratedInstances.Result materialize(java.util.Collection<CuratedInstance> instances);
}
