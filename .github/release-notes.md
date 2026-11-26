- **No more kicks for placing too fast.** It places 5 blocks a second by default, the rate of holding right-click
  (`/totem speed 1-20` changes it; it was 40 a second). It no longer sends extra look packets: where it looks for each
  block goes in the game's own movement packet, so anti-cheat sees a player holding right-click and turning.
- **Smoother flight.** It turns at most 25° a tick instead of snapping your view, only flies forward once it's facing
  about the right way, and lets go of up or down just before the right height, so it doesn't bob.
- **Auto-build no longer gets stuck** when a new block ends up across its way: it finds another way when it stops
  getting nearer, and after 3 tries goes for a different block.
