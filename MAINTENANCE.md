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
