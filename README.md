# RuneTrial

A timed, four-wave PvE arena for one to four players, with unlockable runes, scaling enemy counts and a ravager finale. Trial progress is shown on a boss bar, and players can check their persistent personal best.

## Build and install

Run `mvn clean verify` with Java 21. Copy the JAR from `target/` into the `plugins/` folder on Paper 1.21.11. GitHub Actions also packages a JAR when the workflow succeeds.

1. On a non-Peaceful world, build an enclosed arena with a flat floor, clear spawn points and adequate space inside 14 blocks of the center.
2. Stand at its center with the operator permission `runetrial.admin`, then run `/rune setarena`.
3. Have one to four Survival players enter the arena and run `/rune join`. One queued player runs `/rune start`.
4. Clear the four waves within four minutes. Use `/rune best` to check your fastest clear or `/rune leave` to exit the active party.

Earn Mend, Ward and Pulse at 3, 6 and 12 monster kills. Runes cost regenerating mana and each has its own cooldown. The ravager calls reinforcements when alive at half health or lower. A death spends one shared revive for a checkpoint respawn while the encounter is active. Normal server death and inventory rules apply.

## Demo

Run the encounter with two players. Show the countdown, cast a rune, trigger the ravager reinforcements and finish the wave. Check your personal best after restarting. On a second run, leave the arena or wait for timeout and check that trial mobs and the boss bar disappear. Test outsider melee and projectile damage and check that a death during an active run respawns at the center.

## Difficulty and boss mechanics

Choose a mode with `/rune start <normal|veteran|nightmare>`. Use `/rune best <mode>` to see your record for that difficulty. Old personal bests remain under Normal.

| Mode | Time limit | Shared revives | Wave scaling bonus |
|---|---:|---:|---:|
| Normal | 4:00 | 4 | 0 |
| Veteran | 3:30 | 2 | 1 |
| Nightmare | 3:00 | 1 | 2 |

The ravager marks a four-block area with a flame ring three seconds before a slam. The hit is centered where the warning appeared. The boss slams more frequently after calling reinforcements. Revives are shared across the party and displayed on the boss bar. Players who die after the revive budget runs out leave the active party.

For a demo, start Nightmare with two players. Dodge the flame ring, then show one player spending the shared revive and the other being eliminated after it runs out.
