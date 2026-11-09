package com.tianaiagent.domain.agent.model.valobj;

/**
 * 描述：代理执行状态得枚举类
 */
public enum AgentState {
    /** 空闲状 **/
    IDLE,

    /** 运行中状态 **/
    RUNNING,

    /** 已完成状态 **/
    FINISHED,

    /** 错误状态 **/
    ERROR
}
