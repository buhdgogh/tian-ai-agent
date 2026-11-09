package com.tianaiagent.infrastructure.dao.mysql;

import com.tianaiagent.infrastructure.dao.po.McpServerRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface McpServerMapper {
    @Select("SELECT * FROM agent_mcp_server WHERE owner_id=#{ownerId} ORDER BY server_id")
    List<McpServerRow> list(@Param("ownerId") String ownerId);

    @Select("SELECT * FROM agent_mcp_server WHERE owner_id=#{ownerId} AND server_id=#{id}")
    McpServerRow find(@Param("ownerId") String ownerId, @Param("id") String id);

    @Insert("""
            INSERT INTO agent_mcp_server(owner_id, server_id, endpoint_url, token_ciphertext, enabled, tools_json)
            VALUES (#{ownerId}, #{serverId}, #{endpointUrl}, #{tokenCiphertext}, #{enabled}, #{toolsJson})
            ON DUPLICATE KEY UPDATE endpoint_url=#{endpointUrl}, token_ciphertext=#{tokenCiphertext},
                enabled=#{enabled}, tools_json=#{toolsJson}
            """)
    int save(McpServerRow server);

    @Delete("DELETE FROM agent_mcp_server WHERE owner_id=#{ownerId} AND server_id=#{id}")
    int delete(@Param("ownerId") String ownerId, @Param("id") String id);
}
