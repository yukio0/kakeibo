#!/usr/bin/env bash

set -Eeuo pipefail

readonly SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly BACKUP_SCRIPT="$SCRIPT_DIR/backup-postgres.sh"

backend_stopped=false

fail() {
  echo "エラー: $*" >&2
  exit 1
}

compose() {
  docker compose --project-directory "$PROJECT_ROOT" "$@"
}

is_service_running() {
  compose ps --status running --services | grep -Fxq "$1"
}

restart_backend() {
  if [[ "$backend_stopped" == "true" ]]; then
    echo "backendを再起動します。"
    compose start backend
    backend_stopped=false
  fi
}

restart_backend_after_failure() {
  if [[ "$backend_stopped" == "true" ]]; then
    echo "復元処理が失敗したためbackendを再起動します。" >&2
    compose start backend || echo "警告: backendの再起動に失敗しました。手動で起動してください。" >&2
  fi
}

usage() {
  echo "使い方: bash scripts/restore-postgres.sh <バックアップdumpファイル>" >&2
  exit 2
}

if [[ $# -ne 1 ]]; then
  usage
fi

dump_file="$1"

if ! command -v docker >/dev/null 2>&1; then
  fail "docker コマンドが見つかりません。"
fi

if [[ ! -f "$dump_file" || ! -s "$dump_file" ]]; then
  fail "バックアップdumpファイルが見つからないか、空です: $dump_file"
fi

if [[ ! -f "$BACKUP_SCRIPT" ]]; then
  fail "事前バックアップ用スクリプトが見つかりません: $BACKUP_SCRIPT"
fi

if ! is_service_running "postgres"; then
  fail "PostgreSQLコンテナが起動していません。先に docker compose up -d postgres を実行してください。"
fi

if ! compose exec -T postgres pg_restore --list <"$dump_file" >/dev/null; then
  fail "指定されたファイルは復元可能なPostgreSQLダンプではありません。"
fi

echo "警告: 現在の家計簿データを削除し、次のダンプで置き換えます。"
echo "対象: $dump_file"
echo "復元前に現在のデータを backups/ へバックアップします。"
read -r -p "続行するには RESTORE と入力してください: " confirmation || fail "確認入力を受け取れませんでした。"

if [[ "$confirmation" != "RESTORE" ]]; then
  echo "復元を中止しました。"
  exit 0
fi

echo "復元前のバックアップを作成します。"
if ! bash "$BACKUP_SCRIPT"; then
  echo "警告: 復元前のバックアップに失敗しました。" >&2
  read -r -p "バックアップなしで復元するには、もう一度 RESTORE と入力してください: " backup_failure_confirmation || fail "確認入力を受け取れませんでした。"

  if [[ "$backup_failure_confirmation" != "RESTORE" ]]; then
    echo "復元を中止しました。"
    exit 0
  fi
fi

if is_service_running "backend"; then
  echo "データ更新を止めるためbackendを停止します。"
  compose stop backend
  backend_stopped=true
fi

trap restart_backend_after_failure EXIT

echo "データベースを復元します。"
compose exec -T postgres sh -c 'exec pg_restore -U "$POSTGRES_USER" -d "$POSTGRES_DB" --clean --if-exists --no-owner --no-privileges --exit-on-error' <"$dump_file"

restart_backend
trap - EXIT

echo "復元が完了しました。"
