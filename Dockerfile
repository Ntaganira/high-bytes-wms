# Build
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src ./src
RUN mvn -B clean package -DskipTests

# Run
FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S hb && adduser -S hb -G hb \
 && mkdir -p /data/attachments && chown -R hb:hb /data
WORKDIR /app
COPY --from=build /build/target/highbytes-wms-*.jar app.jar
USER hb
ENV TZ=Africa/Kigali
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
