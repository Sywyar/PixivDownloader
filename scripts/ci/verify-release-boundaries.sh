set -euo pipefail

if [ "${VERIFY_COMPOSE_TESTS:-false}" = true ]; then
  pwsh -NoProfile -File scripts/ci/verify-compose-tests.ps1
  pwsh -NoProfile -File scripts/ci/verify-plugin-sdk.ps1
fi

tests="DistributionPackagingBoundaryTest${ADDITIONAL_TESTS:+,$ADDITIONAL_TESTS}"
IFS=',' read -r -a selectors <<< "$tests"
mkdir -p pixivdownload-app/target/surefire-reports
for selector in "${selectors[@]}"; do
  class=${selector%%#*}
  find pixivdownload-app/target/surefire-reports -name "*$class.txt" -delete
done
mvn -B -ntp -pl pixivdownload-app -am surefire:test "-Dtest=$tests" -Dsurefire.failIfNoSpecifiedTests=false -Ddistribution.packaging.require-artifacts=true "-Ddistribution.packaging.require-production-credential-key=$REQUIRE_PRODUCTION_CREDENTIAL_KEY" -Dexec.skip=true
for selector in "${selectors[@]}"; do
  class=${selector%%#*}
  report=$(find pixivdownload-app/target/surefire-reports -name "*$class.txt" -print -quit)
  test -n "$report"
  grep -Eq 'Tests run: [1-9][0-9]*, Failures: 0, Errors: 0, Skipped: 0' "$report"
done
