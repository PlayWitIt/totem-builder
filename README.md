# Totem Builder

A client-side Fabric mod for Minecraft 1.21.11. It builds a raid farm for totems of undying over the ocean while you fly
round it (an elytra, or any other flight), and shows you exactly where the villager goes. Every raider that comes dies
by itself: there's nothing to kill.

## The farm

- **Spawn pad** 95 blocks above the villager's bed. Over the open ocean the game finds nowhere else to spawn raiders,
  so every wave appears on the pad.
- **Lake and drop.** The pad sits in a shallow lake that flows into a corner of four open fence gates. Raiders walk off
  the pad towards an island they can never reach, the current carries them through the gates, and they fall 105
  blocks onto the slab floor. The fall kills everything, ravagers too (100 health, 102 damage).
- **Totems and ominous bottles** drop whatever killed the mob, so they all land on the floor. Walk out of the booth
  between raids and pick them up.
- **Rate.** On Hard a raid with Bad Omen II or more has 8 waves and 8 evokers: 8 totems. A raid takes about 6 to 7
  minutes from drinking the bottle to the last wave, so roughly 70 totems an hour while you have bottles. On Normal a
  raid has only 2 evokers, and on Easy none.
- **Bottles run out.** Only pillager captains drop ominous bottles, and only in waves without vindicators, so a raid
  gives back about 2 for every 3 you drink. Bring bottles from trial chambers or patrols to keep it going.
- **What it takes:** about 1,320 cobblestone, 170 glass, 94 cobblestone slabs, 8 oak fence gates, 1 bed and 6 water
  buckets' worth of water (`/totem materials` gives the exact count still to place). You don't need six buckets:
  bring empty ones too, and when the full ones run out it fills the empties at any water in reach that isn't the
  farm's (fly down to the sea, or make a little pool by the lake) and carries on.

In testing (real raids on Hard with Bad Omen V, in a test world) every raid was won with 7 or 8 totems, the villager
was never hurt and no raider ever got out of the farm. About one raid in ten leaves a single raider wedged at the top
for a while; the screen tells you when that happens and where (see below).

## Building it

1. Fly out over open ocean, at least 107 blocks from any land, island or building, and press **Numpad 9** (or
   `/totem here`). The villager's bed goes right under you; the floor sits on the sea and the pad 105 blocks up.
2. It checks for land straight away: anything within 107 blocks that a raider could spawn on would steal the raids. It
   lists what it found (red lines in the world) or says the spot is good. Columns that aren't loaded yet are counted;
   fly round and `/totem check` again.
3. Press **Numpad 8** to start the printer. Fly round the farm: every missing block within reach is placed, nearest
   first, from anywhere in your inventory. The first block goes into thin air (nothing else is there over the sea);
   after that everything is placed against a neighbour. The bed is placed facing the right way, the fence gates are
   placed and opened, and the water goes in last, once every block is in, one source at a time.
4. The top left says how far along it is and where to fly next (`Next: ↗ 23 m, 40 up`). Missing blocks nearby are
   outlined in their colour, wrong blocks in red (break those).

## The villager

The villager's room is a two-block cell around a bed, 11 blocks above the sea, under the pad. It's shown as a **green
box with a green beam** going up from it, and `/totem villager` gives the coordinates. There's a two-high gap in the
room's north wall with a ledge outside it to bring the villager in from. Once a villager is inside, the printer fills
the gap. Wait for green sparkles over it: then it has claimed the bed and raids will work. Use one villager only, with
no job block anywhere near.

## Running it

Stand in the glass booth on the floor and drink an ominous bottle. The raid starts 30 seconds later; waves fall onto
the floor next to you. Between raids walk through the booth's door and collect the drops (they last 5 minutes on the
ground). Drink the next bottle when the boss bar is gone.

If a raider stays up top for over 40 seconds the screen says so in red, with where it is: the raid waits for it. Fly up
and finish it.

Once the farm is built and the villager sealed in, nothing shows on screen unless a raider is stuck.

## Commands

`/totem here` · `/totem check` · `/totem print` · `/totem materials` · `/totem villager` · `/totem status` ·
`/totem speed 1-8` (blocks a tick, 2 by default) · `/totem forget`

Keys: **Numpad 8** printer on/off, **Numpad 9** plan the farm here (or the status once it's planned). Change them in
Controls.

## Tests

- `./gradlew test`: the plan is sound (the pad covers the spawn search's last ring, nothing above it, the fall is
  lethal, the pad within the search height).
- `./gradlew runRaidTest -PtbRaids=10`: builds the farm straight into a Hard test world high in the air and runs real
  raids with Bad Omen V: waves cleared, raiders stuck or lost, totems, the villager.
- `./gradlew runSelfTest`: survival, flying, with the materials: plans the farm, checks for land (and finds a block put
  out at sea), prints the whole farm block for block, seals the villager in, then runs a real raid on what it built.
