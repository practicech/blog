#!/usr/bin/env bash
# 학교 공용 서버 배포(서버 배포 설명서 10-08): GitHub Actions가 이미지 파일과 함께 이 스크립트를 서버로 보내 실행한다.
# - DB는 Crowfoot PostgreSQL(밖), Nginx는 서버 공용 Nginx(~/nginx/<도메인>.conf → APP_PORT)
# - 이 스크립트는 우리 것만 띄운다: Redis 컨테이너 + 앱 컨테이너(APP_PORT로만 열림), 사진은 Docker 볼륨
# - 무중단 배포(093): 공용 Nginx는 그대로 APP_PORT(8300)만 보고, 8300에는 우리 전용 작은 Nginx(proxy.conf)를 둔다.
#   그 뒤에 앱 두 대(APP_PORT+1, APP_PORT+2)를 붙여 한 대씩 갈아끼운다. 새 컨테이너가 건강 확인을 통과해야 다음 대로 넘어가고,
#   그동안 다른 한 대가 요청을 받는다. 강사님이 공용 Nginx 설정을 바꿀 필요가 없다.
# - 자동 되돌리기(1.15.0): 새 버전이 3분 안에 안 켜지면 그 대만 전 이미지(team-blog:previous)로 다시 켠다.
#   마이그레이션은 "추가만" 하므로 새 버전이 DB를 바꾼 뒤에도 옛 버전이 같이 돈다(093 롤링 중에도 마찬가지).
# - 비밀값은 Actions가 환경 변수로 넘겨주고, 서버에는 0600 권한 env 파일로만 남긴다(다시 시작할 때 필요)
# 필요한 환경 변수: APP_PORT APP_DOMAIN DB_ADDRESS DB_PORT DB_NAME DB_USERNAME DB_PASSWORD DB_SCHEMA
#   MAIL_USERNAME MAIL_PASSWORD GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET
#   (선택) GEMINI_API_KEY, KAKAO_JS_KEY, ADMIN_EMAILS, TELEGRAM_BOT_TOKEN
set -euo pipefail
APP_PORT1="$((${APP_PORT:-8300} + 1))"
APP_PORT2="$((${APP_PORT:-8300} + 2))"

APP_NAME="${APP_NAME:-team-blog}"
WORK="${HOME}/${APP_NAME}"
IMAGE_TAR="${WORK}/image.tar.gz"
NET="${APP_NAME}-net"
REDIS="${APP_NAME}-redis"
APP="${APP_NAME}-app"
APP2="${APP_NAME}-app-2"
PROXY="${APP_NAME}-proxy"
PROXY_IMAGE="nginx:1.28-alpine"
APP_MEMORY="${APP_MEMORY:-1g}" # 공용 서버: 한 대당 메모리 상한(JVM은 이것의 75%를 힙으로 씀)

for v in APP_PORT APP_DOMAIN DB_ADDRESS DB_PORT DB_NAME DB_USERNAME DB_PASSWORD DB_SCHEMA \
         MAIL_USERNAME MAIL_PASSWORD GOOGLE_CLIENT_ID GOOGLE_CLIENT_SECRET GITHUB_CLIENT_ID GITHUB_CLIENT_SECRET; do
  if [ -z "${!v:-}" ]; then echo "환경 변수 ${v}가 비어 있어요(GitHub Secrets 확인)" >&2; exit 1; fi
done

