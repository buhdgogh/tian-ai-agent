# RabbitMQ 异步工具审计

MySQL 模式下，工具执行线程只构造并发布审计事件，不再调用 `MysqlToolAuditRepository.save()`。
生产者等待 Broker Confirm，不等待消费者完成数据库写入。消费者收到消息后写入原有的
`agent_tool_audit` 表；任务、画像的持久化方式以及审计查询接口保持不变。

## 覆盖范围

- SUCCESS：工具正常完成。
- FAILED：抛出异常、超时或已识别的业务失败（例如 `ok=false`）。
- BLOCKED / REJECTED：被工具黑名单拦截或参数超限，仍记录审计。
- `observeOperation()` 包装的 MCP/文档操作使用相同发送路径。

事件发送前捕获审计 ID、工具名、耗时、状态、原工具错误、参数哈希，以及
taskId / ownerId / chatId / stepId / iteration / toolCallId。消费线程不读取原线程的 ExecutionContext。
审计重试只重试数据库写入，不重新调用业务工具。

## 消息流程和重试次数

```text
工具执行结束 → RabbitMQ工作队列 → 写MySQL成功 → 手动ACK
                         │
                         └→ 写库失败 → 发布到重试队列并收到Confirm → ACK原消息
                                                │
                                       TTL到期后回到工作队列
                                                │
                                     3次重试后仍失败 → Reject(false) → 死信队列
```

“最多3次重试”定义为 **首次写库1次 + 重试最多3次，即最多4次计划内写库尝试**。
默认退避1秒、2秒、4秒。`audit-retry-count` 消息头随消息持久化，不依赖应用内存计数，
应用重启不会把已经发送到重试队列的重试计数清零。消费线程不 sleep 等待退避。

| 组件 | 默认名称 |
|---|---|
| 主交换机（Direct） | tian.tool-audit.v1 |
| 工作队列 | tian.tool-audit.write.v1 |
| 重试交换机 | tian.tool-audit.retry.v1 |
| 三个TTL重试队列 | tian.tool-audit.retry.v1.1 / .2 / .3 |
| 死信交换机 | tian.tool-audit.dead.v1 |
| 死信队列 | tian.tool-audit.dead.v1 |

