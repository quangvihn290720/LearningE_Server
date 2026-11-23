# syntax=docker/dockerfile:1
# Một Dockerfile cho mọi dịch vụ:  docker build -f deploy/Dockerfile --build-arg SERVICE=auth .

# 1) Biên dịch tất cả một lần; lớp này dùng chung cho cả 6 image.
FROM eclipse-temurin:21.0.13_9-jdk-noble AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -q bootJar

# 2) Tách lớp jar của dịch vụ được chọn (thư viện ít đổi nằm riêng, build lại nhanh).
FROM build AS extract
ARG SERVICE
RUN cp ${SERVICE}/build/libs/${SERVICE}-*[0-9].jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

# 3) Image chạy: chỉ JRE và các lớp jar, không root.
FROM eclipse-temurin:21.0.13_9-jre-noble
RUN groupadd --gid 10001 app \
 && useradd --uid 10001 --gid app --no-create-home --shell /usr/sbin/nologin app \
 && mkdir -p /app/native /data && chown -R app:app /app/native /data
WORKDIR /app
COPY --from=extract /layers/dependencies/ ./
COPY --from=extract /layers/spring-boot-loader/ ./
COPY --from=extract /layers/snapshot-dependencies/ ./
COPY --from=extract /layers/application/ ./
USER 10001:10001
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
