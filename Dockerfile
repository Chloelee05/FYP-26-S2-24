# ── Stage 1: React production build ───────────────────────────────────────────
FROM node:20-alpine AS frontend-build
WORKDIR /build
COPY FYP/Frontend/package.json FYP/Frontend/package-lock.json ./
RUN npm ci
COPY FYP/Frontend/ ./
ENV VITE_BASE=/online-auction/
RUN npm run build

# ── Stage 2: Maven WAR build ──────────────────────────────────────────────────
FROM maven:3.9-eclipse-temurin-11 AS backend-build
WORKDIR /build
COPY FYP/pom.xml ./
COPY FYP/src ./src
RUN mvn -B package -DskipTests

# ── Stage 3: Explode WAR and merge React dist ─────────────────────────────────
FROM eclipse-temurin:11-jre AS app-assembly
WORKDIR /work
RUN apt-get update \
    && apt-get install -y --no-install-recommends unzip \
    && rm -rf /var/lib/apt/lists/*
COPY --from=backend-build /build/target/online-auction.war .
RUN unzip -q online-auction.war -d online-auction
COPY --from=frontend-build /build/dist/ online-auction/

# ── Stage 4: Tomcat 10 runtime ──────────────────────────────────────────────
FROM tomcat:10.1-jdk11-temurin
RUN rm -rf /usr/local/tomcat/webapps/*
COPY --from=app-assembly /work/online-auction /usr/local/tomcat/webapps/online-auction
COPY docker-entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN sed -i 's/\r$//' /usr/local/bin/docker-entrypoint.sh \
    && chmod +x /usr/local/bin/docker-entrypoint.sh
ENV CATALINA_OPTS="-Djava.io.tmpdir=/tmp"
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/docker-entrypoint.sh"]
