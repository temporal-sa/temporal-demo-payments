FROM eclipse-temurin:21-jdk AS build
WORKDIR /src/backend/kotlin
COPY backend/kotlin/gradle gradle
COPY backend/kotlin/gradlew backend/kotlin/settings.gradle.kts backend/kotlin/build.gradle.kts backend/kotlin/gradle.properties ./
COPY backend/kotlin/src src
RUN chmod +x gradlew && ./gradlew bootJar --no-daemon

FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /src/backend/kotlin/build/libs/payout-demo-0.1.0.jar build/libs/payout-demo-0.1.0.jar
EXPOSE 8081
CMD ["java", "-jar", "build/libs/payout-demo-0.1.0.jar"]
