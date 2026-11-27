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

## Seeing what's left

- **Ghost blocks.** Every block still to place shows in the world as a see-through copy of the real block, like a
  schematic mod: bright where it can go in right now, faint where it still needs something beside it first. The next
  block to place has a pulsing cyan outline with a short stalk above it. Wrong blocks in the farm's cells are red
  boxes (break them), blocks that don't belong anywhere in the farm (old scaffolding, a misplaced block) are purple
  boxes (take them out), and scaffolding the auto-builder left behind is outlined in amber. The **Ghosts** button on
  the screen switches between everything left, only what can go in now, and off (`/totem ghosts` too).
- **What's left, on the screen** (`/totem`, or Mod Menu → Totem Builder → Config): a bar for each part of the farm
  (floor and booth, villager room, support columns, drop walls, pad and lake, water, walls, islands) and every block
  still needed as its item, with how many you carry next to it, red where you're short. Hover one for the exact count.
- **Top left while you build:** how far along it is, the next block with its item icon and which way it is, how many
  wrong or stray blocks there are, and what you're short of.

## On foot: Auto (walk + scaffolding)

No flying needed. Stand on the farm or on a block beside it (or be in the water next to it), have the materials, a
stack or so of your scaffolding block and a pickaxe, and press **Auto: walk + scaffolding** (or `/totem walk`). It
plans the farm where you are if there's none yet, then builds it all as a player would: it walks sneaking (so it
never steps off an edge), steps up, pillars up one column beside the booth with your scaffolding block, bridges
across to reach the rest, and lets the printer put in every farm block within reach, layer by layer from the bottom so
it never shuts itself in. It breaks wrong and stray blocks it finds in the farm, pours the lake, finishes the walls
and islands, then takes every bit of its scaffolding away again, top down, riding its pillar back to the bottom.
It eats when it's hungry, and it stops and says what's missing if you run out of something; start it again and it
carries on from where it is. Once the lake is in, if it's ever left with no way down it steps off the edge into the
sea (landing in water does no damage) and climbs back up.

**Scaffolding block:** cobblestone unless you change it with the **Scaffold** button on the screen (it cycles through
cobblestone, netherrack, dirt, cobbled deepslate and other plain blocks), or hold the block you want and press
**Held** (or `/totem scaffold`). It uses a few hundred over the build and takes them all back up.

Its clicks go at the printer's speed (`/totem speed`) and it turns your view to look at what it clicks, the tick
before, so a server sees nothing a player couldn't do. `/totem stop` stops it.

## Flying: /totem auto

Have the materials on you (and your flight, e.g. elytra fly, switched on), type `/totem auto` and sit back. It looks
for land in range; if there is any it flies away from it until there's nothing within 107 blocks, plans the farm
there, then flies from block to block while the printer places them. When the water buckets run out it flies down to
the sea, fills the empty ones and goes back. It stops when only the villager is left, and tells you where it goes.
If you run out of something it stops and says what; fill up and `/totem auto` again carries on. `/totem stop` stops
it. It flies by turning your view and holding your movement keys (forward, jump to go up, sneak to go down), and if it
touches down it takes off again (jump, then jump in the air).

`/totem` and all its commands stay on your side: they never go to the server or its chat.

## Building it by hand

1. Fly out over open ocean, at least 107 blocks from any land, island or building, and press **Plan the farm here** in the Mod Menu screen (or
   `/totem here`). The villager's bed goes right under you; the floor sits on the sea and the pad 105 blocks up.
2. It checks for land straight away: anything within 107 blocks that a raider could spawn on would steal the raids. It
   lists what it found (red lines in the world) or says the spot is good. Columns that aren't loaded yet are counted;
   fly round and `/totem check` again.
3. Press **Printer** in the Mod Menu screen to start it. Fly round the farm: every missing block within reach is placed, nearest
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

`/totem walk` · `/totem stop` · `/totem ghosts` · `/totem scaffold` · `/totem here` · `/totem check` · `/totem print` · `/totem materials` · `/totem villager` · `/totem status` ·
`/totem speed 1-20` (blocks a second, 5 by default: the rate of holding right-click, which servers with anti-cheat
accept; much faster can get you kicked) · `/totem forget`

No keys by default: everything is on the **Mod Menu → Totem Builder → Config** screen (plan here, printer on/off,
check the spot, materials, villager spot, forget). Two unbound keys exist in Controls if you ever want them.

## Tests

- `./gradlew test`: the plan is sound (the pad covers the spawn search's last ring, nothing above it, the fall is
  lethal, the pad within the search height).
- `./gradlew runRaidTest -PtbRaids=10`: builds the farm straight into a Hard test world high in the air and runs real
  raids with Bad Omen V: waves cleared, raiders stuck or lost, totems, the villager.
- `./gradlew runAutoTest`: a sea with an island where you start; types /totem auto once and checks it flies clear of
  the island, builds every block, fills its empty buckets at the sea and lets go of the keys.
- `./gradlew runSelfTest`: survival, flying, with the materials: plans the farm, checks for land (and finds a block put
  out at sea), prints the whole farm block for block, seals the villager in, then runs a real raid on what it built.
