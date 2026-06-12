# ───────────── Etape 1 — compilation (Maven + JDK 21) ─────────────
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app

# Cache des dependances : on copie d'abord le manifeste seul.
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline || true

# Copie du code reel puis compilation (tests ignores pour l'image).
COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ───────────── Etape 2 — image d'execution minimale (JRE 21) ─────────────
FROM eclipse-temurin:21-jre AS runtime
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

# Execution sous un utilisateur non-root (principe du moindre privilege).
RUN useradd --system --no-create-home --uid 10001 appuser
WORKDIR /app

COPY --from=build /app/target/afb-titres-backend.jar app.jar

USER appuser
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=3s --start-period=60s --retries=8 \
    CMD curl -fsS http://localhost:8080/health || exit 1

ENTRYPOINT ["java", "-Xms128m", "-Xmx320m", "-jar", "/app/app.jar"]
