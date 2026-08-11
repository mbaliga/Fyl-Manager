# WP-0.2 — `workflow_dispatch` on android.yml

**Status: DONE.**

Added `workflow_dispatch:` to `.github/workflows/android.yml`'s trigger list, so Android CI can
be run manually from the Actions tab — previously it was the only workflow in the constellation
that couldn't be, which made the WP-0.1 diagnosis harder than it needed to be.

Confirmed `.github/workflows/release-readiness.yml` already carries `workflow_dispatch:`; no
change needed there.

**Next:** nothing. One-line change, no behavioral risk.
