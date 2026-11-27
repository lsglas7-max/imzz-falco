#!/usr/bin/env bash
# Push proyek ke GitHub lalu jalankan workflow "Build IMZYY XL LITE (ARM64)".
# Pakai: GH_USER=lsglas7-max GH_TOKEN=ISI_TOKEN bash scripts/trigger-github-run.sh [nama-repo]
set -Eeuo pipefail
GH_USER="${GH_USER:-lsglas7-max}"
GH_TOKEN="${GH_TOKEN:-ISI_TOKEN}"
REPO="${1:-imzyy-xl-lite}"
[[ "$GH_TOKEN" == "ISI_TOKEN" ]] && { echo "Isi GH_TOKEN dulu (ganti ISI_TOKEN)." >&2; exit 2; }
cd "$(dirname "${BASH_SOURCE[0]}")/.."
api() { curl -fsS -H "Authorization: Bearer $GH_TOKEN" -H "Accept: application/vnd.github+json" "$@"; }
api "https://api.github.com/repos/$GH_USER/$REPO" >/dev/null 2>&1 || \
  api -X POST https://api.github.com/user/repos -d "{\"name\":\"$REPO\",\"private\":false}" >/dev/null
[[ -d .git ]] || git init -b main
git add -A
git -c user.name=IMZYY -c user.email=imzyy@users.noreply.github.com commit -m "IMZYY XL Lite fix" || true
git push -f "https://$GH_USER:$GH_TOKEN@github.com/$GH_USER/$REPO.git" HEAD:main
api -X POST "https://api.github.com/repos/$GH_USER/$REPO/actions/workflows/android-lite.yml/dispatches" -d '{"ref":"main"}' || true
echo "Buka: https://github.com/$GH_USER/$REPO/actions"
