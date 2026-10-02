# Throwaway spike image; the jar is built on the host with ./gradlew bootJar.
FROM eclipse-temurin:25-jre
WORKDIR /app
COPY build/libs/ws-spike.jar app.jar
ENTRYPOINT ["java", "-XX:+UseG1GC", "-Xms256m", "-Xmx1g", "-jar", "app.jar"]
