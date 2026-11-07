import java.nio.file.*;
import java.sql.*;
import java.util.regex.*;

class AuditInspect {
    static String scalar(String source, String name) {
        var match = Pattern.compile("(?m)^\\s+" + name + ":\\s*(.+)$").matcher(source);
        if (!match.find()) throw new IllegalStateException("Missing database setting: " + name);
        return match.group(1).trim().replaceAll("^[\"']|[\"']$", "");
    }
    public static void main(String[] args) throws Exception {
        String config = Files.readString(Path.of(args[0]));
        var section = Pattern.compile("(?ms)^    mysql:\\R(.*?)(?=^  \\S|\\z)").matcher(config);
        if (!section.find()) throw new IllegalStateException("MySQL configuration not found");
        String db = section.group(1);
        try (var connection = DriverManager.getConnection(scalar(db, "jdbc-url"), scalar(db, "username"), scalar(db, "password"))) {
            connection.setReadOnly(true);
            String[] queries = {
                "SELECT status,COUNT(*) total,SUM(task_id IS NULL OR task_id='') missing_task,SUM(owner_id IS NULL OR owner_id='') missing_owner,SUM(chat_id IS NULL OR chat_id='') missing_chat,SUM(step_id IS NULL OR step_id='') missing_step,SUM(iteration IS NULL) missing_iteration,SUM(tool_call_id IS NULL OR tool_call_id='') missing_call,SUM(error_text IS NULL OR error_text='') missing_error FROM agent_tool_audit GROUP BY status",
                "SELECT tool_name,status,COUNT(*) total,SUM(owner_id IS NULL OR owner_id='') missing_owner,SUM(chat_id IS NULL OR chat_id='') missing_chat,SUM(tool_call_id IS NULL OR tool_call_id='') missing_call FROM agent_tool_audit GROUP BY tool_name,status ORDER BY total DESC LIMIT 14",
                "SELECT tool_name,status,task_id IS NULL AS no_task,owner_id IS NULL AS no_owner,chat_id IS NULL AS no_chat,step_id IS NULL AS no_step,iteration IS NULL AS no_iteration,tool_call_id IS NULL AS no_call,argument_digest IS NULL AS no_digest,created_at FROM agent_tool_audit ORDER BY created_at DESC LIMIT 10",
                "SELECT type,status,COUNT(*) total,MIN(created_at) first_created,MAX(created_at) last_created FROM agent_task GROUP BY type,status",
                "SELECT id,status,created_at,started_at,finished_at FROM agent_task WHERE type='AGENT_WORKFLOW' ORDER BY created_at DESC LIMIT 5",
                "SELECT t.id,JSON_LENGTH(t.result_json,'$.plan.steps') plan_steps,JSON_LENGTH(t.result_json,'$.executions') executed_steps,COUNT(a.id) audits_during_execution FROM agent_task t LEFT JOIN agent_tool_audit a ON a.created_at>=t.started_at AND a.created_at<=t.finished_at WHERE t.type='AGENT_WORKFLOW' GROUP BY t.id,t.result_json"
            };
            for (int index = 0; index < queries.length; index++) {
                System.out.println("Query " + (index + 1));
                try (var statement = connection.createStatement(); var rows = statement.executeQuery(queries[index])) {
                    var meta = rows.getMetaData();
                    for (int column = 1; column <= meta.getColumnCount(); column++) System.out.print((column > 1 ? " | " : "") + meta.getColumnLabel(column));
                    System.out.println();
                    while (rows.next()) {
                        for (int column = 1; column <= meta.getColumnCount(); column++) System.out.print((column > 1 ? " | " : "") + rows.getString(column));
                        System.out.println();
                    }
                }
            }
        }
    }
}
