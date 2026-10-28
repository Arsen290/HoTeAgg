FROM maven:3-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml ./
COPY src ./src
RUN mvn -q -B -DskipTests package
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /build/target/hoteagg-simulator-0.0.1-SNAPSHOT.jar app.jar
ENV SIMULATOR_URL=ws://app:8080/ws/telemetry
ENTRYPOINT ["java", "-jar", "app.jar"]