# 붙여 넣다 따라 들어간 앞뒤 빈칸·줄바꿈만 지운다(가운데는 그대로, 키 모양 AIza…·AQ.…는 따지지 않음)
GEMINI_API_KEY="${GEMINI_API_KEY:-}"
GEMINI_API_KEY="${GEMINI_API_KEY#"${GEMINI_API_KEY%%[![:space:]]*}"}"
GEMINI_API_KEY="${GEMINI_API_KEY%"${GEMINI_API_KEY##*[![:space:]]}"}"
KAKAO_JS_KEY="${KAKAO_JS_KEY:-}"
KAKAO_JS_KEY="${KAKAO_JS_KEY#"${KAKAO_JS_KEY%%[![:space:]]*}"}"
KAKAO_JS_KEY="${KAKAO_JS_KEY%"${KAKAO_JS_KEY##*[![:space:]]}"}"
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN:-}"
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN#"${TELEGRAM_BOT_TOKEN%%[![:space:]]*}"}"
TELEGRAM_BOT_TOKEN="${TELEGRAM_BOT_TOKEN%"${TELEGRAM_BOT_TOKEN##*[![:space:]]}"}"

DOCKER="docker"
if ! docker info > /dev/null 2>&1; then DOCKER="sudo -n docker"; fi
${DOCKER} info > /dev/null 2>&1 || { echo "docker를 쓸 수 없어요(설치·권한 확인)" >&2; exit 1; }

mkdir -p "${WORK}"
chmod 700 "${WORK}"

# 사진 서명 비밀값: 처음 한 번 만들어 서버에만 둔다(바뀌면 예전 사진 주소가 깨지므로 유지)
if [ ! -s "${WORK}/storage-secret" ]; then
  (umask 077; head -c 48 /dev/urandom | base64 | tr -d '\n=+/' > "${WORK}/storage-secret")
fi

# 10-29 방장 AI 키 암호화 비밀값: 처음 한 번 만들어 서버에만 둔다(DB와 따로 있어 DB만 새어도 키를 못 읽음. 바뀌면 방장이 다시 연결)
if [ ! -s "${WORK}/club-ai-secret" ]; then
  (umask 077; head -c 48 /dev/urandom | base64 | tr -d '\n=+/' > "${WORK}/club-ai-secret")
fi

# 앱 환경 변수 파일(0600). 값에 줄바꿈이 없다는 전제(키·비밀번호)
ENV_FILE="${WORK}/app.env"
(
  umask 077
  {
    echo "SPRING_PROFILES_ACTIVE=prod"
    echo "DB_URL=jdbc:postgresql://${DB_ADDRESS}:${DB_PORT}/${DB_NAME}?currentSchema=${DB_SCHEMA},public"
    echo "DB_USERNAME=${DB_USERNAME}"
    echo "DB_PASSWORD=${DB_PASSWORD}"
    echo "SPRING_FLYWAY_DEFAULT_SCHEMA=${DB_SCHEMA}"
    echo "SPRING_FLYWAY_SCHEMAS=${DB_SCHEMA}"
    echo "REDIS_HOST=${REDIS}"
    echo "REDIS_PORT=6379"
    echo "APP_BASE_URL=https://${APP_DOMAIN}"
    echo "MAIL_USERNAME=${MAIL_USERNAME}"
    echo "MAIL_PASSWORD=${MAIL_PASSWORD}"
    echo "GOOGLE_CLIENT_ID=${GOOGLE_CLIENT_ID}"
    echo "GOOGLE_CLIENT_SECRET=${GOOGLE_CLIENT_SECRET}"
    echo "GITHUB_CLIENT_ID=${GITHUB_CLIENT_ID}"
    echo "GITHUB_CLIENT_SECRET=${GITHUB_CLIENT_SECRET}"
    echo "GEMINI_API_KEY=${GEMINI_API_KEY:-}"
    echo "KAKAO_JS_KEY=${KAKAO_JS_KEY:-}"
    echo "BLOG_ADMIN_EMAILS=${ADMIN_EMAILS:-}"
    echo "TELEGRAM_BOT_TOKEN=${TELEGRAM_BOT_TOKEN:-}"
    echo "STORAGE_TYPE=local"
    echo "STORAGE_LOCAL_DIR=/app/data"
    echo "STORAGE_LOCAL_SECRET=$(cat "${WORK}/storage-secret")"
    echo "CLUB_AI_SECRET=$(cat "${WORK}/club-ai-secret")"
  } > "${ENV_FILE}"
)

# 지금 돌고 있는 앱의 이미지를 previous로 표시해 둔다(새 버전이 안 켜지면 이걸로 다시 켠다)
PREV_ID="$(${DOCKER} inspect -f '{{.Image}}' "${APP}" 2>/dev/null || true)"
if [ -n "${PREV_ID}" ]; then ${DOCKER} tag "${PREV_ID}" "${APP_NAME}:previous"; fi

echo "== 이미지 불러오기"
${DOCKER} load -i "${IMAGE_TAR}"
rm -f "${IMAGE_TAR}"

${DOCKER} network inspect "${NET}" > /dev/null 2>&1 || ${DOCKER} network create "${NET}" > /dev/null

echo "== Redis"
if ! ${DOCKER} ps --format '{{.Names}}' | grep -qx "${REDIS}"; then
  ${DOCKER} rm -f "${REDIS}" > /dev/null 2>&1 || true
  ${DOCKER} run -d --name "${REDIS}" --network "${NET}" --restart unless-stopped \
    -v "${APP_NAME}-redis-data:/data" --log-opt max-size=10m --log-opt max-file=3 \
    redis:8 redis-server --appendonly yes > /dev/null
fi

run_app() { # $1 = 컨테이너 이름, $2 = 포트, $3 = 이미지
  # rm -f는 바로 죽이므로 먼저 stop: 받던 요청을 끝낼 시간(Spring 우아한 종료)을 준다. 새 요청은 우리 Nginx가 다른 대로 보낸다
  ${DOCKER} stop -t 30 "$1" > /dev/null 2>&1 || true
  ${DOCKER} rm -f "$1" > /dev/null 2>&1 || true
  ${DOCKER} run -d --name "$1" --hostname "$1" --network "${NET}" --restart unless-stopped \
    -p "127.0.0.1:$2:8080" --memory "${APP_MEMORY}" --env-file "${ENV_FILE}" \
    -v "${APP_NAME}-data:/app/data" --log-opt max-size=20m --log-opt max-file=5 \
    "$3" > /dev/null
}

wait_healthy() { # $1 = 포트. 5초마다, 최대 3분
  for i in $(seq 1 36); do
    if curl -fsS "http://127.0.0.1:$1/actuator/health" > /dev/null 2>&1; then return 0; fi
    sleep 5
  done
  return 1
}

# Gemini 키가 실제로 통하는지 배포 기록에 남긴다(키 값은 찍지 않음, 결과와 상관없이 배포는 계속). 모델 정보만 묻는 요청이라 하루 횟수를 쓰지 않는다
check_gemini() {
  if [ -z "${GEMINI_API_KEY:-}" ]; then echo "Gemini 키 없음: AI 기능은 꺼진 채로 켜져요"; return 0; fi
  local code
  # 키는 curl 인자가 아니라 표준 입력 설정으로 넘긴다(공용 서버에서 ps로 보이지 않게, printf는 셸 내장이라 프로세스가 안 생김)
  code="$(printf 'header = "x-goog-api-key: %s"\n' "${GEMINI_API_KEY}" | curl -s -o /dev/null -w '%{http_code}' -m 10 --config - \
    "https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL:-gemini-flash-lite-latest}" || true)"
  case "${code}" in
    200) echo "Gemini 키 확인: 통해요(200)" ;;
    400|401|403) echo "Gemini 키 확인: 거절됐어요(${code}). 키 값, API 사용 설정, 키의 API 제한을 확인하세요" ;;
    404) echo "Gemini 키 확인: 모델(${GEMINI_MODEL:-gemini-flash-lite-latest})을 찾지 못했어요(404)" ;;
    000) echo "Gemini 키 확인: 서버에서 Google에 닿지 못했어요(연결 실패 또는 시간 초과)" ;;
    *) echo "Gemini 키 확인: 예상하지 못한 응답이에요(${code})" ;;
  esac
}

