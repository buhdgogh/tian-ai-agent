package com.tianaiagent.infrastructure.dao.mysql;

import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface ToolCredentialMapper {
    @Select("SELECT ciphertext FROM agent_tool_credential WHERE owner_id=#{ownerId} AND tool=#{tool}")
    CiphertextRow find(@Param("ownerId") String ownerId, @Param("tool") String tool);

    @Insert("""
            INSERT INTO agent_tool_credential(owner_id, tool, ciphertext)
            VALUES (#{ownerId}, #{tool}, #{ciphertext})
            ON DUPLICATE KEY UPDATE ciphertext=#{ciphertext}
            """)
    void save(@Param("ownerId") String ownerId, @Param("tool") String tool, @Param("ciphertext") byte[] ciphertext);

    @Delete("DELETE FROM agent_tool_credential WHERE owner_id=#{ownerId} AND tool=#{tool}")
    void delete(@Param("ownerId") String ownerId, @Param("tool") String tool);

    record CiphertextRow(byte[] ciphertext) {}
}
