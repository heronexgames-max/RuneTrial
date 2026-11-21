# RuneTrial

A timed, four-wave PvE arena for one to four players, with unlockable runes, scaling enemy counts and a ravager finale. Trial progress is shown on a boss bar, and players can check their persistent personal best.

## Build and install

Run `mvn clean verify` with Java 21. Copy the JAR from `target/` into the `plugins/` folder on Paper 1.21.11. GitHub Actions also packages a JAR when the workflow succeeds.

1. On a non-Peaceful world, build an enclosed arena with a flat floor, clear spawn points and adequate space inside 14 blocks of the center.
2. Stand at its center with the operator permission `runetrial.admin`, then run `/rune setarena`.
3. Have one to four Survival players enter the arena and run `/rune join`. One queued player runs `/rune start`.
4. Clear the four waves within four minutes. Use `/rune best` to check your fastest clear or `/rune leave` to exit the active party.

Earn Mend, Ward and Pulse at 3, 6 and 12 monster kills. Runes cost regenerating mana and each has its own cooldown. The ravager calls reinforcements when alive at half health or lower. Death during an active run gives a checkpoint respawn while the encounter is active. Normal server death and inventory rules apply.

## Demo

Run the encounter with two players. Show the countdown, cast a rune, trigger the ravager reinforcements and finish the wave. Check your personal best after restarting. On a second run, leave the arena or wait for timeout and check that trial mobs and the boss bar disappear. Test outsider melee and projectile damage and check that a death during an active run respawns at the center.
