# Fuzz targets

Every native parser entry point gets a `cargo fuzz` target, run for at least 60 s per
milestone per `docs/agent/MASTER_PLAN.md` section 3.4. Empty until the first parser
(M3) exists to fuzz — `cargo fuzz init` runs then, not before.
