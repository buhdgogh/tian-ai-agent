package com.tianaiagent.domain.conversation.service;

import java.util.Optional;
import java.util.regex.Pattern;

public final class ConversationScope {
    private static final Pattern PUBLIC_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern RESERVED = Pattern.compile("(?i)^(?:u-[a-z0-9_]{1,32}|kb-[a-z0-9]{1,32})--");
    private static final Pattern STORAGE_ID = Pattern.compile(
            "(?:u-([a-z0-9_]{1,32})--)?(?:kb-([a-z0-9]{1,32})--)?[A-Za-z0-9_-]{1,128}");

    private ConversationScope() {}

    /** 无空间的通用对话使用独立命名空间，避免加载旧的无空间医疗对话。 */
    public static String workspaceScope(String spaceId) {
        return spaceId == null ? "general" : spaceId;
    }

    public static String storageId(String owner, String id) {
        return storageId(owner, id, null);
    }

    public static String storageId(String owner, String id, String spaceId) {
        if (id == null || !PUBLIC_ID.matcher(id).matches() || RESERVED.matcher(id).find()) {
            throw new IllegalArgumentException("非法会话 ID");
        }
        return ownerPrefix(owner) + spacePrefix(spaceId) + id;
    }

    public static boolean validStorageId(String id) { return id != null && STORAGE_ID.matcher(id).matches(); }

    public static StorageScope storageScope(String id) {
        var matcher = STORAGE_ID.matcher(id == null ? "" : id);
        if (!matcher.matches()) throw new IllegalArgumentException("非法会话 ID");
        String owner = matcher.group(1);
        String scope = matcher.group(2);
        return new StorageScope(owner == null ? "admin" : owner, scope == null ? "" : scope);
    }

    public record StorageScope(String ownerId, String scopeId) {}

    public static Optional<String> visibleId(String owner, String storageId) {
        return visibleId(owner, storageId, null);
    }

    public static Optional<String> visibleId(String owner, String storageId, String spaceId) {
        if (!validStorageId(storageId)) return Optional.empty();
        String prefix = ownerPrefix(owner) + spacePrefix(spaceId);
        if (!storageId.startsWith(prefix)) return Optional.empty();
        String id = storageId.substring(prefix.length());
        return PUBLIC_ID.matcher(id).matches() && !RESERVED.matcher(id).find() ? Optional.of(id) : Optional.empty();
    }

    private static String ownerPrefix(String owner) {
        if ("admin".equals(owner)) return "";
        if (owner == null || !owner.matches("[a-z0-9_]{1,32}")) throw new IllegalArgumentException("非法会话所有者");
        return "u-" + owner + "--";
    }

    private static String spacePrefix(String spaceId) {
        if (spaceId == null) return "";
        if (!spaceId.matches("[a-z0-9]{1,32}")) throw new IllegalArgumentException("非法空间 ID");
        return "kb-" + spaceId + "--";
    }
}
