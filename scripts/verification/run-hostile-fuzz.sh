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
  --dependency-verification=strict \
  :app:testDebugUnitTest \
  --tests net.clench.wallet.security.PsbtSafetyPropertyTest \
  --tests net.clench.wallet.security.ExternalSignaturePolicyTest \
  --tests net.clench.wallet.ui.components.BBQrHostilePropertyTest \
  --tests net.clench.wallet.ui.components.CoinkiteProtocolHostileTest \
  --tests net.clench.wallet.data.repository.MultisigDescriptorHostilePropertyTest \
  --tests net.clench.wallet.data.repository.StorageRecoveryHostilePropertyTest \
  --tests net.clench.wallet.verification.FeeAttackPropertyTest \
  --tests net.clench.wallet.ui.viewmodel.PsbtStoreInterruptionTest
