import java.sql.*;
class WikiExistingCheck {
    public static void main(String[] args) throws Exception {
        String path = "E:/Code/Projects/Tian-AI-Agent-main/tian-ai-agent-backend/tian-doc-parse-index-mcp-server/data/knowledge";
        try (Connection db = DriverManager.getConnection("jdbc:h2:file:" + path + ";IFEXISTS=TRUE;ACCESS_MODE_DATA=r", "sa", "")) {
            try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME='WIKI_REVISIONS'")) {
                result.next();
                if (result.getInt(1) == 0) { System.out.println("Existing local database: no compiled Wiki revisions table"); return; }
            }
            try (var statement = db.createStatement(); var result = statement.executeQuery("SELECT COUNT(*) FROM wiki_revisions")) {
                result.next(); System.out.println("Existing local Wiki revisions: " + result.getLong(1));
            }
        }
    }
}
