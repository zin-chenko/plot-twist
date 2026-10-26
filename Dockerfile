FROM maven:3.9.11-eclipse-temurin-25@sha256:407c4423cec0cf2981055bc2c6c0dc211d9605b6669279b95997f2d1c7e91e2c AS build
WORKDIR /build
ARG MODULE
RUN --mount=type=bind,source=pom.xml,target=/build/pom.xml,readonly \
    --mount=type=bind,source=auth-service/pom.xml,target=/build/auth-service/pom.xml,readonly \
    --mount=type=bind,source=api-service/pom.xml,target=/build/api-service/pom.xml,readonly \
    --mount=type=bind,source=domain-service/pom.xml,target=/build/domain-service/pom.xml,readonly \
    --mount=type=bind,source=${MODULE}/src,target=/project-src,readonly \
    --mount=type=bind,source=docker/java/Healthcheck.java,target=/Healthcheck.java,readonly \
    --mount=type=cache,target=/root/.m2,sharing=locked \
    --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml \
    --mount=type=secret,id=java_cacerts,target=/opt/java/openjdk/lib/security/cacerts \
    cp -a /project-src "${MODULE}/src" && \
    mvn -B -pl "${MODULE}" -am package && cp "${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar" /app.jar && javac -d /health /Healthcheck.java

FROM eclipse-temurin:25-jre-jammy@sha256:e0d8c47eccd7ec82d635542dfcd1f2b1293add6bdd7ffbeb650f8ec1b1335425
RUN groupadd --gid 999 app && useradd --uid 999 --gid app --no-create-home app
WORKDIR /app
RUN mkdir /app/keys && chown app:app /app/keys
COPY --from=build --chown=app:app /app.jar app.jar
COPY --from=build /health/Healthcheck.class /app/Healthcheck.class
USER app
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=20 CMD ["java", "-Xms8m", "-Xmx16m", "-XX:ActiveProcessorCount=1", "-XX:+UseSerialGC", "-cp", "/app", "Healthcheck"]
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=65", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/app.jar"]
