FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /build
ARG MODULE
RUN --mount=type=bind,source=pom.xml,target=/build/pom.xml,readonly \
    --mount=type=bind,source=.mvn,target=/build/.mvn,readonly \
    --mount=type=bind,source=moderation-contract,target=/contract-src,readonly \
    --mount=type=bind,source=moderation-service/pom.xml,target=/build/moderation-service/pom.xml,readonly \
    --mount=type=bind,source=auth-service/pom.xml,target=/build/auth-service/pom.xml,readonly \
    --mount=type=bind,source=api-service/pom.xml,target=/build/api-service/pom.xml,readonly \
    --mount=type=bind,source=domain-service/pom.xml,target=/build/domain-service/pom.xml,readonly \
    --mount=type=bind,source=${MODULE}/src,target=/project-src,readonly \
    --mount=type=bind,source=docker/java/Healthcheck.java,target=/Healthcheck.java,readonly \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java_cacerts,target=/opt/java/openjdk/lib/security/cacerts \
    mkdir -p moderation-contract && cp /contract-src/pom.xml moderation-contract/ && cp -a /contract-src/src moderation-contract/ && \
    cp -a /project-src "${MODULE}/src" && \
    mvn -e -B -pl "${MODULE}" -am package && cp "${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar" /app.jar && javac -d /health /Healthcheck.java

# Separate integration runner: only PostgreSQL, RabbitMQ and moderation worker are required.
FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS moderation-tests
WORKDIR /workspace
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*
COPY pom.xml ./
COPY .mvn ./.mvn
COPY auth-service/pom.xml ./auth-service/pom.xml
COPY api-service/pom.xml ./api-service/pom.xml
COPY moderation-service/pom.xml ./moderation-service/pom.xml
COPY moderation-contract ./moderation-contract
COPY domain-service ./domain-service
ENTRYPOINT ["mvn", "-B", "-pl", "domain-service", "-am", "test"]

FROM eclipse-temurin:25-jre-jammy@sha256:e0d8c47eccd7ec82d635542dfcd1f2b1293add6bdd7ffbeb650f8ec1b1335425
ARG MODULE
RUN if [ "$MODULE" = "domain-service" ]; then apt-get update && apt-get install -y --no-install-recommends ffmpeg && rm -rf /var/lib/apt/lists/*; fi
RUN groupadd --gid 999 app && useradd --uid 999 --gid app --no-create-home app
WORKDIR /app
RUN mkdir /app/keys && chown app:app /app/keys
COPY --from=build --chown=app:app /app.jar app.jar
COPY --from=build /health/Healthcheck.class /app/Healthcheck.class
USER app
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=20 CMD ["java", "-Xms8m", "-Xmx16m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-cp", "/app", "Healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
