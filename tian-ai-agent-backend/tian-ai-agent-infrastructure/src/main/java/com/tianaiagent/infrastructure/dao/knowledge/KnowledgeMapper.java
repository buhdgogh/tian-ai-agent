package com.tianaiagent.infrastructure.dao.knowledge;

import com.tianaiagent.infrastructure.dao.po.KnowledgeRows;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

public interface KnowledgeMapper {
    @Insert("MERGE INTO parse_tasks(task_id,payload,updated_at) KEY(task_id) VALUES (#{taskId},#{payload},#{updatedAt})")
    int saveTask(@Param("taskId") String taskId, @Param("payload") String payload, @Param("updatedAt") String updatedAt);

    @Select("SELECT payload FROM parse_tasks WHERE task_id=#{taskId}")
    String task(@Param("taskId") String taskId);

    @Select("SELECT payload FROM parse_tasks WHERE payload LIKE #{pattern}")
    List<String> tasksMatching(@Param("pattern") String pattern);

    @Insert("MERGE INTO knowledge_spaces(space_id,name,medical) KEY(space_id) VALUES (#{spaceId},#{name},#{medical})")
    int seedSpace(@Param("spaceId") String spaceId, @Param("name") String name, @Param("medical") boolean medical);

    @Insert("INSERT INTO knowledge_spaces(space_id,name,medical) VALUES (#{spaceId},#{name},#{medical})")
    int insertSpace(@Param("spaceId") String spaceId, @Param("name") String name, @Param("medical") boolean medical);

    @Select("SELECT space_id,name,medical,deleting FROM knowledge_spaces WHERE space_id=#{spaceId}")
    KnowledgeRows.Space space(@Param("spaceId") String spaceId);

    @Select("SELECT space_id,name,medical,deleting FROM knowledge_spaces ORDER BY medical DESC,name,space_id")
    List<KnowledgeRows.Space> spaces();

    @Update("UPDATE knowledge_spaces SET deleting=TRUE WHERE space_id=#{spaceId}")
    int markSpaceDeleting(@Param("spaceId") String spaceId);

    @Delete("DELETE FROM knowledge_spaces WHERE space_id=#{spaceId}")
    int deleteSpace(@Param("spaceId") String spaceId);

    @Delete("DELETE FROM document_deletions WHERE space_id=#{spaceId}")
    int deleteSpaceMarkers(@Param("spaceId") String spaceId);

    @Select("""
            SELECT d.doc_id,d.title,d.updated_at,d.metadata_json FROM documents d
            WHERE d.space_id=#{spaceId} AND NOT EXISTS (
                SELECT 1 FROM document_deletions x WHERE x.space_id=d.space_id AND x.doc_id=d.doc_id)
            ORDER BY d.doc_id LIMIT #{limit}
            """)
    List<KnowledgeRows.Source> wikiSources(@Param("spaceId") String spaceId, @Param("limit") int limit);

    @Select("""
            SELECT space_id FROM documents WHERE doc_id=#{docId}
            UNION SELECT space_id FROM document_deletions WHERE doc_id=#{docId}
            """)
    List<String> documentSpaces(@Param("docId") String docId);

    @Delete("DELETE FROM chunks WHERE space_id=#{spaceId}")
    int deleteSpaceChunks(@Param("spaceId") String spaceId);

    @Delete("DELETE FROM chunks WHERE space_id=#{spaceId} AND doc_id=#{docId}")
    int deleteChunks(@Param("spaceId") String spaceId, @Param("docId") String docId);

    @Delete("DELETE FROM documents WHERE space_id=#{spaceId}")
    int deleteSpaceDocuments(@Param("spaceId") String spaceId);

    @Delete("DELETE FROM documents WHERE space_id=#{spaceId} AND doc_id=#{docId}")
    int deleteDocument(@Param("spaceId") String spaceId, @Param("docId") String docId);

    @Insert("""
            INSERT INTO documents(doc_id,title,source_uri,content_sha256,metadata_json,updated_at,space_id)
            VALUES (#{docId},#{title},#{sourceUri},#{contentSha256},#{metadataJson},#{updatedAt},#{spaceId})
            """)
    int insertDocument(KnowledgeRows.Document document);

    @Insert("""
            INSERT INTO chunks(node_id,doc_id,parent_id,level_no,leaf,ordinal_no,title,source_uri,
                content_sha256,chunk_text,metadata_json,vector,space_id)
            VALUES (#{nodeId},#{docId},#{parentId},#{levelNo},#{leaf},#{ordinalNo},#{title},#{sourceUri},
                #{contentSha256},#{chunkText},#{metadataJson},#{vector},#{spaceId})
            """)
    int insertChunk(KnowledgeRows.Chunk chunk);

    @Insert("MERGE INTO document_deletions(doc_id,space_id) KEY(doc_id) VALUES (#{docId},#{spaceId})")
    int markDeleted(@Param("spaceId") String spaceId, @Param("docId") String docId);

    @Select("SELECT COUNT(*) FROM document_deletions WHERE space_id=#{spaceId} AND doc_id=#{docId}")
    int countDeleted(@Param("spaceId") String spaceId, @Param("docId") String docId);

    @Select("SELECT * FROM chunks WHERE space_id=#{spaceId} AND leaf=TRUE")
    List<KnowledgeRows.Chunk> leafChunks(@Param("spaceId") String spaceId);

    @Select("SELECT * FROM chunks WHERE space_id=#{spaceId} AND doc_id=#{docId} ORDER BY level_no,ordinal_no")
    List<KnowledgeRows.Chunk> documentChunks(@Param("spaceId") String spaceId, @Param("docId") String docId);

    @Select("SELECT * FROM chunks WHERE space_id=#{spaceId} AND node_id=#{nodeId}")
    KnowledgeRows.Chunk chunk(@Param("spaceId") String spaceId, @Param("nodeId") String nodeId);

    @Insert("""
            MERGE INTO semantic_memories(memory_id,owner_id,memory_text,metadata_json,vector,updated_at)
            KEY(memory_id) VALUES (#{memoryId},#{ownerId},#{memoryText},#{metadataJson},#{vector},#{updatedAt})
            """)
    int saveMemory(KnowledgeRows.Memory memory);

    @Select("SELECT * FROM semantic_memories WHERE owner_id=#{ownerId}")
    List<KnowledgeRows.Memory> memories(@Param("ownerId") String ownerId);

    @Delete("DELETE FROM semantic_memories WHERE owner_id=#{ownerId} AND memory_id=#{memoryId}")
    int deleteMemory(@Param("ownerId") String ownerId, @Param("memoryId") String memoryId);

    @Delete("DELETE FROM semantic_memories WHERE owner_id=#{ownerId}")
    int deleteMemories(@Param("ownerId") String ownerId);

    @Select("SELECT COUNT(*) FROM documents WHERE space_id=#{spaceId}")
    int countDocuments(@Param("spaceId") String spaceId);

    @Select("""
            <script>
            SELECT COUNT(*) FROM chunks WHERE space_id=#{spaceId}
            <if test="leavesOnly">AND leaf=TRUE</if>
            </script>
            """)
    int countChunks(@Param("spaceId") String spaceId, @Param("leavesOnly") boolean leavesOnly);
}
