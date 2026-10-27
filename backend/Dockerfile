FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes classes
COPY --from=build /app/target/lib lib
# OpenTelemetry javaagent: traces for the AWS SDK (SQS/DynamoDB) and the downstream HTTP call, no code change.
# Traces go OTLP -> Jaeger; metrics come from the /prom scrape instead (OTEL_METRICS_EXPORTER=none).
ADD --chmod=0644 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.14.0/opentelemetry-javaagent.jar otel.jar
USER 1000
ENV JAVA_TOOL_OPTIONS=-javaagent:/app/otel.jar
HEALTHCHECK --interval=30s --timeout=3s CMD bash -c 'echo > /dev/tcp/127.0.0.1/${PORT:-8080}'
# JDK 21 pins a carrier while a virtual thread blocks in the SDK HTTP client; with 200 permits the default pool (one carrier per CPU) starves
CMD ["java", "-Djdk.virtualThreadScheduler.parallelism=256", "-Djdk.virtualThreadScheduler.maxPoolSize=512", "-cp", "classes:lib/*", "com.caseware.fanout.Main"]
