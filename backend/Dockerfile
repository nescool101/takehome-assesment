FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
COPY src src
RUN mvn -q -DskipTests package dependency:copy-dependencies -DoutputDirectory=target/lib

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/target/classes classes
COPY --from=build /app/target/lib lib
# JDK 21 pins a carrier while a virtual thread blocks in the SDK HTTP client; with 200 permits the default pool (one carrier per CPU) starves
CMD ["java", "-Djdk.virtualThreadScheduler.parallelism=256", "-Djdk.virtualThreadScheduler.maxPoolSize=512", "-cp", "classes:lib/*", "com.caseware.fanout.Main"]