交换机 durable=true，队列 durable=true 且不自动删除，审计和重试消息均为 PERSISTENT。
队列使用 quorum；工作和重试队列配置 `at-least-once` 死信转发及 `reject-publish` overflow，
降低死信转发时目标队列不可用造成的丢失风险。详见
[RabbitMQ quorum queue 文档](https://www.rabbitmq.com/docs/quorum-queues#dead-lettering)。
不要直接把同名的既有 classic 队列改为 quorum，RabbitMQ 不允许原地改变队列类型。
当前使用新的版本化队列名；修改TTL/不可变队列参数时也应使用新队列名并规划旧消息迁移。

## 生产者确认与消费者确认

生产者使用 `CORRELATED` Confirm、publisher returns 和 mandatory 路由。
每次发送有唯一 CorrelationData ID，消息 ID 则保持为原审计 ID。Confirm Future 返回 ACK 后，
还会检查 returned message；交换机ACK但消息无队列可路由，同样视为失败。
该行为参考 [Spring AMQP Confirm/Returns](https://docs.spring.io/spring-amqp/reference/amqp/template.html#template-confirms)。

- Broker NACK、不可路由、连接错误或确认超时：明确抛出审计发送异常并记录 auditId，不静默丢弃。
- SQL成功：等JDBC自动提交完成后才手动ACK。
- SQL失败但可重试：重试消息持久化投递且确认成功后，才ACK原消息。
- 重试投递未确认：NACK原消息并requeue，避免先ACK导致消息丢失。
- SQL失败且重试耗尽：Reject且requeue=false，转入死信队列。
- JSON损坏、版本不支持、事件ID不匹配或重试头非法：直接进入死信队列。

写库成功后、ACK之前发生崩溃会产生重复投递。MySQL以审计ID为主键，使用
`ON DUPLICATE KEY UPDATE id=id` 幂等插入，同一事件不会重复创建记录，也不覆盖原始审计结果。
Broker重连、ACK丢失、重试投递确认超时仍可能造成重复处理，不能把4次计划内尝试理解成全局恰好4次SQL。
工作队列额外设置 delivery-limit=4，限制异常requeue/崩溃引发的连续重投，耗尽也会死信化。

## 部署配置

直接修改 `tian-ai-agent-admin/src/main/resources/application.yml`，不使用环境变量占位符：

```yaml
spring:
  rabbitmq:
    host: localhost
    port: 5672
    username: tian_agent
    password: tian_agent_dev
    virtual-host: /

agent:
  tool-gateway:
    audit-rabbit:
      consumer-auto-startup: true
      max-retries: 3
      retry-delay-ms: 1000
      confirm-timeout-ms: 5000
```

`max-retries` 只允许0到3；基准退避时间可调整。
Compose通过启动参数 `--spring.profiles.active=docker` 加载 `application-docker.yml`，
将RabbitMQ主机切换为服务名rabbitmq，其余配置继承主配置。
Broker初始化账号直接配置在 `deploy/rabbitmq/rabbitmq.conf` 并只读挂载；修改账号密码时与主配置保持一致。
仓库中的账号密码是本地开发值。如果RabbitMQ已有持久卷，修改初始化配置不会自动修改既有账号密码。
Compose等待RabbitMQ健康检查通过；Broker使用 `rabbitmq_data` 命名卷保存消息、队列和元数据。
RabbitMQ容器hostname固定，避免重建容器时节点名变化导致持久数据不可见。

项目提供的是单节点持久化配置，不等于跨机器容灾。生产环境需要按可用性要求部署RabbitMQ集群。
消息持久化保证从Broker接收并确认之后开始；工具完成到成功发布之间的进程崩溃窗口仍存在，
本次没有引入Outbox。发送异常不能作为重复执行业务工具的依据，应按auditId补偿审计。

开发兼容模式 `AGENT_PERSISTENCE_BACKEND=file` 继续使用原内存审计，不注册本套MQ消费者。

## 排查和死信补偿

1. 查看RabbitMQ管理界面（Compose默认15672端口）的工作队列、重试队列、死信队列长度。
2. 用日志中的auditId关联事件；死信消息保留原始业务状态、错误及关联信息。
3. 排查MySQL连接、表结构、字段长度、权限等，修复后再人工重新投递死信到主交换机的write路由。
4. 重新投递应保留原messageId和body，使用持久化消息，并等待生产者确认后再确认死信原消息。
   如果希望重新给予3次重试，可显式把audit-retry-count重置为0；不要自动无限回灌死信。

死信队列不自动消费，不会因数据库持续故障而无限循环。
进入死信的记录尚未成功写入MySQL，所以 `/api/tool-gateway/audits` 查不到它；补偿成功后才可查询。

## 验证

常规测试使用模拟RabbitTemplate/Channel和H2，覆盖持久化属性、确认/NACK/不可路由/超时、
先写库后ACK、重试先确认后ACK、重试耗尽死信、重复投递幂等和所有工具业务状态。

真实Broker联调测试 `ToolAuditRabbitIntegrationTest` 默认关闭，避免无意连接用户服务。
准备独立RabbitMQ 4.x测试实例后执行。此独立联调测试仍通过RABBITMQ_*环境变量指定测试实例，
不影响应用直接读取YAML配置：

```powershell
$env:JAVA_HOME = 'D:/JDK/JDK21'
$env:TOOL_AUDIT_RABBIT_INTEGRATION = 'true'
mvn -f tian-ai-agent-admin/pom.xml test '-Dtest=ToolAuditRabbitIntegrationTest'
```

联调测试使用唯一前缀声明测试队列和交换机，结束后仅删除本次测试资源；MySQL写入使用H2替身。
它验证真实Broker投递、TTL重试和死信流转，不包含Broker断电恢复或生产MySQL验证。
