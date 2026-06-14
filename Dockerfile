FROM maven:3.8.8-eclipse-temurin-8 AS builder
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

FROM eclipse-temurin:8-jre
WORKDIR /app
ENV PORT=80
COPY --from=builder /app/target/pearl-cloudrun-springboot-*.jar app.jar
EXPOSE 80
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
