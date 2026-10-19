FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
COPY docker/Healthcheck.java /build/Healthcheck.java
RUN javac -d /build/health /build/Healthcheck.java
RUN mvn -B -ntp package -DskipTests

FROM eclipse-temurin:21.0.8_9-jre-jammy
WORKDIR /app
RUN groupadd --system app && useradd --system --gid app --home-dir /app app
COPY --from=build --chown=app:app /build/target/skill-platform-1.0.0.jar /app/app.jar
COPY --from=build --chown=app:app /build/health/Healthcheck.class /app/Healthcheck.class
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
