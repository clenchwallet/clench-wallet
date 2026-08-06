#!/usr/bin/env bash
set -euo pipefail

CASES="${1:-${CLENCH_FUZZ_CASES:-20000}}"
if [[ ! "$CASES" =~ ^[0-9]+$ ]] || (( CASES < 64 || CASES > 20000 )); then
  printf 'Fuzz case count must be an integer from 64 through 20000.\n' >&2
  exit 2
fi

export CLENCH_FUZZ_CASES="$CASES"

./gradlew \
  --no-daemon \
  --no-build-cache \
  --rerun-tasks \
  --dependency-verification=strict \
  :app:testDebugUnitTest \
  --tests net.clench.wallet.verification.HostileFuzzExecutionContractTest \
  --tests net.clench.wallet.security.PsbtSafetyPropertyTest \
  --tests net.clench.wallet.security.ExternalSignaturePolicyTest \
  --tests net.clench.wallet.ui.components.BBQrHostilePropertyTest \
  --tests net.clench.wallet.ui.components.CoinkiteProtocolHostileTest \
  --tests net.clench.wallet.data.repository.MultisigDescriptorHostilePropertyTest \
  --tests net.clench.wallet.data.repository.StorageRecoveryHostilePropertyTest \
  --tests net.clench.wallet.verification.FeeAttackPropertyTest \
  --tests net.clench.wallet.ui.viewmodel.PsbtStoreInterruptionTest

CONTRACT_REPORT="app/build/test-results/testDebugUnitTest/TEST-net.clench.wallet.verification.HostileFuzzExecutionContractTest.xml"
EXPECTED_MARKER="CLENCH_HOSTILE_FUZZ_EXECUTED=${CASES};"
if [[ ! -f "$CONTRACT_REPORT" ]] || ! grep -Fq "$EXPECTED_MARKER" "$CONTRACT_REPORT"; then
  printf 'Hostile fuzz lane did not execute the requested %s cases.\n' "$CASES" >&2
  exit 1
fi
printf 'Verified hostile fuzz execution count: %s cases.\n' "$CASES"
