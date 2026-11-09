FROM maven:3.9-amazoncorretto-21 AS builder
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY docs/mysql/tian_ai_agent.sql ./docs/mysql/
COPY tian-ai-agent-api ./tian-ai-agent-api
COPY tian-ai-agent-app ./tian-ai-agent-app
COPY tian-ai-agent-domain ./tian-ai-agent-domain
COPY tian-ai-agent-infrastructure ./tian-ai-agent-infrastructure
COPY tian-ai-agent-trigger ./tian-ai-agent-trigger
COPY tian-ai-agent-types ./tian-ai-agent-types
RUN mvn -B -pl tian-ai-agent-app -am clean package -DskipTests

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S -G app -u 10001 app \
    && mkdir -p /app/tmp /app/data /app/parsed /app/runtime \
    && chown -R app:app /app
COPY --from=builder --chown=app:app /workspace/tian-ai-agent-app/target/tian-ai-agent-backend.jar /app/app.jar

EXPOSE 8123
USER app
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8123/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
