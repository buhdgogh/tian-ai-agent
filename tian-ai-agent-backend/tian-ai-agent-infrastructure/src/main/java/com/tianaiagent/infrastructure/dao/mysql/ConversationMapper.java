package com.tianaiagent.infrastructure.dao.mysql;

import com.tianaiagent.infrastructure.dao.po.ConversationRows;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

public interface ConversationMapper {
    @Insert("""
            INSERT INTO agent_conversation(owner_id,memory_space,scope_id,storage_id,title)
            VALUES (#{ownerId},#{space},#{scopeId},#{storageId},'') ON DUPLICATE KEY UPDATE id=id
            """)
    int initialize(@Param("ownerId") String ownerId, @Param("space") String space,
                   @Param("scopeId") String scopeId, @Param("storageId") String storageId);

    @Select("SELECT id FROM agent_conversation WHERE memory_space=#{space} AND storage_id=#{storageId} FOR UPDATE")
    long lock(@Param("space") String space, @Param("storageId") String storageId);

    @Select("SELECT id FROM agent_conversation_message WHERE conversation_id=#{id} LIMIT 1")
    Long firstMessageId(@Param("id") long id);

    @Insert("""
            INSERT INTO agent_conversation_message(conversation_id,role,content,tool_calls,tool_responses)
            VALUES (#{id},#{message.role},#{message.content},#{message.toolCalls},#{message.toolResponses})
            """)
    int insertMessage(@Param("id") long id, @Param("message") ConversationRows.Message message);

    @Update("""
            UPDATE agent_conversation SET title=CASE WHEN title='' THEN #{title} ELSE title END,
              updated_at=UTC_TIMESTAMP(6) WHERE id=#{id}
            """)
    int updateAfterAppend(@Param("id") long id, @Param("title") String title);

    @Update("UPDATE agent_conversation SET title=#{title},created_at=#{time},updated_at=#{time} WHERE id=#{id}")
    int updateAfterImport(@Param("id") long id, @Param("title") String title, @Param("time") LocalDateTime time);

    @Select("""
            <script>
            SELECT m.role,m.content,m.tool_calls,m.tool_responses FROM agent_conversation_message m
            JOIN agent_conversation c ON c.id=m.conversation_id
            WHERE c.memory_space=#{space} AND c.storage_id=#{storageId} ORDER BY m.id
            <if test="limit != null">DESC LIMIT #{limit}</if>
            </script>
            """)
    List<ConversationRows.Message> messages(@Param("space") String space, @Param("storageId") String storageId,
                                            @Param("limit") Integer limit);

    @Select("""
            <script>
            SELECT storage_id,title,updated_at FROM agent_conversation WHERE memory_space=#{space}
            <if test="ownerId != null">AND owner_id=#{ownerId} AND scope_id=#{scopeId}</if>
            ORDER BY updated_at DESC,id DESC
            </script>
            """)
    List<ConversationRows.Summary> list(@Param("space") String space, @Param("ownerId") String ownerId,
                                       @Param("scopeId") String scopeId);

    @Update("""
            UPDATE agent_conversation SET updated_at=UTC_TIMESTAMP(6)
            WHERE memory_space=#{space} AND storage_id=#{storageId}
            """)
    int touch(@Param("space") String space, @Param("storageId") String storageId);

    @Delete("DELETE FROM agent_conversation WHERE id=#{id}")
    int delete(@Param("id") long id);
}
