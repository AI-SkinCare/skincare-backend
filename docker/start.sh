#!/bin/sh
# TRD §17.1 — 컴파일된 마이그레이션 실행 → 앱 시작 (migrationsRun은 쓰지 않는다)
set -e
node node_modules/typeorm/cli.js migration:run -d dist/database/data-source.js
exec node dist/main.js
