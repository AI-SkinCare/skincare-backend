# 운영 배포 (CD)

| 항목 | 내용 |
|------|------|
| 서버 | 정도희 Mac mini (`doi-1`, Tailscale `100.92.5.69`, 사용자 `doi`), Docker Desktop, arm64 |
| 위치 | `~/skincare-backend` (develop 체크아웃), `.env`·`docker-compose.override.yml`은 서버에만 |
| 서비스 | `docker compose`의 `db` · `backend` · `web`(nginx) · `cloudflared`(터널 토큰 있을 때) |
| 공개 주소 | `https://skincare.doi2.my` (Cloudflare 터널 → `web:80`) — 터널 연결 후 |
| 배포 트리거 | `develop`에 푸시 → `ci` 워크플로의 `build` 통과 → `deploy` 잡 |

## 흐름

```
GitHub Actions (ubuntu-latest)
  └─ build: spotless · test · bootJar · integrationTest
  └─ deploy (develop push / 수동 실행만)
       ├─ tailscale/github-action → tailnet에 tag:ci 임시 기기로 접속
       └─ ssh doi@100.92.5.69 "<커밋 SHA>"
             └─ authorized_keys forced command → ~/bin/skincare-deploy.sh <SHA>
                   git checkout <SHA> → docker compose up -d --build → nginx 경유 헬스체크
```

- 배포 키는 `command="/Users/doi/bin/skincare-deploy.sh",no-pty,...`로 제한돼 있어 **배포 스크립트 말고는 실행할 수 없다.** 넘기는 값은 40자 SHA뿐이고 그 외 입력은 거부한다.
- 저장소가 public이라 자체 호스팅 러너는 쓰지 않는다 (포크 PR이 서버에서 코드를 실행할 수 있음). 포크 PR에는 시크릿이 전달되지 않는다.
- 서버의 스크립트는 저장소 파일이 아니라 사본(`~/bin/skincare-deploy.sh`)이다. `scripts/deploy.sh`를 바꾸면 서버 사본도 갱신한다:
  `scp scripts/deploy.sh vocamini:~/bin/skincare-deploy.sh`

## GitHub 시크릿 (Settings → Secrets and variables → Actions)

| 이름 | 값 |
|------|-----|
| `TS_OAUTH_CLIENT_ID` / `TS_OAUTH_SECRET` | Tailscale OAuth 클라이언트 (아래) |
| `DEPLOY_SSH_KEY` | 배포 전용 ed25519 비밀키 (공개키는 서버 authorized_keys에 forced command로 등록) |
| `DEPLOY_HOST` | `100.92.5.69` |
| `DEPLOY_USER` | `doi` |
| `DEPLOY_KNOWN_HOSTS` | `ssh-keyscan -t ed25519 100.92.5.69` 출력 |

## Tailscale 설정 (관리자 콘솔, 1회)

1. **Access controls**(ACL)에 태그와 접근 허용 추가:
   ```jsonc
   "tagOwners": { "tag:ci": ["autogroup:admin"] },
   // grants 또는 acls 중 쓰는 방식에 맞춰 — CI 기기는 서버 SSH(22)만
   "grants": [ { "src": ["tag:ci"], "dst": ["100.92.5.69"], "ip": ["tcp:22"] } ]
   ```
2. **Settings → OAuth clients → Generate OAuth client**: 쓰기 범위 `auth_keys`(Keys → Auth Keys: Write), 태그 `tag:ci` → Client ID·Secret을 위 시크릿에 넣는다.
3. CI 기기는 ephemeral로 붙었다가 잡이 끝나면 사라진다.

## Cloudflare 터널 (1회)

1. Cloudflare 대시보드 → Zero Trust → Networks → Tunnels → **Create a tunnel** (Cloudflared), 이름 `skincare`
2. 토큰을 서버 `.env`의 `TUNNEL_TOKEN=`에 넣는다 (토큰은 실행 인자로 넘기지 않는다 — `docker inspect`에 노출됨)
3. **Public hostname**: `skincare.doi2.my` → Service `HTTP` `web:80`
4. 다음 배포부터 `cloudflared`가 자동으로 같이 뜬다 (지금 바로: 서버에서 `docker compose up -d cloudflared`)

## AI 서버 연결 (예정 — Tailscale)

- 백엔드 → AI 서버는 Tailscale로 붙인다. 서버 `.env`:
  `AI_MODE=real`, `AI_BASE_URL=http://<AI 기기 Tailscale IP>:8000`, `AI_INTERNAL_TOKEN=<AI와 같은 값>`
- Docker Desktop(macOS) 컨테이너의 외부 연결은 호스트를 거치므로 호스트의 Tailscale 경로(100.x)로 나간다. MagicDNS 이름은 컨테이너에서 안 풀릴 수 있어 **IP로 쓴다**. 연결 후 `GET /api/v1/system/status`의 `aiAvailable: true`로 확인.
- 그 전까지는 `AI_MODE=fixture`로 운영한다.

## 수동 배포 · 되돌리기

- 같은 커밋 다시 배포: Actions → `ci` → **Run workflow** (브랜치 `develop`)
- 특정 커밋으로 되돌리기: `develop`에 revert 커밋을 푸시한다. 급하면 서버에서 직접
  `~/bin/skincare-deploy.sh <이전 커밋 SHA>`
- 서버 상태: `ssh vocamini 'cd ~/skincare-backend && docker compose ps && docker compose logs --tail 50 backend'`
