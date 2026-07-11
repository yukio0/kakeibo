#!/usr/bin/env bash

set -Eeuo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
PROJECT_ROOT="$(cd -- "$SCRIPT_DIR/.." && pwd)"
readonly PROJECT_ROOT
readonly DEFAULT_BACKUP_DIR="$PROJECT_ROOT/backups"

backup_dir="${BACKUP_DIR:-$DEFAULT_BACKUP_DIR}"
retention_days="${BACKUP_RETENTION_DAYS:-}"
timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
backup_file="$backup_dir/kakeibo-postgres-$timestamp.dump"
temporary_file="$backup_file.tmp"

fail() {
  echo "エラー: $*" >&2
  exit 1
}

cleanup_temporary_file() {
  rm -f -- "$temporary_file"
}

compose() {
  docker compose --project-directory "$PROJECT_ROOT" "$@"
}

if ! command -v docker >/dev/null 2>&1; then
  fail "docker コマンドが見つかりません。"
fi

if [[ -n "$retention_days" && ! "$retention_days" =~ ^[1-9][0-9]*$ ]]; then
  fail "BACKUP_RETENTION_DAYS は1以上の整数で指定してください。"
fi

if ! compose ps --status running --services | grep -Fxq "postgres"; then
  fail "PostgreSQLコンテナが起動していません。先に docker compose up -d postgres を実行してください。"
fi

umask 077
mkdir -p -- "$backup_dir"
trap cleanup_temporary_file ERR INT TERM

# $POSTGRES_USER/$POSTGRES_DB は postgres コンテナ内の sh で展開させるため、単一引用符のまま渡す(SC2016は意図的)。
# shellcheck disable=SC2016
compose exec -T postgres sh -c 'pg_isready -U "$POSTGRES_USER" -d "$POSTGRES_DB" >/dev/null'
# shellcheck disable=SC2016
compose exec -T postgres sh -c 'exec pg_dump -U "$POSTGRES_USER" -d "$POSTGRES_DB" --format=custom' >"$temporary_file"
compose exec -T postgres pg_restore --list <"$temporary_file" >/dev/null

mv -- "$temporary_file" "$backup_file"
trap - ERR INT TERM

if [[ -n "$retention_days" ]]; then
  find "$backup_dir" -maxdepth 1 -type f -name "kakeibo-postgres-*.dump" -mtime "+$retention_days" -delete
fi

echo "バックアップを作成しました: $backup_file"
