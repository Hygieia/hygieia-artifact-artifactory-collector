package com.capitalone.dashboard.repository;

import com.capitalone.dashboard.model.ArtifactItem;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.repository.Query;

import java.util.List;
import java.util.Set;


public interface ArtifactItemRepository extends BaseCollectorItemRepository<ArtifactItem>{

    @Query(value="{ 'collectorId' : ?0 }")
    Set<ArtifactItem> findByCollectorIdInSet(ObjectId collectorId);

    @Query(value="{ 'collectorId' : ?0, options.instanceUrl : ?1, options.repoName : ?2}")
    ArtifactItem findArtifactItem(ObjectId collectorId, String instanceUrl, String repoName);

    @Query(value="{ 'collectorId' : ?0, enabled: true}")
    Set<ArtifactItem> findEnabledArtifactItems(ObjectId collectorId);

}
