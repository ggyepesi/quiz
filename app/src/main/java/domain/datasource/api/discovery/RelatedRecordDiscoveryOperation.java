package domain.datasource.api.discovery;

import domain.datasource.api.DatasourceOperation;
import work.Query;

import java.util.List;

/**
 * A source capability that finds bounded source-native records related to exact seeds.
 *
 * <p>The records remain inspection evidence. Deciding which modeled class they become
 * and how they attach to a field is a later explicit model/curation operation.
 */
public interface RelatedRecordDiscoveryOperation extends DatasourceOperation {
    String recordTypeName();
    List<DiscoveredRecordField> recordFields();
    int maximumSeeds();
    int maximumRecordsPerSeed();
    Query<RelatedRecordDiscoveryResult> discover(RelatedRecordDiscoveryRequest request);
}
