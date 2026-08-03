FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/com.registration.server-1.0-SNAPSHOT.jar app.jar

# Mở cả 2 cổng 9000 và 8080
EXPOSE 9000 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]