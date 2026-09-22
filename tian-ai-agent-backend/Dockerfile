FROM maven:3.9-amazoncorretto-21 AS builder
WORKDIR /workspace

COPY pom.xml ./pom.xml
COPY tian-ai-agent-admin/pom.xml ./tian-ai-agent-admin/pom.xml
RUN mvn -pl tian-ai-agent-admin -am dependency:go-offline -DskipTests

COPY tian-ai-agent-admin/src ./tian-ai-agent-admin/src
RUN mvn -pl tian-ai-agent-admin -am package -DskipTests

FROM amazoncorretto:21-alpine
WORKDIR /app
RUN apk add --no-cache curl \
    && addgroup -S app \
    && adduser -S -G app -u 10001 app \
    && mkdir -p /app/tmp \
    && chown -R app:app /app
COPY --from=builder --chown=app:app /workspace/tmp/admin-build/tian-ai-agent-admin-0.0.1-SNAPSHOT.jar /app/app.jar

EXPOSE 8123
USER app
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8123/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
