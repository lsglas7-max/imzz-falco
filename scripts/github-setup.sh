#!/usr/bin/env bash
# One-shot: create a GitHub repo for this project, push it, and (optionally) tag a release.
# Usage: bash scripts/github-setup.sh <repo-name> [public|private] [--release v3.0.0]
# Needs: git, and GitHub CLI `gh` already logged in (`gh auth login`).
set -Eeuo pipefail
NAME="${1:?usage: github-setup.sh <repo-name> [public|private] [--release vX.Y.Z]}"
VIS="${2:-public}"
command -v git >/dev/null || { echo "git not found" >&2; exit 2; }
command -v gh  >/dev/null || { echo "GitHub CLI 'gh' not found: https://cli.github.com" >&2; exit 2; }
gh auth status >/dev/null 2>&1 || { echo "Run 'gh auth login' first" >&2; exit 2; }
cd "$(dirname "${BASH_SOURCE[0]}")/.."
[[ -d .git ]] || git init -b main
git add -A
git -c user.name="${GIT_AUTHOR_NAME:-IMZYY}" -c user.email="${GIT_AUTHOR_EMAIL:-imzyy@users.noreply.github.com}" commit -m "IMZYY XL Launcher v3" || true
gh repo create "$NAME" "--$VIS" --source=. --remote=origin --push
if [[ "${3:-}" == "--release" && -n "${4:-}" ]]; then
  git tag "$4" && git push origin "$4"
  echo "Tag $4 pushed: Actions will build and attach the APK to the Release."
fi
echo "Done. Open: $(gh repo view --json url -q .url)/actions"
