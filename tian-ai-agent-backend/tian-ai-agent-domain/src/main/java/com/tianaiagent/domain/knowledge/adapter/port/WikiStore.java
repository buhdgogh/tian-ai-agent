package com.tianaiagent.domain.knowledge.adapter.port;

import com.tianaiagent.domain.knowledge.model.entity.WikiSnapshot;

import java.util.Map;
import java.util.Optional;

public interface WikiStore {
    String readParsedSource(String spaceId, String docId, int maximumChars);

    Optional<WikiSnapshot> load(String spaceId);

    void publish(String spaceId, WikiSnapshot snapshot, Map<String, String> sourceTitles);
    void deleteSpace(String spaceId);
}
