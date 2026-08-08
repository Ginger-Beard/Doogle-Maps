#!/usr/bin/env python3
"""
Fetches a chathead sprite for every farmer who protects a patch, and names them.

Why this exists at all: a protected patch used to be marked with a generic green shield,
identical on all 49 of them, when FarmPatch already knows *which* farmer is owed for that
plot. Chatheads are 3D model renders rather than sprites, so there is nothing to ask
ItemManager for and no way to render one to a Swing icon at runtime — the RuneLite API
exposes NPCComposition.getChatheadModels() and Client.loadModel(), but nothing that
rasterises a Model. So the images have to be bundled, which means fetching them here.

Nothing is hand-maintained. The farmer NPC ids come out of the generated
FarmingWorldData, their numeric values out of the runelite-api jar Gradle already
downloaded, and their names and portraits off the wiki by searching for the id. A farmer
who moves ids, or an npc whose page stops declaring one, shows up as a miss in the report
rather than as a silently wrong face.

Usage:
    python3 tools/fetch_chatheads.py [--dry-run]

Writes:
    src/main/resources/com/dooglemaps/chatheads/<npcId>.png
    src/main/java/com/dooglemaps/data/Farmers.java
    tools/chatheads.tsv        provenance: id, constant, name, wiki file

Chathead art is Jagex's. It is bundled the way other Plugin Hub plugins bundle game
sprites; see ATTRIBUTION.md.
"""

import argparse
import glob
import json
import os
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
WORLD_DATA = os.path.join(ROOT, "src", "main", "java", "com", "dooglemaps", "data", "FarmingWorldData.java")
IMAGE_DIR = os.path.join(ROOT, "src", "main", "resources", "com", "dooglemaps", "chatheads")
JAVA_OUT = os.path.join(ROOT, "src", "main", "java", "com", "dooglemaps", "data", "Farmers.java")
TSV_OUT = os.path.join(ROOT, "tools", "chatheads.tsv")

API = "https://oldschool.runescape.wiki/api.php"
UPLOAD = "https://oldschool.runescape.wiki/images/"

# The wiki asks third-party tools to identify themselves and rate-limit.
USER_AGENT = "doogle-maps-chathead-fetch/1.0 (RuneLite plugin build tool)"
DELAY_SECONDS = 0.4


def get(url):
    request = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read()


def api(**params):
    params.setdefault("format", "json")
    time.sleep(DELAY_SECONDS)
    return json.loads(get(API + "?" + urllib.parse.urlencode(params)))


# Faces this plugin shows that are not any patch's farmer, so FarmingWorldData does not
# name them. Guildmaster Jane assigns the farming contract and takes it back, and the
# contract's tab is hers rather than a patch sprite — there is one contract, it is a job she
# gave you, and every other tab in the strip is already a crop icon.
#
# Grouped, because she is three NpcID constants for one person and the wiki only knows two
# of them: its infobox declares 8586 and 8587, while the id RuneLite matches her dialogue
# chathead on is 8628. Verifying against *any* id in a group is what lets the page be found
# at all, and writing the sprite under *every* id is what makes the lookup work whichever one
# the game happens to report — which is not a thing this tool can find out, and not a thing
# worth an aliasing table at runtime for three copies of a four-kilobyte PNG.
#
# Listed here rather than fetched by hand so she is still resolved, named and recorded in
# chatheads.tsv by the same path as the gardeners: a wiki lookup by id, which fails loudly if
# she ever moves rather than leaving a silently wrong face bundled.
EXTRA_NPC_GROUPS = [
    ("FARMING_GUILD_MASTER", "FARMING_GUILD_MASTER_1OP", "FARMING_GUILD_MASTER_2OP"),
]


def farmer_constants():
    """Every NpcID constant FarmingWorldData uses as a patch's farmer."""
    with open(WORLD_DATA, encoding="utf-8") as handle:
        source = handle.read()
    # The farmer is the fourth argument to the FarmPatch constructor, so anything else
    # referencing an NpcID would be picked up too. Nothing else does today, and a stray
    # one would only cost an extra lookup.
    return sorted(set(re.findall(r"NpcID\.([A-Z0-9_]+)", source)))


