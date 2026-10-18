# Build the executable application JAR.
FROM maven:3.9.6-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn dependency:go-offline
COPY src ./src
RUN mvn clean package -DskipTests

# Run the application. Configure TELEGRAM_BOT_TOKEN, GOOGLE_API_KEY (or
# GEMINI_API_KEY), KASA_DB_PASSWORD, and KASA_DB_URL at container startup.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*jar-with-dependencies.jar app.jar

ENTRYPOINT ["java", "-jar", "app.jar"]
