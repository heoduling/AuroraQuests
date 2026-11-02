# Midnight reroll and manual hot update: 2.5.0-6

Based on maintained 2.5.0-5 (d8df5a1275f30bd562ab09933515a7583d980d5e), using Aurora 2.6.0-13. QuestRollJob previously retained profile snapshots across quit cleanup and unconditionally dereferenced a missing player pool. Batch profile operations now route to each player's EntityScheduler and revalidate online/current-profile identity; stale pool definitions and absent player pools are skipped. Pool definitions publish atomically, and config reload cancels the previous periodic unlock task.

The only Quartz job reads JobExecutionContext directly and has no injected setters. SimpleJobFactory replaces unused property injection, avoiding JDK Bean introspection soft caches after actual job execution. No reflection into server internals or cache-wide clearing was added.

`clean check shadowJar` passed all 23 tests on Java25. Real old5 reproduced the exact line134 NPE under controlled snapshot/quit ordering. Local Shiroha/Mizuki26.2-DEV-28fe077, PlugManX3.1.0-Beta.2 and isolated MySQL tests passed with two clients in separate Folia regions: old5-to-new6 manual replacement, three Quests-only transitions, three config reloads, quit/rejoin, paired unload/manual load, menus and persisted completion/progress/count. Normal stop/cold restart passed. A new6-only JVM using default soft-cache policy collected both retired plugin instance and loader after real job execution and unload. Migration-era legacy5 soft-cache reclamation was tested separately with SoftRefLRUPolicyMSPerMB=0; this flag is not required for deployment. Local Mythic5.13.1-SNAPSHOT-6458f70e differs from the original supplied snapshot. No Linux or large-player stress claim.

No configuration/schema/reward changes. Main remains loaded for a Quests-only update: unload AuroraQuests, replace its JAR (one copy), then load AuroraQuests-2.5.0-6. Existing config/data stays. Tests/probes are separate from the plugin JAR. Evidence: audit/aurora-midnight-20261101; delivery: 交付/AuroraQuests-midnight-fix-20261101.

Build with an explicit maintained Main13 JAR:

```sh
./gradlew --offline --no-daemon clean check shadowJar -PauroraJar=/path/to/Aurora-2.6.0-13.jar
```

# Block tracker compatibility: 2.5.0-1

Based on upstream `0946f759d4b4785bd80ae4fc2c65da05bf33f0ac` (2.5.0).

Custom block-break, block-loot, special-crop loot and Nexo callbacks previously dereferenced a missing Aurora region manager when block tracking was disabled at startup. These callbacks now skip provenance-dependent processing when the manager is absent. An enabled manager retains the existing placed/natural checks. Normal crop harvesting, quest definitions, rewards, menus, commands and stored quest progress are unchanged.

With the supplied production build `AuroraQuests-2.5.0-b167-SNAPSHOT.jar`, all 393 existing classes outside the four guarded classes are byte-identical. Bundled YAML content is identical after normalizing line endings, except for the plugin version. `QuestData` and all database-facing serialization remain unchanged.

Build Aurora 2.6.0-1 first, then compile and test against that exact JAR using Java 25:

```sh
./gradlew --no-daemon clean check shadowJar "-PauroraJar=../Aurora/build/libs/Aurora-2.6.0-1.jar"
```

Use an ASCII build/output path on Windows if Java argument-file encoding prevents test classes from loading. Three regression cases cover the missing-tracker callbacks, enabled-tracker behavior, and quest progress/completion/unlock/roll-data serialization.

Keep the existing AuroraQuests directory and task files. This patch adds no configuration keys and performs no data migration. Tracking still needs to be enabled for objectives that rely on natural-block provenance.
