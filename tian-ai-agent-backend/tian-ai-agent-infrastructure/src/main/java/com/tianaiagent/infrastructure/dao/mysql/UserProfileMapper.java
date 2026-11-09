package com.tianaiagent.infrastructure.dao.mysql;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.tianaiagent.infrastructure.dao.po.UserProfileRow;
import org.apache.ibatis.annotations.Insert;

public interface UserProfileMapper extends BaseMapper<UserProfileRow> {
    @Insert("""
            INSERT INTO agent_account_profile(owner_id,facts_json,preferences_json)
            VALUES (#{ownerId},#{factsJson},#{preferencesJson})
            ON DUPLICATE KEY UPDATE facts_json=JSON_MERGE_PATCH(facts_json, #{factsJson}),
              preferences_json=JSON_MERGE_PATCH(preferences_json, #{preferencesJson}), updated_at=UTC_TIMESTAMP(6)
            """)
    int merge(UserProfileRow profile);
}
