package com.tianaiagent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("agent_account_profile")
public record UserProfileRow(@TableId(type = IdType.INPUT) String ownerId, String factsJson, String preferencesJson) {}
