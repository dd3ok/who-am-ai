#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT_DIR"

if [[ -z "${GEMINI_API_KEY:-}" || -z "${MONGO_URI:-}" ]]; then
  echo "GEMINI_API_KEY 또는 MONGO_URI가 설정되지 않았습니다." >&2
  exit 1
fi

THRESHOLDS=(0.62 0.65 0.68)
MAX_PROMPTS="${BENCHMARK_MAX_PROMPTS:-5}"
for threshold in "${THRESHOLDS[@]}"; do
  echo "\n===== BENCHMARK threshold=${threshold} ====="
  GRADLE_USER_HOME="${GRADLE_USER_HOME:-$ROOT_DIR/.gradle}" \
    ./gradlew test \
    --tests "*RagThresholdBenchmarkTest" \
    -Drag.search.similarity-threshold="$threshold" \
    -Dbenchmark.threshold="$threshold" \
    -Dbenchmark.max-prompts="$MAX_PROMPTS" \
    ${BENCHMARK_EXTRA_ARGS:-}
done