# 메일 계정 로그인이 되는지 배포 기록에 남긴다(로그인만 하고 메일은 보내지 않음, 결과와 상관없이 배포는 계속).
# 계정·비밀번호는 curl 인자가 아니라 표준 입력 설정으로 넘긴다(공용 서버에서 ps로 보이지 않게). -v는 로그인 정보를 찍으므로 쓰지 않는다
check_mail() {
  local esc rc=0
  esc="$(printf %s "${MAIL_USERNAME}:${MAIL_PASSWORD}" | sed 's/[\\"]/\\&/g')"
  printf 'user = "%s"\n' "${esc}" | curl -s -o /dev/null -m 15 --ssl-reqd --config - "smtp://smtp.gmail.com:587" || rc=$?
  case "${rc}" in
    0) echo "메일 로그인 확인: 통해요(${MAIL_USERNAME})" ;;
    67) echo "메일 로그인 확인: 실패(로그인 거절, 535). MAIL_USERNAME과 Google 앱 비밀번호(MAIL_PASSWORD)를 확인하세요" ;;
    6|7|28) echo "메일 로그인 확인: 서버에서 smtp.gmail.com에 닿지 못했어요(curl ${rc})" ;;
    *) echo "메일 로그인 확인: 예상하지 못한 결과예요(curl ${rc})" ;;
  esac
}

