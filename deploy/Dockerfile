# Một Dockerfile cho mọi dịch vụ:  docker build -f deploy/Dockerfile --build-arg SERVICE=auth .
# syntax=docker/dockerfile:1

FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
ARG SERVICE
WORKDIR /src
# Chép phần mô tả build trước để tận dụng cache tải thư viện.
COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle gradle
COPY proto/build.gradle.kts proto/
COPY common/build.gradle.kts common/
COPY edge/build.gradle.kts edge/
COPY auth/build.gradle.kts auth/
COPY sync/build.gradle.kts sync/
COPY content/build.gradle.kts content/
COPY gamification/build.gradle.kts gamification/
COPY insight/build.gradle.kts insight/
COPY all-in-one/build.gradle.kts all-in-one/
RUN --mount=type=cache,target=/root/.gradle ./gradlew --no-daemon -q :${SERVICE}:dependencies > /dev/null
COPY . .
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew --no-daemon :${SERVICE}:bootJar -x test \
 && cp ${SERVICE}/build/libs/${SERVICE}-*[0-9].jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --gid 10001 app && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
 && mkdir -p /app/native /data && chown -R app:app /app/native /data
WORKDIR /app
COPY --from=build /layers/dependencies/ ./
COPY --from=build /layers/spring-boot-loader/ ./
COPY --from=build /layers/snapshot-dependencies/ ./
COPY --from=build /layers/application/ ./
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
