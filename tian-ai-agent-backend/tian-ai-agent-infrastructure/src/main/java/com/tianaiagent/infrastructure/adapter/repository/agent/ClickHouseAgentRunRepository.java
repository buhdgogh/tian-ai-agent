package com.tianaiagent.infrastructure.adapter.repository.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tianaiagent.domain.agent.adapter.repository.AgentRunRepository;
import com.tianaiagent.domain.agent.model.entity.AgentRun;
import com.tianaiagent.domain.agent.model.entity.ToolAuditRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** One insert per completed run, with sequence zero holding the run summary. */
public final class ClickHouseAgentRunRepository implements AgentRunRepository {
    private static final Logger LOG = LoggerFactory.getLogger(ClickHouseAgentRunRepository.class);
    private static final String TABLE = "agent_trace_spans";
    private static final String SCHEMA = """
            CREATE TABLE IF NOT EXISTS agent_trace_spans (
                owner_id String, trace_id String, run_id String, parent_run_id String,
                sequence UInt32, kind LowCardinality(String), name String,
                status LowCardinality(String), started_ms UInt64, elapsed_ms UInt64,
                input_tokens Nullable(UInt64), output_tokens Nullable(UInt64), total_tokens Nullable(UInt64),
                provider String, model String, call_id String, argument_digest String, payload String
            ) ENGINE = ReplacingMergeTree ORDER BY (owner_id, run_id, sequence)
            """;
    private final URI endpoint;
    private final String database;
    private final String username;
    private final String password;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final FileAgentRunRepository pending;
    private final AgentRunRepository previous;
    private volatile boolean schemaReady;

    public ClickHouseAgentRunRepository(URI endpoint, String database, String username, String password,
                                        ObjectMapper mapper, AgentRunRepository previous) {
        if (!List.of("http", "https").contains(endpoint.getScheme()) || endpoint.getHost() == null
                || endpoint.getUserInfo() != null || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("ClickHouse HTTP 地址无效");
        }
        this.endpoint = endpoint;
        this.database = database;
        this.username = username;
        this.password = password;
        this.mapper = mapper;
        String target = UUID.nameUUIDFromBytes((endpoint + "\n" + database + "\n" + username)
                .getBytes(StandardCharsets.UTF_8)).toString();
        this.pending = new FileAgentRunRepository(mapper, "agent-trace-pending/" + target);
        this.previous = previous;
    }

