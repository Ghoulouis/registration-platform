FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/client-1.0-SNAPSHOT.jar app.jar

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
