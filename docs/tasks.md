# SPEAR: observed username history

1. **Spec**: SEEN-01 through SEEN-06 in `requirements.md`; existing `/seen`, profile writes, recovery, SQLite and MySQL schema inspected.
2. **Prove**: Add focused migration, rename, and ambiguous-name tests; run before implementation and record the failure.
3. **Engine**: Add durable history storage and async `/seen` lookup and presentation.
4. **Arch**: Keep history writes atomic with profile and recovery-batch writes; preserve UUID identity and server-thread boundaries.
5. **Refine**: Run focused and complete tests, package the test JAR, and record limits of live validation.
