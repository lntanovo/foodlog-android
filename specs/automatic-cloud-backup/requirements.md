# Automatic cloud backup / 自动云备份

The user explicitly requested implementation: save automatically after records change and restore on another device after signing into the same account. These acceptance boundaries extend the existing approved account/backup design; proceed directly without a separate planning approval.

1. After a successful local commit, signed-in users get an automatic, durable upload queue; rapid edits coalesce. Offline work survives process death and retries with connectivity.
2. On sign-in/start, inspect cloud data before uploading. A pristine installation restores the latest verified full backup automatically. Never upload an empty installation over existing cloud records.
3. A previously associated device with no unsent edits may receive a newer cloud snapshot automatically. Concurrent changes on both sides, different accounts, or existing unassociated records require an explicit choice; preserve both histories.
4. Never clear local records on login/logout/network failure; verify owner, size, SHA-256, schema and summaries; save a recovery copy and reject stale local revisions before restoration.
5. Settings show waiting, uploading, restored, up-to-date, needs-login and conflict states. Manual history/preview/export remain available. Background restrictions can delay work; no promise of instant upload after force-stop.
6. Publish a same-package/same-signature APK and bilingual source/release documentation; preserve all older releases.
