FROM eclipse-temurin:17-jre-alpine AS base

WORKDIR /app

COPY x-registry-server/target/x-registry-server-*-exec.jar app.jar

EXPOSE 8848 9848 7848 7849 7850

HEALTHCHECK --interval=10s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8848/v1/cluster/health || exit 1

ENTRYPOINT ["java", "-jar", "app.jar"]
