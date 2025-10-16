FROM eclipse-temurin:21-jdk
COPY mv64e-etl-processor-0.11.1.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
