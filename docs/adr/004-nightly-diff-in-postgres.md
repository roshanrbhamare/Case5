# ADR 004: Nightly diff with streamed staging, bucket digests and set-based apply

**Status:** accepted

**Context.** Compare a 200,000-row export with the store in under 60 s, with bounded memory, and never apply a broken file.

**Decision.**
1. **Stage:** stream the request body through Commons CSV → validate → hash → PostgreSQL `CopyManager.copyIn` into `reconcile_staging`, in 256 KB chunks. JVM memory stays flat whatever the file size. Then `ANALYZE` the staging table so the planner has fresh statistics.
2. **Analyze:** compute `md5(string_agg(id:hash:version))` per bucket (256 buckets by FNV-1a of the id) for both sides. Identical buckets are skipped. Rows are compared only in buckets that differ, through indexed joins. The missing count is one anti-join. The safety brake decides before anything is written.
3. **Apply:** per group of 8 buckets, one transaction: set-based insert of joiners, set-based update of newer versions, set-based deactivation of leavers, each writing its outbox rows in the same statement. Rare cases (rehires, creation races) go through the per-record domain path inside savepoints.

**Measured:** see `backend/bench/RESULTS.md` (`ReconcileBenchmark`).

**Alternatives rejected:**
- *Sort-merge of two sorted streams in the application:* needs the file sorted by id, which a real export isn't.
- *Loading both sides into a hash map:* about 200k objects per side; memory grows with the client.
- *Per-record apply only:* an early version did this; at about 6 round trips per row it took 28 s for 4,000 changes. Set-based statements cut that to about 6 s.
- *One COPY message per row:* 200k tiny socket writes; buffering into 256 KB chunks made staging about 5× faster.

**Trade-off:** set-based apply duplicates a little logic in SQL (changed-field detection, event payload shape). Integration tests compare the events produced by both paths so they can't drift silently.
