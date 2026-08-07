# Reference images

Not bundled — nothing here is on the classpath. These are the inputs and the fallbacks for
`../JaneChathead.java`.

## Guildmaster Jane

`fetch_chatheads.py` pulls every chathead from the wiki, and for Jane the wiki render has
**no visible eyes**. At the 18–20px these are actually drawn at, eyes are most of what makes a
face recognisable, so hers read as a blank oval on the contract tab.

- `in_game_jane.png` — a screenshot of her chathead in the client, which is the authoritative look.
- `8628-wiki-chathead.png` — what the wiki gives, kept deliberately. It is a complete chathead
  with the whole hat, where the screenshot is cropped flat across the top. If the derived one ever
  looks worse than this, this is what to put back.

Regenerate with:

```
java tools/JaneChathead.java tools/reference/in_game_jane.png \
    src/main/resources/com/dooglemaps/chatheads/8628.png 97 95
```

Jane has three NPC ids — 8586, 8587 and 8628 — and `FarmerIcon` looks up by id, so all three files
have to be written. `fetch_chatheads.py` will overwrite them from the wiki again if it is re-run;
that is the one thing to watch.