# 096: 서버에서 api.telegram.org에 닿는지, 봇 토큰이 통하는지 배포 기록에 남긴다(토큰 값은 찍지 않음, 결과와 상관없이 배포는 계속)
check_telegram() {
  if [ -z "${TELEGRAM_BOT_TOKEN:-}" ]; then echo "텔레그램 토큰 없음: 텔레그램 알림은 꺼진 채로 켜져요"; return 0; fi
  local code
  code="$(printf 'url = "https://api.telegram.org/bot%s/getMe"\n' "${TELEGRAM_BOT_TOKEN}" | curl -s -o /dev/null -w '%{http_code}' -m 10 --config - || true)"
  case "${code}" in
    200) echo "텔레그램 봇 확인: 통해요(200)" ;;
    401|404) echo "텔레그램 봇 확인: 토큰이 거절됐어요(${code}). @BotFather가 준 토큰을 다시 넣어 주세요" ;;
    000) echo "텔레그램 봇 확인: 서버에서 api.telegram.org에 닿지 못했어요(연결 실패 또는 시간 초과)" ;;
    *) echo "텔레그램 봇 확인: 예상하지 못한 응답이에요(${code})" ;;
  esac
}

# 우리 전용 Nginx: 공용 Nginx → 서버IP:APP_PORT → 앱 두 대. 호스트 네트워크라 앱 포트(127.0.0.1)를 그대로 본다
start_proxy() {
  # 바인드한 파일을 같은 inode로 덮어써야 컨테이너가 새 내용을 본다(mv 금지)
  cat > "${WORK}/proxy.conf" <<CONF
map \$http_upgrade \$conn_upgrade {
    default upgrade;
    ''      '';
}
upstream app {
    # 한 번 실패하면 10초 동안 빼 둔다(배포 중 꺼진 대를 빨리 피함)
    server 127.0.0.1:${APP_PORT1} max_fails=1 fail_timeout=10s;
    server 127.0.0.1:${APP_PORT2} max_fails=1 fail_timeout=10s;
    keepalive 16;
}
server {
    # 공용 Nginx(nginx-manager, 11-03~)가 127.0.0.1이 아니라 서버 IP(10.x)로 들어오므로 모든 주소에서 받는다
    listen ${APP_PORT};
    client_max_body_size 20M;
    server_tokens off;
    location / {
        proxy_pass http://app;
        # 한 대가 꺼져 있으면 다른 대로 한 번만 다시 보낸다(POST는 앱에 닿기 전 실패일 때만, Nginx 기본)
        proxy_next_upstream error timeout http_502 http_503;
        proxy_next_upstream_tries 2;
        proxy_next_upstream_timeout 10s;
        proxy_connect_timeout 3s;
        proxy_read_timeout 60s;
        proxy_http_version 1.1;
        # 공용 Nginx가 붙인 원래 정보를 그대로 넘긴다(앱은 127.0.0.1을 믿을 프록시로 보고 맨 앞 IP를 씀)
        proxy_set_header Host              \$host;
        proxy_set_header X-Real-IP         \$http_x_real_ip;
        proxy_set_header X-Forwarded-For   \$proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto \$http_x_forwarded_proto;
        proxy_set_header Upgrade    \$http_upgrade;
        proxy_set_header Connection \$conn_upgrade;
    }
}
CONF
  if ${DOCKER} ps --format '{{.Names}}' | grep -qx "${PROXY}"; then
    ${DOCKER} exec "${PROXY}" nginx -s reload > /dev/null
    # reload로는 127.0.0.1:포트 → 모든 주소:포트로 못 바꾼다(같은 포트 bind 충돌). 아직 127.0.0.1만 쥐고 있으면 한 번 다시 켠다
    if ss -ltnH "sport = :${APP_PORT}" | grep -q "127.0.0.1:${APP_PORT}"; then
      ${DOCKER} restart "${PROXY}" > /dev/null
    fi
    return
  fi
  ${DOCKER} rm -f "${PROXY}" > /dev/null 2>&1 || true
  ${DOCKER} run -d --name "${PROXY}" --network host --restart unless-stopped --memory 64m \
    -v "${WORK}/proxy.conf:/etc/nginx/conf.d/default.conf:ro" --log-opt max-size=10m --log-opt max-file=3 \
    "${PROXY_IMAGE}" > /dev/null
}

