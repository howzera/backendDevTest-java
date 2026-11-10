# --- build stage: compile and package with Maven ---
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build
# Dependencies first: this layer is reused while pom.xml does not change.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# --- runtime stage: JRE only, non-root user ---
FROM eclipse-temurin:17-jre
RUN useradd --create-home appuser
WORKDIR /service
COPY --from=build /build/target/similar-products.jar app.jar
USER appuser
EXPOSE 5000
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
