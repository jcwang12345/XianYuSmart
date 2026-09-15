#!/bin/zsh

# Native macOS QA runtime: Java/Vue run on the host, while MySQL 5.7 reuses the
# isolated Docker QA volume. Production port 2000 and its MySQL volume are never touched.
set -eu

SCRIPT_PATH="${0:A}"
PROJECT_ROOT="${SCRIPT_PATH:h:h}"
TOOLCHAIN="$PROJECT_ROOT/scripts/local-toolchain.sh"
COMPOSE_FILE="$PROJECT_ROOT/deploy/native-qa/compose.mysql.yaml"
RUNTIME_DIR="$PROJECT_ROOT/.tools/native-qa"
LOG_FILE="$RUNTIME_DIR/app.log"
JAR_LINK="$RUNTIME_DIR/app.jar"
RELEASE_DIR="$RUNTIME_DIR/releases"
LAUNCH_LABEL="com.xianyusmart.native-qa"
APP_ENV_CONTAINER="${NATIVE_QA_APP_ENV_CONTAINER:-xianyusmart-matrix-handoff-app}"
MYSQL_PORT="${NATIVE_QA_MYSQL_PORT:-13306}"
APP_PORT="${NATIVE_QA_APP_PORT:-3000}"
DOCKER_BIN="${NATIVE_QA_DOCKER_BIN:-$(command -v docker 2>/dev/null || true)}"
if [[ ! -x "$DOCKER_BIN" ]]; then
  DOCKER_BIN="/Users/$(id -un)/.docker/bin/docker"
fi
[[ -x "$DOCKER_BIN" ]] || { echo "Docker CLI not found; set NATIVE_QA_DOCKER_BIN." >&2; exit 1; }

mkdir -p "$RUNTIME_DIR/data" "$RUNTIME_DIR/logs" "$RELEASE_DIR"

atomic_link() {
  local target="$1"
  local pending_link="$RUNTIME_DIR/.app.jar.$$.next"
  ln -s "$target" "$pending_link"
  mv -f "$pending_link" "$JAR_LINK"
}

