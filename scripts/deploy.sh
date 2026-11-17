#!/bin/bash
# 운영 서버(Mac mini, Docker Desktop) 배포 스크립트 — docs/deploy.md
#
# GitHub Actions가 Tailscale로 서버에 SSH 접속하면, authorized_keys의 forced command로 이 스크립트만 실행된다.
# 배포할 커밋 SHA는 SSH 명령(SSH_ORIGINAL_COMMAND) 또는 첫 인자로 받는다.
# 서버에는 저장소 안의 파일이 아니라 ~/bin/skincare-deploy.sh 사본을 둔다 (git checkout 중에 실행 중인 스크립트가 바뀌지 않게).
set -euo pipefail

SHA="${1:-${SSH_ORIGINAL_COMMAND:-}}"
if [[ ! "$SHA" =~ ^[0-9a-f]{40}$ ]]; then
  echo "배포할 커밋 SHA(40자)가 필요해요: '${SHA}'" >&2
  exit 2
fi

APP_DIR="${APP_DIR:-$HOME/skincare-backend}"
export PATH="/usr/local/bin:/opt/homebrew/bin:/Applications/Docker.app/Contents/Resources/bin:$PATH"
cd "$APP_DIR"

echo "▶ $(date -u +%FT%TZ) 배포 시작: $SHA"
git fetch --quiet origin
git checkout --quiet --detach "$SHA"

# 터널 토큰이 있을 때만 cloudflared를 같이 띄운다
services=(db backend web)
if grep -qE '^TUNNEL_TOKEN=.+' .env; then
  services+=(cloudflared)
fi

docker compose up -d --build "${services[@]}"

echo "▶ 헬스체크 (최대 3분)"
for _ in $(seq 1 36); do
  if docker compose exec -T web wget -qO- http://127.0.0.1/api/v1/system/status > /dev/null 2>&1; then
    echo "✓ 배포 완료: $(git log --oneline -1)"
    docker image prune -f > /dev/null
    exit 0
  fi
  sleep 5
done

echo "✗ 헬스체크 실패 — 최근 로그" >&2
docker compose ps >&2
docker compose logs --tail 80 backend >&2
exit 1