def constant_values():
    """Maps every NpcID constant to its number, straight out of the api jar."""
    jars = glob.glob(os.path.join(
        os.path.expanduser("~"), ".gradle", "caches", "modules-2", "files-2.1",
        "net.runelite", "runelite-api", "*", "*", "runelite-api-*.jar"))
    if not jars:
        sys.exit("no runelite-api jar in the Gradle cache - run ./gradlew build first")

    jar = sorted(jars)[-1]
    output = subprocess.run(
        ["javap", "-constants", "-classpath", jar, "net.runelite.api.gameval.NpcID"],
        capture_output=True, text=True, check=True).stdout

    values = {}
    for name, value in re.findall(r"int\s+([A-Z0-9_]+)\s*=\s*(-?\d+);", output):
        values[name] = int(value)
    return values


def find_page(npc_id):
    """
    The wiki page declaring this NPC id, and the chathead file on it.

    Searched by id rather than by name because the constants are things like
    FARMING_GARDENER_HOPS_3, which is not what the NPC is called. The search is only a
    shortlist — the id is confirmed against the page's own infobox before anything is
    downloaded, since `insource` also matches an item that happens to share the number.
    """
    # Four shortlists, most precise first, because each one fails differently.
    #
    # The quoted phrase is an ordinary indexed search and by far the most reliable — the
    # regex forms are scored and time-limited, so a page that plainly matches can still fall
    # outside the first few results (Ayesha, Imiago and Liliwen all did). The regex forms
    # then catch the spacing and versioning the phrase cannot: `id2 = ...` on a versioned
    # infobox, or an unusual gap around the equals sign. The bare number is a last resort
    # and matches drop tables and sound-id lists as readily as the page wanted — tolerable
    # only because every candidate is verified against its own infobox below.
    #
    # Note CirrusSearch's regex dialect has no \s, and matches nothing rather than
    # complaining about it. That is how this first reported all 49 farmers as missing.
    queries = [
        'insource:"id = %d"' % npc_id,
        r"insource:/id *= *%d/" % npc_id,
        r"insource:/id[0-9]* *= *%d/" % npc_id,
        r"insource:/%d/" % npc_id,
    ]

    seen = set()
    for query in queries:
        found = api(action="query", list="search", srsearch=query, srnamespace=0, srlimit=10)
        for hit in found.get("query", {}).get("search", []):
            title = hit["title"]
            if title in seen:
                continue
            seen.add(title)

            page = api(action="parse", page=title, prop="wikitext", redirects=1)
            text = page.get("parse", {}).get("wikitext", {}).get("*", "")

            if not re.search(r"\{\{\s*Infobox NPC", text, re.IGNORECASE):
                continue
            # `id = 2663` or `id2 = 2663,2664` - versioned infoboxes number their fields and
            # can list several ids in one.
            if not re.search(r"^\s*\|?\s*id\d*\s*=.*\b%d\b" % npc_id, text, re.MULTILINE):
                continue

            chathead = re.search(r"\[\[File:([^\]|]*chathead[^\]|]*\.png)", text, re.IGNORECASE)
            if not chathead:
                # The right page, but this NPC has no portrait - a squirrel or a Tortugan
                # rather than a person. Worth reporting as itself, not as "not found".
                return title, None
            return title, chathead.group(1).strip()
    return None, None


# What may pass from the wiki into this repo. Everything fetched here is attacker-writable
# in principle — the wiki is publicly editable — and two of the outputs are dangerous sinks:
# names are interpolated into Farmers.java as string literals (a quote in a page title would
# otherwise end the literal and compile whatever follows), and filenames become URL paths and
# local file writes. So each is checked against the shape the real data actually has, and a
# mismatch kills the run rather than being cleaned up: a farmer the wiki suddenly calls
# `Alan"); do_evil("` is a thing to look at, not to sanitise into `Alan do_evil`.
NAME_OK = re.compile(r"^[A-Za-z0-9 .'-]+$")
TITLE_OK = re.compile(r"^[A-Za-z0-9 .,'()-]+$")
CONSTANT_OK = re.compile(r"^[A-Z][A-Z0-9_]*$")
WIKI_FILE_OK = re.compile(r"^[A-Za-z0-9 .,'()_-]+\.png$", re.IGNORECASE)
PNG_MAGIC = b"\x89PNG\r\n\x1a\n"


