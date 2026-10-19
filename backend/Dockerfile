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

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
