# syntax=docker/dockerfile:1
# Một Dockerfile cho mọi dịch vụ:  docker build -f deploy/Dockerfile --build-arg SERVICE=auth .

# 1) Biên dịch tất cả một lần; lớp này dùng chung cho cả 6 image.
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:bc12ebd88ffa6dda0092940e179365b05aef8d813b867f9df5a1f168c9a44feb AS build
WORKDIR /src
COPY . .
RUN --mount=type=cache,target=/root/.gradle,sharing=locked \
    ./gradlew --no-daemon -q bootJar

# 2) Tách lớp jar của dịch vụ được chọn (thư viện ít đổi nằm riêng, build lại nhanh).
FROM build AS extract
ARG SERVICE
RUN cp ${SERVICE}/build/libs/${SERVICE}-*[0-9].jar /app.jar \
 && java -Djarmode=tools -jar /app.jar extract --layers --launcher --destination /layers

# 3) JRE rút gọn: đủ Java SE và vài module jdk.* mà Netty, SQLite, MongoDB cần; bỏ công cụ chẩn đoán và biên dịch.
FROM eclipse-temurin:21.0.13_9-jdk-noble@sha256:bc12ebd88ffa6dda0092940e179365b05aef8d813b867f9df5a1f168c9a44feb AS jre
RUN jlink --output /jre --strip-debug --no-man-pages --no-header-files --compress zip-6 \
      --add-modules java.se,jdk.unsupported,jdk.crypto.ec,jdk.management,jdk.net,jdk.zipfs,jdk.naming.dns,jdk.charsets

# 4) Image chạy: chỉ JRE và các lớp jar, không root.
FROM ubuntu:noble@sha256:562111b88caa79c71391c0c4d3c2114a7d5c35915c20561fd893cb1e07929e6b
COPY --from=jre /jre /opt/java
ENV PATH="/opt/java/bin:$PATH" LANG=C.UTF-8
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
