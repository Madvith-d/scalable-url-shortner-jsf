FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /build
RUN apt-get update && apt-get install -y --no-install-recommends curl unzip ca-certificates \
    && rm -rf /var/lib/apt/lists/*
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw && ./mvnw -B --no-transfer-progress dependency:go-offline
COPY src src
RUN ./mvnw -B --no-transfer-progress -DskipTests package

FROM eclipse-temurin:21-jre-jammy
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system shortify && useradd --system --gid shortify shortify
COPY --from=build --chown=shortify:shortify /build/target/shortify-0.0.1-SNAPSHOT.jar app.jar
USER shortify
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
