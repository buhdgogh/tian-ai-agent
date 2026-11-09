package com.tianaiagent.domain.conversation.adapter.repository;

import java.util.Map;
import java.util.Optional;

public interface UserProfileStore {
    Optional<Profile> find(String ownerId);

    void merge(String ownerId, Profile changes);

    record Profile(Map<String, String> facts, Map<String, String> preferences) {}
}
