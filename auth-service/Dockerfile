# Multi-stage build — final image is minimal JRE only (no JDK, no build tools)
# Stage 1: Build
FROM eclipse-temurin:21-jdk-jammy AS builder
WORKDIR /build

# Cache Maven deps as a separate layer (only re-downloads on pom.xml change)
COPY pom.xml                    ../pom.xml
COPY common-lib/pom.xml         ../common-lib/pom.xml
COPY auth-service/pom.xml             pom.xml
RUN  --mount=type=cache,target=/root/.m2      cd .. && mvn dependency:go-offline -pl common-lib,auth-service -q 2>/dev/null || true

COPY ../common-lib/src           ../common-lib/src
COPY src                         src
RUN  --mount=type=cache,target=/root/.m2      cd .. && mvn package -pl common-lib,auth-service -am -DskipTests -q

# Extract layered jar for better Docker cache utilisation
RUN java -Djarmode=layertools -jar target/*.jar extract

# Stage 2: Runtime (slim JRE — ~200 MB vs ~500 MB for JDK)
FROM eclipse-temurin:21-jre-jammy AS runtime

# Security: never run as root
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# Copy layers in order of change frequency (dependencies rarely change)
COPY --from=builder /build/dependencies/          ./
COPY --from=builder /build/spring-boot-loader/    ./
COPY --from=builder /build/snapshot-dependencies/ ./
COPY --from=builder /build/application/           ./

RUN chown -R appuser:appgroup /app
USER appuser

ENV JAVA_OPTS="  -server   -XX:+UseZGC   -XX:+ZGenerational   -Xms256m -Xmx512m   -XX:+UseContainerSupport   -XX:MaxRAMPercentage=75.0   -XX:+AlwaysPreTouch   -Djava.security.egd=file:/dev/./urandom   -Dfile.encoding=UTF-8"

EXPOSE 8080 8081

HEALTHCHECK --interval=30s --timeout=10s --start-period=90s --retries=3   CMD curl -sf http://localhost:${SERVER_PORT:-8080}/actuator/health/liveness || exit 1

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
