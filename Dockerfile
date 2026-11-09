FROM eclipse-temurin:17-jdk AS build
WORKDIR /app
COPY . .
RUN ./gradlew jvmJar

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /app/build/libs/klestra-jvm-1.1.0.jar /app/klestra-jvm.jar
ENTRYPOINT ["java", "-jar", "/app/klestra-jvm.jar", "dev.kigawa.klestra.K8sMainKt"]
EXPOSE 8080
