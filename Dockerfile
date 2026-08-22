# Stage 1: Build the application
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

# Install Maven
RUN apt-get update && apt-get install -y maven

# Copy your source code
COPY . .

# Move into the subfolder containing the pom.xml
WORKDIR /app/upi-offline-mesh
RUN mvn clean package -DskipTests

# Stage 2: Run the application
FROM eclipse-temurin:25-jdk
WORKDIR /app

# Copy the built JAR from the subfolder's target directory
COPY --from=build /app/upi-offline-mesh/target/*.jar app.jar

ENV PORT=8080
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
