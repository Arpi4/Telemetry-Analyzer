# Build context: repository root (backend depends on the shared core module).
#   docker build -f backend/Dockerfile -t telemetry-backend .
FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app

COPY pom.xml .
COPY core/pom.xml core/
COPY backend/pom.xml backend/
COPY desktop/pom.xml desktop/
COPY core/src core/src
COPY backend/src backend/src
RUN mvn -q -B -pl backend -am -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/backend/target/*.jar app.jar

# Sized for a 512 MB / 0.1 CPU instance (Render free plan). The JVM default heap there is 128 MB,
# too small to parse a 30 MB MoTeC export next to the stored sessions; a fixed limit also keeps the
# process inside 512 MB if the container limit is not visible to the JVM. Override via the
# environment if the instance is larger.
ENV JAVA_TOOL_OPTIONS="-Xmx256m -Xss512k -XX:MaxMetaspaceSize=128m -XX:+UseSerialGC -XX:ActiveProcessorCount=1 -Djdk.nio.maxCachedBufferSize=262144"
ENV MALLOC_ARENA_MAX=2

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
