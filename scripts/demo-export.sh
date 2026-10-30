#!/usr/bin/env bash
# Writes a nightly-export CSV built from the current roster, for demos.
#
#   scripts/demo-export.sh --keep 0.6 --out demo/acme-truncated.csv            (cut off like a broken export)
#   scripts/demo-export.sh --change 4 --join 2 --leave 1 --out demo/acme-normal.csv
#
# Options: --tenant (default acme), --keep fraction of rows (1), --change N shift changes,
# --join N new joiners, --leave N leavers (taken from the end), --out file.
# Reads through the running Postgres container (docker compose), demo data only.
set -euo pipefail

tenant=acme keep=1 change=0 join=0 leave=0 out=demo/export.csv
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tenant) tenant="$2"; shift 2 ;;
    --keep) keep="$2"; shift 2 ;;
    --change) change="$2"; shift 2 ;;
    --join) join="$2"; shift 2 ;;
    --leave) leave="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) echo "unknown option: $1" >&2; exit 1 ;;
  esac
done

cd "$(dirname "$0")/.."
mkdir -p "$(dirname "$out")"

docker compose exec -T postgres psql -U rostersync -d rostersync -q -v ON_ERROR_STOP=1 \
  -v t="$tenant" -v keep="$keep" -v change="$change" -v joiners="$join" -v leave="$leave" > "$out" <<'SQL'
COPY (
  WITH active AS (
    SELECT e.*, row_number() OVER (ORDER BY employee_id) AS rn, count(*) OVER () AS total
      FROM employees e WHERE tenant_id = :'t' AND status = 'ACTIVE'
  ), kept AS (
    SELECT employee_id, full_name, email, phone, manager_id, office_code,
           CASE WHEN rn BETWEEN 2 AND :change + 1 THEN CASE WHEN shift_code = 'NIGHT' THEN 'S1000' ELSE 'NIGHT' END ELSE shift_code END AS shift_code,
           CASE WHEN rn BETWEEN 2 AND :change + 1 THEN hr_version + 1 ELSE hr_version END AS version,
           rn
      FROM active WHERE rn <= total - :leave
  ), joiners AS (
    SELECT 'J' || to_char(now(), 'HH24MISS') || g, 'New Joiner ' || g,
           'j' || to_char(now(), 'HH24MISS') || g || '@' || :'t' || '.example', '+9199000' || (10000 + g), NULL,
           (SELECT code FROM offices WHERE tenant_id = :'t' ORDER BY code LIMIT 1), 'S0900', 1, 1000000 + g
      FROM generate_series(1, :joiners) g
  ), all_rows AS (
    SELECT * FROM kept UNION ALL SELECT * FROM joiners
  )
  SELECT employee_id, full_name, email, phone, manager_id, office_code, shift_code, version
    FROM all_rows ORDER BY rn
   LIMIT (SELECT ceil(count(*) * :keep) FROM all_rows)
) TO STDOUT WITH (FORMAT csv, HEADER);
SQL

echo "wrote $(($(wc -l < "$out") - 1)) rows to $out"
