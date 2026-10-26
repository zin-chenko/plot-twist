FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY story-service ./story-service
COPY character-service ./character-service
ARG MODULE
RUN --mount=type=cache,target=/root/.m2 --mount=type=secret,id=maven_settings,target=/root/.m2/settings.xml --mount=type=secret,id=java_cacerts,target=/opt/java/openjdk/lib/security/cacerts mvn -B -pl "${MODULE}" -am package && cp "${MODULE}/target/${MODULE}-0.0.1-SNAPSHOT.jar" /app.jar

FROM eclipse-temurin:21-jre-jammy
RUN groupadd --system app && useradd --system --gid app app
WORKDIR /app
COPY --from=build --chown=app:app /app.jar app.jar
USER app
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=3s --start-period=30s --retries=20 CMD curl --fail --silent http://localhost:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
