FROM eclipse-temurin:21-jre-alpine

WORKDIR /app
COPY target/com.registration.server-1.0-SNAPSHOT.jar app.jar

EXPOSE 9000

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar \"$@\"", "--"]
