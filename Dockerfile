# Stage 1: Build the application
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven

# Copy your source code and build the JAR
COPY . .
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:25-jdk
WORKDIR /app

# Copy the built JAR from the previous stage
COPY --from=build /app/target/*.jar app.jar

# Expose the port and run the app
ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
