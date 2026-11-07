package com.tianaiagent.wiki;

import java.util.*;

/** One mutable state per root execution; guarded by its monitor, never keyed by a reusable chat ID. */
public final class WikiTraversalState {
    public static final String CONTEXT_KEY = "tian.wiki.traversal";
    public static final String CONSUMER_KEY = "tian.wiki.consumer";
    final Map<String, Integer> depths = new HashMap<>();
    final Map<String, Visit> visited = new LinkedHashMap<>();
    record Visit(String revisionId, String title, Set<String> neighbors,
                 com.fasterxml.jackson.databind.JsonNode data, Set<String> deliveredTo) {}
}
