# TRD §17.1 — 멀티 스테이지 node:20-alpine
FROM node:20-alpine AS build
WORKDIR /app
# bcrypt 네이티브 빌드 대비 (alpine용 prebuild가 없을 때)
RUN apk add --no-cache python3 make g++
COPY package.json package-lock.json ./
RUN npm ci
COPY tsconfig.json tsconfig.build.json nest-cli.json ./
COPY src ./src
# AI_MODE=fixture로 띄울 때 필요한 AI 응답 fixture (실제 데이터 아님)
COPY test/fixtures/ai ./test/fixtures/ai
# 빌드 후 dev 의존성을 걷어 낸 node_modules를 그대로 넘긴다 (run 스테이지에 빌드 도구 불필요)
RUN npm run build && npm prune --omit=dev

FROM node:20-alpine AS run
ENV NODE_ENV=production
WORKDIR /app
COPY --from=build --chown=node:node /app/package.json ./package.json
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --from=build --chown=node:node /app/test/fixtures/ai ./test/fixtures/ai
COPY --chown=node:node docker/start.sh ./start.sh
USER node
EXPOSE 3000
# DB까지 확인하는 경로 (DB 장애면 500)
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD wget -qO- http://127.0.0.1:3000/api/v1/system/status > /dev/null || exit 1
CMD ["sh", "./start.sh"]