def checked(pattern, value, what):
    if not pattern.match(value):
        sys.exit("refusing %s %r - not a shape this tool will write" % (what, value))
    return value


def wiki_image(filename):
    """Downloads a File: page's actual bits, following the wiki's own redirect for it."""
    checked(WIKI_FILE_OK, filename, "wiki filename")
    info = api(action="query", titles="File:" + filename, prop="imageinfo", iiprop="url")
    data = None
    for page in info.get("query", {}).get("pages", {}).values():
        for image in page.get("imageinfo", []):
            # The URL comes out of the API response, so it is held to the wiki's own host
            # rather than trusted - urlopen would follow it anywhere, file:// included.
            url = image["url"]
            if not url.startswith("https://oldschool.runescape.wiki/"):
                sys.exit("refusing image URL %r - not the wiki's own host" % url)
            data = get(url)
            break
        if data is not None:
            break
    if data is None:
        # Fall back to the predictable upload path, which works for most files.
        data = get(UPLOAD + urllib.parse.quote(filename.replace(" ", "_")))
    if not data.startswith(PNG_MAGIC):
        sys.exit("refusing %r - fetched bytes are not a PNG" % filename)
    return data


def display_name(title):
    """
    What the NPC is called, without the wiki's disambiguator.

    Page titles carry one when the name is shared — "Alan (Farming Guild)", "Squirrel
    (Fossil Island)". It exists to keep two articles apart, and repeating it in a tooltip
    that already names the patch would only say the same thing twice.
    """
    return re.sub(r"\s*\([^)]*\)\s*$", "", title).strip()


