# TRD §17.1 — 멀티 스테이지: JDK에서 빌드 → JRE로 실행 (Java 21)
FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
# 의존성 층을 먼저 받아 소스만 바뀌면 캐시를 쓴다
COPY gradlew settings.gradle build.gradle ./
COPY gradle ./gradle
RUN ./gradlew --no-daemon dependencies > /dev/null
# 소스 + AI_MODE=fixture용 AI 응답 fixture(src/main/resources/fixtures, 실제 데이터 아님)
COPY src/main ./src/main
RUN ./gradlew --no-daemon bootJar && cp build/libs/skincare-backend-*.jar app.jar

FROM eclipse-temurin:21-jre-alpine AS run
ENV APP_ENV=production
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
COPY --from=build --chown=app:app /app/app.jar ./app.jar
USER app
EXPOSE 3000
# DB까지 확인하는 경로 (DB 장애면 500)
HEALTHCHECK --interval=30s --timeout=5s --start-period=40s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/api/v1/system/status > /dev/null || exit 1
# 마이그레이션은 앱 기동 시 Flyway가 먼저 적용한다 (실패하면 기동하지 않음)
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