snapshot_current_release() {
  local snapshot_name="$1"
  [[ -f "$JAR_LINK" ]] || return 0
  local current_path="${JAR_LINK:A}"
  if [[ "$current_path" == "$RELEASE_DIR"/* ]]; then
    echo "$current_path"
    return 0
  fi
  local snapshot_path="$RELEASE_DIR/${snapshot_name}.jar"
  cp -p "$current_path" "$snapshot_path"
  echo "$snapshot_path"
}

container_env() {
  local key="$1"
  "$DOCKER_BIN" inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$APP_ENV_CONTAINER" 2>/dev/null \
    | sed -n "s/^${key}=//p" | head -n 1
}

import_qa_environment() {
  local key value
  local keys=(
    DB_USERNAME DB_PASSWORD JWT_SECRET ACCOUNT_DATA_ENCRYPTION_KEY
    JWT_EXPIRATION_MS JWT_REFRESH_EXPIRATION_MS
    BOOTSTRAP_ADMIN_USERNAME BOOTSTRAP_ADMIN_PASSWORD
    PRODUCT_BATCH_DISPATCH_ENABLED PRODUCT_BATCH_QA_MOCK_ENABLED
    PRODUCT_BATCH_QA_MOCK_TENANT_ID PRODUCT_BATCH_QA_MOCK_ACCOUNT_IDS
    PRODUCT_BATCH_QA_MOCK_GOODS_PREFIX PUBLISH_QA_MOCK_ENABLED
    PUBLISH_QA_MOCK_TENANT_ID PUBLISH_QA_MOCK_ACCOUNT_IDS
    PUBLISH_QA_MOCK_TITLE_PREFIX BACKUP_QA_MOCK_ENABLED
    BACKUP_QA_MOCK_TENANT_ID ACCOUNT_BATCH_QA_MOCK_ENABLED
    ACCOUNT_BATCH_QA_MOCK_TENANT_ID ACCOUNT_BATCH_QA_MOCK_ACCOUNT_IDS
    PRODUCT_MARKETING_QA_MOCK_ENABLED PRODUCT_MARKETING_QA_MOCK_TENANT_ID
    PRODUCT_MARKETING_QA_MOCK_ACCOUNT_IDS
    AI_ENABLED PRINT_RAW_MESSAGE
  )
  for key in $keys; do
    value="$(container_env "$key")"
    if [[ -n "$value" ]]; then
      export "$key=$value"
    fi
  done
  : "${DB_USERNAME:?Missing DB_USERNAME in $APP_ENV_CONTAINER}"
  : "${DB_PASSWORD:?Missing DB_PASSWORD in $APP_ENV_CONTAINER}"
  : "${JWT_SECRET:?Missing JWT_SECRET in $APP_ENV_CONTAINER}"
  : "${ACCOUNT_DATA_ENCRYPTION_KEY:?Missing ACCOUNT_DATA_ENCRYPTION_KEY in $APP_ENV_CONTAINER}"
  export DB_URL="jdbc:mysql://127.0.0.1:${MYSQL_PORT}/xianyusmart?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&rewriteBatchedStatements=true&sslMode=DISABLED"
  export SERVER_PORT="$APP_PORT"
  export SPRING_PROFILES_ACTIVE="qa"
  export BACKUP_QA_MOCK_ENABLED="${BACKUP_QA_MOCK_ENABLED:-true}"
  export BACKUP_QA_MOCK_TENANT_ID="${BACKUP_QA_MOCK_TENANT_ID:-${PRODUCT_BATCH_QA_MOCK_TENANT_ID:--1}}"
  export ACCOUNT_BATCH_QA_MOCK_ENABLED="${ACCOUNT_BATCH_QA_MOCK_ENABLED:-true}"
  export ACCOUNT_BATCH_QA_MOCK_TENANT_ID="${ACCOUNT_BATCH_QA_MOCK_TENANT_ID:-${PRODUCT_BATCH_QA_MOCK_TENANT_ID:-1}}"
  export ACCOUNT_BATCH_QA_MOCK_ACCOUNT_IDS="${ACCOUNT_BATCH_QA_MOCK_ACCOUNT_IDS:-${PRODUCT_BATCH_QA_MOCK_ACCOUNT_IDS:-101,102,103}}"
  export ALLOWED_ORIGINS="http://localhost:${APP_PORT},http://127.0.0.1:${APP_PORT}"
  export MEDIA_STORAGE_DIR="$RUNTIME_DIR/data/media"
  export VECTOR_STORE_FILE="$RUNTIME_DIR/data/vectorstore.json"
  export RISK_GUARD_STATE_FILE="$RUNTIME_DIR/data/platform-risk-guard.json"
}

mysql_up() {
  NATIVE_QA_MYSQL_PORT="$MYSQL_PORT" "$DOCKER_BIN" compose -p xianyusmart-native-qa -f "$COMPOSE_FILE" up -d mysql
  local attempt
  for attempt in {1..60}; do
    if "$DOCKER_BIN" exec xianyusmart-native-qa-mysql mysqladmin ping -h 127.0.0.1 --silent >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "QA MySQL did not become ready; inspect: docker logs xianyusmart-native-qa-mysql" >&2
  return 1
}

running_pid() {
  launchctl list | awk -v label="$LAUNCH_LABEL" '$3 == label && $1 ~ /^[0-9]+$/ {print $1; exit}' | grep -E '^[0-9]+$'
}

stop_app() {
  local pid="$(running_pid || true)"
  if launchctl list "$LAUNCH_LABEL" >/dev/null 2>&1; then
    launchctl remove "$LAUNCH_LABEL"
  fi
  if [[ -n "$pid" ]]; then
    local attempt
    for attempt in {1..30}; do
      kill -0 "$pid" 2>/dev/null || return 0
      sleep 1
    done
    echo "Native QA app did not stop within 30 seconds (pid $pid)." >&2
    return 1
  fi
}

start_app() {
  mysql_up
  if running_pid >/dev/null 2>&1; then
    echo "Native QA app is already running on port $APP_PORT."
    return 0
  fi
  [[ -f "$JAR_LINK" ]] || {
    echo "Missing $JAR_LINK; run: scripts/native-qa.sh deploy" >&2
    return 1
  }
  if launchctl list "$LAUNCH_LABEL" >/dev/null 2>&1; then
    launchctl remove "$LAUNCH_LABEL"
  fi
  # launchd 接管进程，避免终端或 Codex 命令会话结束时回收后台 Java 进程。
  launchctl submit -l "$LAUNCH_LABEL" -- /bin/zsh "$SCRIPT_PATH" run-app
  local attempt
  for attempt in {1..120}; do
    if curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
      local pid="$(running_pid || true)"
      echo "Native QA is ready: http://127.0.0.1:${APP_PORT} (pid ${pid:-launchd}, MySQL 127.0.0.1:${MYSQL_PORT})"
      return 0
    fi
    if ! launchctl list "$LAUNCH_LABEL" >/dev/null 2>&1; then
      echo "Native QA app exited during startup. See $LOG_FILE" >&2
      tail -n 80 "$LOG_FILE" >&2
      return 1
    fi
    sleep 1
  done
  echo "Native QA health check timed out. See $LOG_FILE" >&2
  return 1
}

deploy_app() {
  local build_frontend="${1:-true}"
  # Maven replaces target/*.jar in-place. Snapshot a legacy/current artifact
  # before packaging so a running JVM never depends on the file being rebuilt.
  local deployed_at="$(date -u +%Y%m%dT%H%M%SZ)"
  local previous_release="$(snapshot_current_release "predeploy-${deployed_at}" || true)"
  if [[ "$build_frontend" == "true" ]]; then
    "$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run type-check
    "$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run build-only
  else
    [[ -f "$PROJECT_ROOT/src/main/resources/static/index.html" ]] || {
      echo "Missing built frontend resources; run: scripts/native-qa.sh deploy" >&2
      return 1
    }
    echo "Reusing the last verified frontend build (backend-only deployment)."
  fi
  "$TOOLCHAIN" "$PROJECT_ROOT/mvnw" -DskipTests package
  local version="$(awk '/<artifactId>xianyusmart<\/artifactId>/{project=1;next} project && /<version>/{line=$0;sub(/^.*<version>/,"",line);sub(/<\/version>.*$/,"",line);print line;exit}' "$PROJECT_ROOT/pom.xml")"
  local jar="$PROJECT_ROOT/target/xianyusmart-${version}.jar"
  [[ -f "$jar" ]] || { echo "Packaged jar not found: $jar" >&2; return 1; }
  local checksum="$(shasum -a 256 "$jar" | awk '{print substr($1, 1, 12)}')"
  local release="$RELEASE_DIR/xianyusmart-${version}-${deployed_at}-${checksum}.jar"
  local pending_release="${release}.tmp.$$"
  cp -p "$jar" "$pending_release"
  mv "$pending_release" "$release"
  stop_app
  atomic_link "$release"
  if start_app; then
    echo "deployed artifact: $release"
    return 0
  fi
  if [[ -n "$previous_release" && -f "$previous_release" ]]; then
    echo "New release failed health check; rolling back to $previous_release" >&2
    stop_app || true
    atomic_link "$previous_release"
    start_app
  fi
  return 1
}

refresh_frontend() {
  local run_type_check="${1:-false}"
  if [[ "$run_type_check" == "true" ]]; then
    "$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run type-check
  fi
  "$TOOLCHAIN" npm --prefix "$PROJECT_ROOT/vue-code" run build-only
  if ! curl -fsS "http://127.0.0.1:${APP_PORT}/actuator/health" >/dev/null 2>&1; then
    echo "Frontend built, but Native QA is not healthy; run: scripts/native-qa.sh start" >&2
    return 1
  fi
  echo "Frontend refreshed without repackaging the backend: http://127.0.0.1:${APP_PORT}"
}

status() {
  local pid=""
  if pid="$(running_pid)"; then
    echo "app: running (pid $pid, http://127.0.0.1:${APP_PORT})"
  else
    echo "app: stopped"
  fi
  if "$DOCKER_BIN" inspect -f '{{.State.Health.Status}}' xianyusmart-native-qa-mysql >/dev/null 2>&1; then
    echo "mysql: $("$DOCKER_BIN" inspect -f '{{.State.Health.Status}}' xianyusmart-native-qa-mysql) (127.0.0.1:${MYSQL_PORT})"
  else
    echo "mysql: stopped"
  fi
}

case "${1:-status}" in
  run-app)
    exec >>"$LOG_FILE" 2>&1
    cd "$PROJECT_ROOT"
    import_qa_environment
    exec "$PROJECT_ROOT/.tools/jdk21/Contents/Home/bin/java" -Xms256m -Xmx2g \
      -jar "$JAR_LINK"
    ;;
  deploy) deploy_app true ;;
  deploy-backend) deploy_app false ;;
  frontend) refresh_frontend true ;;
  frontend-fast) refresh_frontend false ;;
  start) start_app ;;
  restart) stop_app; start_app ;;
  stop) stop_app ;;
  stop-all)
    stop_app
    NATIVE_QA_MYSQL_PORT="$MYSQL_PORT" "$DOCKER_BIN" compose -p xianyusmart-native-qa -f "$COMPOSE_FILE" stop mysql
    ;;
  status) status ;;
  logs) tail -n "${2:-120}" "$LOG_FILE" ;;
  *)
    echo "Usage: scripts/native-qa.sh {deploy|deploy-backend|frontend|frontend-fast|start|restart|stop|stop-all|status|logs [lines]}" >&2
    exit 2
    ;;
esac
