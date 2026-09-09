FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --chown=10001:10001 itmo-widgets-backend.jar app.jar
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
