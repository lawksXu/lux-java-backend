# syntax=docker/dockerfile:1
FROM eclipse-temurin:17-jdk-noble AS build

WORKDIR /workspace
COPY mvnw pom.xml ./
COPY .mvn/wrapper/ .mvn/wrapper/
COPY src/ src/
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:17-jre-noble AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lux \
    && useradd --uid 10001 --gid 10001 --no-create-home --home-dir /var/lib/lux-backend lux \
    && mkdir -p /app /var/lib/lux-backend/assets /var/lib/lux-backend/staging \
    && chown -R 10001:10001 /var/lib/lux-backend

WORKDIR /app
COPY --from=build /workspace/target/*.jar /app/app.jar

ENV LUX_BIND_ADDRESS=0.0.0.0 \
    LUX_STORAGE_ROOT=/var/lib/lux-backend/assets \
    LUX_STORAGE_STAGING_ROOT=/var/lib/lux-backend/staging

USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