# 한 대 교체. 실패하면 그 대만 전 버전으로 되돌리고 1을 돌려준다(다른 대는 손대지 않았으니 사이트는 계속 돈다)
roll() { # $1 = 컨테이너 이름, $2 = 포트
  # 되돌릴 이미지는 이 대가 지금 쓰는 것(지난 배포가 중간에 멈춰 두 대의 이미지가 다를 수 있음). 처음 띄우는 대면 1번 것
  local prev
  prev="$(${DOCKER} inspect -f '{{.Image}}' "$1" 2>/dev/null || true)"
  prev="${prev:-${PREV_ID}}"
  echo "== ${1}(${2}) 교체, 상태 확인(최대 3분)"
  run_app "$1" "$2" "${APP_NAME}:latest"
  if wait_healthy "$2"; then echo "${1} 켜졌어요"; return 0; fi
  echo "새 버전이 3분 안에 켜지지 않았어요(${1}). 마지막 로그:" >&2
  ${DOCKER} logs --tail 80 "$1" >&2 || true
  if [ -z "${prev}" ]; then return 1; fi # 처음 배포라 되돌릴 버전이 없음
  echo "== ${1}을 전 버전으로 되돌리기" >&2
  run_app "$1" "$2" "${prev}"
  if wait_healthy "$2"; then
    echo "${1}을 전 버전으로 다시 켰어요. 새 버전은 위 로그를 보고 고쳐서 다시 배포해요." >&2
  else
    echo "전 버전도 켜지지 않았어요(DB·Redis 같은 서버 쪽 문제일 수 있어요). 마지막 로그:" >&2
    ${DOCKER} logs --tail 80 "$1" >&2 || true
  fi
  return 1
}

echo "== 지금 메모리(공용 서버 확인용)"
free -m 2>/dev/null | head -2 || true
nginx -v 2>&1 || true # 1.25.1 이상이면 Nginx 설정의 http2 on;을 켤 수 있다
${DOCKER} stats --no-stream --format '{{.Name}} {{.MemUsage}}' "${APP}" "${APP2}" "${PROXY}" 2>/dev/null || true

# 우리 Nginx 이미지는 앱을 건드리기 전에 받아 둔다(못 받으면 아무것도 바꾸지 않고 멈춤)
${DOCKER} pull -q "${PROXY_IMAGE}" > /dev/null || { echo "${PROXY_IMAGE}를 받지 못했어요. 아무것도 바꾸지 않았어요" >&2; exit 1; }

# 2번 대부터(1번이 계속 받는 동안). 실패하면 1번은 건드리지 않고 멈춘다(빨간 X)
roll "${APP2}" "${APP_PORT2}" || exit 1

# 우리 Nginx로 처음 바꿀 때만: 1번이 쥐고 있던 APP_PORT를 비우고 그 자리에 우리 Nginx를 띄운다(1번이 받던 요청을 마치는 동안만 겹침, 몇 초 끊길 수 있음)
if ! ${DOCKER} ps --format '{{.Names}}' | grep -qx "${PROXY}"; then
  echo "== ${APP_PORT}에 우리 Nginx 띄우기(처음 한 번)"
  ${DOCKER} stop -t 30 "${APP}" > /dev/null 2>&1 || true
  ${DOCKER} rm -f "${APP}" > /dev/null 2>&1 || true
  if ! start_proxy || ! wait_healthy "${APP_PORT}"; then
    echo "우리 Nginx가 켜지지 않았어요. 1번을 ${APP_PORT}에 예전 방식으로 다시 켜요" >&2
    ${DOCKER} logs --tail 30 "${PROXY}" >&2 || true
    ${DOCKER} rm -f "${PROXY}" > /dev/null 2>&1 || true
    run_app "${APP}" "${APP_PORT}" "${PREV_ID:-${APP_NAME}:latest}"
    wait_healthy "${APP_PORT}" || true
    exit 1
  fi
else
  start_proxy # 설정이 바뀌었을 수 있으니 다시 읽힌다
  # 우리 Nginx는 실패한 대를 10초(fail_timeout) 동안 빼 두므로, 2번이 다시 받기 시작한 뒤에 1번을 내린다
  sleep 15
fi
roll "${APP}" "${APP_PORT1}" || exit 1

echo "앱 두 대가 켜졌어요: 127.0.0.1:${APP_PORT}(우리 Nginx) → ${APP_PORT1}, ${APP_PORT2} → https://${APP_DOMAIN}"
check_gemini
check_mail
check_telegram
${DOCKER} image prune -f > /dev/null 2>&1 || true
