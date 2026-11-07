package com.tianaiagent.agent;

import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;

/**
 * 描述：ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {
    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 思考结果（包含是否需要行动的逻辑）
     */
    public abstract String think();

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 是否需要继续执行行动
     * @return
     */
    public abstract boolean shouldAct();

    /**
     * 执行单个步骤：思考和行动
     *
     * @return 步骤执行结果
     */
    @Override
    public String step() {
        try {
            // 先思考
            String thought = think();
            checkCancelled();
            if (!shouldAct()) {
                return thought;
            }
            // 再行动
            String actionResult = act();
            return thought + "\n" + actionResult;
        } catch (java.util.concurrent.CancellationException cancelled) {
            throw cancelled;
        } catch (Exception e) {
            // 记录异常日志
            log.error("Step execution failed", e);
            return "步骤执行失败：" + e.getMessage();
        }
    }
}
