# 빌드 컨텍스트는 레포 루트다:
#   docker build -t evacs-api-server .
#
# 1단계: gradle wrapper로 부트 jar를 만든다(호스트 JDK/gradle 불필요).
FROM eclipse-temurin:17-jdk AS build
WORKDIR /workspace

# 의존성 해석만 먼저 캐싱한다 — src 변경에 전체 다운로드가 다시 돌지 않게.
COPY gradlew settings.gradle build.gradle ./
COPY gradle gradle
RUN chmod +x gradlew && ./gradlew --no-daemon dependencies > /dev/null 2>&1 || true

COPY src src
# 테스트는 MySQL·Redis가 필요하므로 이미지 빌드에서는 돌리지 않는다(호스트에서 ./gradlew test).
RUN ./gradlew --no-daemon clean bootJar -x test \
 && cp build/libs/*.jar /workspace/app.jar

# 2단계: 실행 이미지.
# temurin alpine 태그는 amd64만 있어 Apple Silicon에서 못 쓴다 → jammy(ubuntu) 베이스.
FROM eclipse-temurin:17-jre-jammy AS runtime
WORKDIR /app

# HEALTHCHECK용 curl만 추가한다.
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r evacs && useradd -r -g evacs evacs
COPY --from=build --chown=evacs:evacs /workspace/app.jar /app/app.jar
USER evacs

ENV TZ=UTC \
    PORT=8081 \
    JAVA_OPTS="-XX:MaxRAMPercentage=75"

EXPOSE 8081

# actuator health는 application.yml에서 이미 노출돼 있다.
HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=10 \
  CMD curl -fsS "http://127.0.0.1:${PORT}/actuator/health" | grep -q '"status":"UP"' || exit 1

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
