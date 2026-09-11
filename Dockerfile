# Multi-stage build: keep final image small and free of Maven.
FROM maven:3.9-eclipse-temurin-25 AS build
WORKDIR /workspace
# Cache dependencies first
COPY pom.xml ./
RUN mvn -B -e -ntp dependency:go-offline
# Copy source and build
COPY src ./src
RUN mvn -B -e -ntp -DskipTests package

FROM eclipse-temurin:25-jre
WORKDIR /app
COPY --from=build /workspace/target/notification-service-*.jar /app/app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
