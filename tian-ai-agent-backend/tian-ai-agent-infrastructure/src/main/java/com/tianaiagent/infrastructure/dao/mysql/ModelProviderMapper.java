package com.tianaiagent.infrastructure.dao.mysql;

import com.tianaiagent.infrastructure.dao.po.ModelProviderRow;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface ModelProviderMapper {
    @Select("SELECT owner_id FROM agent_model_settings")
    List<String> initializedOwners();

    @Insert("INSERT IGNORE INTO agent_model_settings(owner_id) VALUES (#{ownerId})")
    int initializeOwner(@Param("ownerId") String ownerId);

    @Insert("""
            INSERT INTO agent_model_settings(owner_id) VALUES (#{ownerId})
            ON DUPLICATE KEY UPDATE updated_at=UTC_TIMESTAMP(6)
            """)
    int touchOwner(@Param("ownerId") String ownerId);

    @Select("SELECT * FROM agent_model_provider ORDER BY owner_id, sort_order")
    List<ModelProviderRow> list();

    @Delete("DELETE FROM agent_model_provider WHERE owner_id=#{ownerId}")
    int deleteProviders(@Param("ownerId") String ownerId);

    @Insert("""
            INSERT INTO agent_model_provider(owner_id,provider_id,sort_order,protocol,base_url,model_name,
              api_key_ciphertext,priority,enabled,workspace_id,stream_usage,reasoning_effort)
            VALUES (#{ownerId},#{providerId},#{sortOrder},#{protocol},#{baseUrl},#{modelName},
              #{apiKeyCiphertext},#{priority},#{enabled},#{workspaceId},#{streamUsage},#{reasoningEffort})
            """)
    int insert(ModelProviderRow provider);
}
