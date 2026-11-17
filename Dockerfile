FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew shadowJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/klestra-jvm-1.1.0-all.jar /app/klestra.jar
ENTRYPOINT ["java", "-jar", "/app/klestra.jar"]
EXPOSE 8080