def java_source(farmers):
    lines = [
        "// GENERATED FILE - DO NOT EDIT BY HAND.",
        "// Regenerate with: python3 tools/fetch_chatheads.py",
        "//",
        "// Names and chathead sprites from the OSRS Wiki; the art is Jagex's.",
        "// See ATTRIBUTION.md.",
        "package com.dooglemaps.data;",
        "",
        "import java.util.Collections;",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import net.runelite.api.gameval.NpcID;",
        "",
        "/**",
        " * The gardeners you pay to protect a patch.",
        " *",
        " * <p>{@link FarmPatch#getFarmer()} holds an NPC id, which is the right thing to store"
        " and",
        " * useless to show. This turns it into a name, so a protected patch can say who was"
        " paid",
        " * rather than showing the same anonymous shield on all forty-nine of them.",
        " *",
        " * <p>Generated rather than typed out: the ids come from the mirrored world data and the"
        "",
        " * names from the wiki, so a farmer who is renamed or re-numbered is a regeneration"
        " rather",
        " * than a hand edit somebody has to remember to make.",
        " */",
        "public final class Farmers",
        "{",
        "\tprivate static final Map<Integer, String> NAMES = new HashMap<>();",
        "",
        "\tstatic",
        "\t{",
    ]
    for farmer in farmers:
        # Both halves are checked at the point of emission so the --from-tsv path is held
        # to the same rule as a fresh fetch: the constant lands in source unquoted and the
        # name lands inside a string literal, and neither may carry anything that could
        # read as Java. See NAME_OK.
        constant = checked(CONSTANT_OK, farmer["constant"], "NpcID constant")
        name = checked(NAME_OK, display_name(farmer["name"]), "farmer name")
        lines.append('\t\tNAMES.put(NpcID.%s, "%s");' % (constant, name))
    lines += [
        "\t}",
        "",
        "\tprivate Farmers()",
        "\t{",
        "\t}",
        "",
        "\t/** What this farmer is called, or null for an id we have no name for. */",
        "\t@Nullable",
        "\tpublic static String getName(int npcId)",
        "\t{",
        "\t\treturn NAMES.get(npcId);",
        "\t}",
        "",
        "\t/** Every farmer we can name, for tests that check the sprites line up. */",
        "\tpublic static Map<Integer, String> getAll()",
        "\t{",
        "\t\treturn Collections.unmodifiableMap(NAMES);",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--dry-run", action="store_true",
                        help="resolve everything and report, but write nothing")
    # A full pass is around 200 wiki requests. Changing how a name is *formatted* should not
    # cost that, and should not put the load on the wiki either.
    parser.add_argument("--from-tsv", action="store_true",
                        help="rebuild Farmers.java from chatheads.tsv without refetching")
    args = parser.parse_args()

    if args.from_tsv:
        with open(TSV_OUT, encoding="utf-8") as handle:
            rows = [line.rstrip("\n").split("\t") for line in handle][1:]
        farmers = [{"constant": r[0], "id": int(r[1]), "name": r[2],
                    "file": r[3] if len(r) > 3 else ""} for r in rows]
        with open(JAVA_OUT, "w", encoding="utf-8") as handle:
            handle.write(java_source(farmers))
        print("rebuilt %s from %d cached rows" % (JAVA_OUT, len(farmers)))
        return

    values = constant_values()
    constants = farmer_constants()

    farmers = []
    missing = []

    for constant in constants:
        if constant not in values:
            missing.append((constant, "no such NpcID constant"))
            continue

        npc_id = values[constant]
        title, chathead = find_page(npc_id)
        if not title:
            missing.append((constant, "no wiki page declaring id %d" % npc_id))
            continue

        # A name with no portrait is still worth having: the tooltip can say who is owed
        # even where the badge has to stay a shield.
        farmers.append({"constant": constant, "id": npc_id, "name": title, "file": chathead})
        print("%-40s %6d  %-28s %s"
              % (constant, npc_id, title, chathead or "(no chathead)"))

    for group in EXTRA_NPC_GROUPS:
        known = [(c, values[c]) for c in group if c in values]
        if not known:
            missing.append((group[0], "no such NpcID constant"))
            continue

        # Any id in the group will do to find the page — they are one NPC, and only some of
        # their ids are ones the wiki bothers to list.
        title = chathead = None
        for constant, npc_id in known:
            title, chathead = find_page(npc_id)
            if title:
                break

        if not title:
            missing.append((group[0], "no wiki page declaring any of %s"
                            % ", ".join(str(i) for _, i in known)))
            continue

        for constant, npc_id in known:
            farmers.append({"constant": constant, "id": npc_id, "name": title,
                            "file": chathead})
            print("%-40s %6d  %-28s %s"
                  % (constant, npc_id, title, chathead or "(no chathead)"))

    with_art = [f for f in farmers if f["file"]]
    print("\nnamed %d of %d, %d with a chathead"
          % (len(farmers), len(constants) + sum(len(g) for g in EXTRA_NPC_GROUPS),
             len(with_art)))
    for constant, why in missing:
        print("  MISSING %-38s %s" % (constant, why))

    if args.dry_run:
        return

    os.makedirs(IMAGE_DIR, exist_ok=True)
    for farmer in with_art:
        target = os.path.join(IMAGE_DIR, "%d.png" % farmer["id"])
        with open(target, "wb") as handle:
            handle.write(wiki_image(farmer["file"]))

    with open(JAVA_OUT, "w", encoding="utf-8") as handle:
        handle.write(java_source(farmers))

    with open(TSV_OUT, "w", encoding="utf-8") as handle:
        handle.write("constant\tid\tname\twiki_file\n")
        for farmer in farmers:
            # The title and filename are wiki-authored; held to the known shape here so a
            # tab or newline in either cannot smuggle extra columns into the provenance
            # file that --from-tsv trusts later.
            handle.write("%s\t%d\t%s\t%s\n"
                         % (checked(CONSTANT_OK, farmer["constant"], "NpcID constant"),
                            farmer["id"],
                            checked(TITLE_OK, farmer["name"], "page title"),
                            checked(WIKI_FILE_OK, farmer["file"], "wiki filename")
                            if farmer["file"] else ""))

    print("\nwrote %d sprites to %s" % (len(with_art), IMAGE_DIR))


if __name__ == "__main__":
    main()