    @Override
    public void save(AgentRun run) {
        pending.save(run);
        try {
            flush(run);
        } catch (RuntimeException | IOException e) {
            LOG.warn("ClickHouse 审计写入待重试 runId={} failure={}", run.summary().id(),
                    e.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelayString = "${agent.persistence.clickhouse.retry-ms:30000}")
    public void retry() {
        try {
            for (AgentRun run : pending.pending(100)) flush(run);
        } catch (RuntimeException | IOException e) {
            LOG.debug("ClickHouse 审计重试未完成 failure={}", e.getClass().getSimpleName());
        }
    }

    private void flush(AgentRun run) throws IOException {
        ensureSchema();
        var summary = run.summary();
        var rows = new StringBuilder("INSERT INTO ").append(TABLE).append(" FORMAT JSONEachRow\n");
        append(rows, summary, 0, "RUN", summary.kind(), summary.status(), summary.startedAt().toEpochMilli(),
                summary.elapsedMs(), summary.inputTokens(), summary.outputTokens(), summary.totalTokens(),
                null, null, null, null, summary);
        int sequence = 0;
        for (var event : run.events()) {
            append(rows, summary, ++sequence, event.kind(), event.name(), event.status(), event.startedAt().toEpochMilli(),
                    event.elapsedMs(), event.inputTokens(), event.outputTokens(), event.totalTokens(),
                    event.provider(), event.model(), event.callId(), event.argumentDigest(), event);
        }
        request(rows.toString(), Map.of());
        pending.delete(summary.id());
    }

    private void append(StringBuilder rows, AgentRun.Summary run, int sequence, String kind, String name,
                        String status, long started, long elapsed, Number input, Number output, Number total,
                        String provider, String model, String callId, String digest, Object payload)
            throws JsonProcessingException {
        var row = mapper.createObjectNode()
                .put("owner_id", run.ownerId()).put("trace_id", run.traceId()).put("run_id", run.id())
                .put("parent_run_id", empty(run.parentRunId())).put("sequence", sequence)
                .put("kind", kind).put("name", empty(name)).put("status", status)
                .put("started_ms", Math.max(0, started)).put("elapsed_ms", elapsed)
                .put("provider", empty(provider)).put("model", empty(model))
                .put("call_id", empty(callId)).put("argument_digest", empty(digest))
                .put("payload", mapper.writeValueAsString(payload));
        row.set("input_tokens", mapper.valueToTree(input));
        row.set("output_tokens", mapper.valueToTree(output));
        row.set("total_tokens", mapper.valueToTree(total));
        rows.append(mapper.writeValueAsString(row)).append('\n');
    }

    private static String empty(String value) { return value == null ? "" : value; }

    @Override
    public List<AgentRun.Summary> recent(String ownerId, int limit) {
        var rows = new LinkedHashMap<String, AgentRun.Summary>();
        readRecent(ownerId, limit).forEach(row -> rows.put(row.id(), row));
        pending.recent(ownerId, limit).forEach(row -> rows.putIfAbsent(row.id(), row));
        previous.recent(ownerId, limit).forEach(row -> rows.putIfAbsent(row.id(), row));
        return rows.values().stream().sorted(Comparator.comparing(AgentRun.Summary::startedAt).reversed())
                .limit(limit).toList();
    }

    private List<AgentRun.Summary> readRecent(String ownerId, int limit) {
        try {
            return read("""
                    SELECT payload FROM agent_trace_spans FINAL
                    WHERE owner_id = {owner:String} AND sequence = 0 ORDER BY started_ms DESC, run_id DESC
                    LIMIT {limit:UInt32} FORMAT JSONEachRow
                    """, Map.of("owner", ownerId, "limit", String.valueOf(limit)), AgentRun.Summary.class);
        } catch (IllegalStateException e) {
            return List.of();
        }
    }

    @Override
    public List<AgentRun.Summary> trace(String ownerId, String traceId) {
        var rows = new LinkedHashMap<String, AgentRun.Summary>();
        try {
            read("""
                    SELECT payload FROM agent_trace_spans FINAL
                    WHERE owner_id = {owner:String} AND trace_id = {trace:String} AND sequence = 0
                    ORDER BY started_ms, run_id LIMIT 1000 FORMAT JSONEachRow
                    """, Map.of("owner", ownerId, "trace", traceId), AgentRun.Summary.class)
                    .forEach(run -> rows.put(run.id(), run));
        } catch (IllegalStateException e) {
            LOG.debug("ClickHouse Trace 读取暂不可用");
        }
        pending.trace(ownerId, traceId).forEach(run -> rows.putIfAbsent(run.id(), run));
        previous.trace(ownerId, traceId).forEach(run -> rows.putIfAbsent(run.id(), run));
        return rows.values().stream().sorted(Comparator.comparing(AgentRun.Summary::startedAt)).toList();
    }

    @Override
    public Optional<AgentRun> find(String id) {
        try {
            ensureSchema();
            String result = request("""
                    SELECT sequence, payload FROM agent_trace_spans FINAL WHERE run_id = {id:String}
                    ORDER BY sequence FORMAT JSONEachRow
                    """, Map.of("id", id));
            var payloads = result.lines().filter(line -> !line.isBlank()).map(this::payload).toList();
            if (!payloads.isEmpty()) {
                return Optional.of(new AgentRun(decode(payloads.getFirst(), AgentRun.Summary.class),
                        payloads.stream().skip(1).map(value -> decode(value, AgentRun.Event.class)).toList()));
            }
        } catch (IllegalStateException e) {
            LOG.debug("ClickHouse 审计读取暂不可用");
        }
        return pending.find(id).or(() -> previous.find(id));
    }

    @Override
    public Optional<List<ToolAuditRecord>> recentTools(int limit) {
        List<AgentRun.Event> stored;
        try {
            stored = read("""
                    SELECT payload FROM agent_trace_spans FINAL WHERE kind = 'TOOL'
                    ORDER BY started_ms DESC LIMIT {limit:UInt32} FORMAT JSONEachRow
                    """, Map.of("limit", String.valueOf(limit)), AgentRun.Event.class);
        } catch (IllegalStateException e) {
            stored = List.of();
        }
        try {
            var queued = pending.pending(100).stream().flatMap(run -> run.events().stream());
            return Optional.of(Stream.concat(stored.stream(), queued).filter(event -> event.callId() != null)
                    .sorted(Comparator.comparing(AgentRun.Event::startedAt).reversed())
                    .map(event -> new ToolAuditRecord(event.callId(), event.name(), event.status(),
                            event.elapsedMs(), event.argumentDigest(), event.reason(), event.startedAt().toString()))
                    .distinct().limit(limit).toList());
        } catch (IOException e) {
            throw new IllegalStateException("无法读取待提交审计", e);
        }
    }

    private <T> List<T> read(String query, Map<String, String> parameters, Class<T> type) {
        ensureSchema();
        return request(query, parameters).lines().filter(line -> !line.isBlank())
                .map(this::payload).map(value -> decode(value, type)).toList();
    }

    private String payload(String line) { return decode(line, com.fasterxml.jackson.databind.JsonNode.class).path("payload").asText(); }

    private <T> T decode(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("ClickHouse 审计数据格式无效", e);
        }
    }

    private synchronized void ensureSchema() {
        if (schemaReady) return;
        request(SCHEMA, Map.of());
        schemaReady = true;
    }

    private String request(String query, Map<String, String> parameters) {
        var url = new StringBuilder(endpoint.toString()).append("?wait_end_of_query=1&database=").append(encode(database));
        parameters.forEach((key, value) -> url.append("&param_").append(key).append('=').append(encode(value)));
        var request = HttpRequest.newBuilder(URI.create(url.toString())).timeout(Duration.ofSeconds(3))
                .header("X-ClickHouse-User", username).header("Content-Type", "text/plain; charset=utf-8");
        if (!password.isEmpty()) request.header("X-ClickHouse-Key", password);
        try {
            var response = client.send(request.POST(HttpRequest.BodyPublishers.ofString(query)).build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw new IllegalStateException("ClickHouse HTTP " + response.statusCode());
            return response.body();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ClickHouse 审计请求已中断", e);
        } catch (IOException e) {
            throw new IllegalStateException("ClickHouse 审计服务不可达", e);
        }
    }

    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
}
