package com.tianaiagent.config;

import com.tianaiagent.domain.agent.service.AgentRunContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncTaskConfig {

    @Bean("aiTaskExecutor")
    public Executor aiTaskExecutor(
            @Value("${agent.task-center.core-pool-size:4}") int corePoolSize,
            @Value("${agent.task-center.max-pool-size:12}") int maxPoolSize,
            @Value("${agent.task-center.queue-capacity:200}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix("ai-task-");
        executor.setTaskDecorator(AgentRunContext::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Agent 工具调用专用线程池。
     * 与后台长任务线程池隔离，避免文档处理等任务阻塞实时工具调用。
     */
    @Bean("toolGatewayExecutor")
    public AsyncTaskExecutor toolGatewayExecutor(
            @Value("${agent.tool-gateway.max-concurrency:8}") int maxConcurrency) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(maxConcurrency);
        executor.setMaxPoolSize(maxConcurrency);
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("tool-gateway-worker-");
        executor.setTaskDecorator(AgentRunContext::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);  // 应用关闭时等待线程池执行时间
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }

    /** Agent 流式问答使用独立线程池，避免与后台解析任务互相阻塞。 */
    @Bean("agentStreamExecutor")
    public AsyncTaskExecutor agentStreamExecutor(
            @Value("${agent.task-center.max-pool-size:12}") int maxStreams) {
        return fixedExecutor(maxStreams, 100, "agent-stream-");
    }

    /** 工作流中无依赖步骤的并行调度线程池。 */
    @Bean("workflowStepExecutor")
    public AsyncTaskExecutor workflowStepExecutor(
            @Value("${agent.workflow.max-parallel-steps:4}") int maxParallelSteps) {
        return fixedExecutor(maxParallelSteps, 100, "workflow-step-");
    }

    @Bean("wikiSegmentExecutor")
    public AsyncTaskExecutor wikiSegmentExecutor(
            @Value("${knowledge-base.wiki.max-parallel-segments:3}") int maxParallelSegments) {
        return fixedExecutor(maxParallelSegments, 100, "wiki-segment-");
    }

    /** 单轮 ReAct 中多个只读工具调用的并行调度线程池。 */
    @Bean("parallelToolCallExecutor")
    public AsyncTaskExecutor parallelToolCallExecutor(
            @Value("${agent.tool-gateway.max-concurrency:8}") int maxConcurrency) {
        return fixedExecutor(maxConcurrency, 100, "parallel-tool-call-");
    }

    private AsyncTaskExecutor fixedExecutor(int poolSize, int queueCapacity, String threadNamePrefix) {
        int size = Math.max(1, poolSize);
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(queueCapacity);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setTaskDecorator(AgentRunContext::wrap);
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(60);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.initialize();
        return executor;
    }
}
