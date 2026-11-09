package com.tianaiagent.infrastructure.dao.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

@TableName("agent_long_term_memory")
public record LegacyMemoryRow(@TableId(type = IdType.INPUT) String ownerId, String summaryText, String factsJson,
                              int sourceMessageCount, String updatedAt) {}
