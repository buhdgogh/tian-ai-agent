package com.tianaiagent.toolgateway;

import java.util.List;

public interface ToolAuditRepository {
    void save(ToolAuditRecord audit);
    List<ToolAuditRecord> find(String taskId, String ownerId, String chatId, String status, int limit, int offset);
}
