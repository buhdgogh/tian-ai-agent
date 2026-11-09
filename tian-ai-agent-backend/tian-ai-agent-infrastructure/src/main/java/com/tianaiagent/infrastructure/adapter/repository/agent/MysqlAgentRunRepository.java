package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.infrastructure.dao.mysql.AgentRunMapper;
import com.tianaiagent.infrastructure.dao.po.AgentRunRows;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class MysqlAgentRunRepository implements AgentRunRepository {
    private final AgentRunMapper runs;
    private final TransactionTemplate transactions;

    public MysqlAgentRunRepository(AgentRunMapper runs, TransactionTemplate transactions) {
        this.runs = runs;
        this.transactions = transactions;
    }

    public void importLegacy(FileAgentRunRepository legacy) {
        legacy.forEachRun(this::save);
    }

    @Override
    public void save(AgentRun run) {
        var summary = run.summary();
        transactions.executeWithoutResult(status -> {
            try {
                runs.insert(new AgentRunRows.Summary(summary.id(), summary.ownerId(), summary.kind(), summary.taskId(), summary.parentRunId(),
                        summary.status(), LocalDateTime.ofInstant(summary.startedAt(), ZoneOffset.UTC), summary.elapsedMs(),
                        summary.eventCount(), summary.inputTokens(), summary.outputTokens(), summary.totalTokens()));
            } catch (DuplicateKeyException alreadyStored) {
                // A completed run is immutable. Retrying the import preserves the first committed copy.
                return;
            }
            if (run.events().isEmpty()) return;
            var rows = new ArrayList<AgentRunRows.Event>(run.events().size());
            for (var event : run.events()) {
                rows.add(new AgentRunRows.Event(summary.id(), rows.size(), event.kind(), event.name(), event.provider(), event.model(),
                        event.status(), LocalDateTime.ofInstant(event.startedAt(), ZoneOffset.UTC), event.elapsedMs(),
                        event.inputTokens(), event.outputTokens(), event.totalTokens(), event.reason()));
            }
            for (int offset = 0; offset < rows.size(); offset += 100) {
                runs.insertEvents(rows.subList(offset, Math.min(offset + 100, rows.size())));
            }
        });
    }

    @Override
    public List<AgentRun.Summary> recent(String ownerId, int limit) {
        return runs.recent(ownerId, limit).stream().map(MysqlAgentRunRepository::summary).toList();
    }

    @Override
    public Optional<AgentRun> find(String id) {
        if (id == null || !id.matches("[0-9a-f]{32}")) return Optional.empty();
        return Optional.ofNullable(runs.find(id)).map(row -> new AgentRun(summary(row),
                runs.events(id).stream().map(MysqlAgentRunRepository::event).toList()));
    }

    private static AgentRun.Summary summary(AgentRunRows.Summary row) {
        return new AgentRun.Summary(row.id(), row.ownerId(), row.kind(), row.taskId(), row.parentRunId(), row.status(),
                row.startedAt().toInstant(ZoneOffset.UTC), row.elapsedMs(), row.eventCount(),
                row.inputTokens(), row.outputTokens(), row.totalTokens());
    }

    private static AgentRun.Event event(AgentRunRows.Event row) {
        return new AgentRun.Event(row.kind(), row.name(), row.providerId(), row.modelName(), row.status(),
                row.startedAt().toInstant(ZoneOffset.UTC), row.elapsedMs(),
                row.inputTokens(), row.outputTokens(), row.totalTokens(), row.reason());
    }
}
