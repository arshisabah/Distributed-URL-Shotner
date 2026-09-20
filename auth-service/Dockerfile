FROM eclipse-temurin:21-jre-jammy

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

COPY auth-service/target/*.jar app.jar

RUN chown -R appuser:appgroup /app
USER appuser

ENV JAVA_OPTS="-server -XX:+UseZGC -Xms256m -Xmx512m -XX:+UseContainerSupport -Djava.security.egd=file:/dev/./urandom"

EXPOSE 8080 8081

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
