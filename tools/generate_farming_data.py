#!/usr/bin/env python3
"""
Generates Doogle Maps' mirrored farming data spine from RuneLite client sources.

RuneLite core's net.runelite.client.plugins.timetracking.farming package holds the
authoritative varbit -> patch-state mapping, but FarmingPatch, FarmingWorld and
PatchState are package-private, so an external Plugin Hub plugin cannot call them.
This script parses those sources and emits equivalent *public* data classes under
com.dooglemaps.data, so we mirror the data rather than reflecting into core.

Usage:
    python3 tools/generate_farming_data.py <path-to-extracted-client-sources>

The sources come from the client sources jar Gradle already downloads, e.g.
    ~/.gradle/caches/modules-2/files-2.1/net.runelite/client/<ver>/*/client-<ver>-sources.jar

Original data is Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause.
See ATTRIBUTION.md.
"""

import os
import re
import sys

OUT_PKG_DIR = os.path.join(
    os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "java", "com", "dooglemaps", "data"
)

HEADER = """// GENERATED FILE - DO NOT EDIT BY HAND.
// Regenerate with: python3 tools/generate_farming_data.py <runelite-client-sources>
//
// Mirrored from RuneLite core's net.runelite.client.plugins.timetracking.farming
// package (Copyright (c) 2018 Abex and the RuneLite contributors, BSD 2-clause).
// Those classes are package-private, so external plugins must carry their own copy.
// See ATTRIBUTION.md.
package com.dooglemaps.data;
"""


def read(src_root, name):
    path = os.path.join(src_root, "net", "runelite", "client", "plugins", "timetracking", name)
    with open(path, "r", encoding="utf-8") as f:
        return f.read()


# --------------------------------------------------------------------------------------
# Produce.java
# --------------------------------------------------------------------------------------

# Matches a Produce enum constant with any of its constructor overloads. Constants are
# one per line, terminated by "," or ";" and sometimes followed by a trailing comment.
PRODUCE_RE = re.compile(
    r'^\t([A-Z][A-Z0-9_]*)\(\s*(.+?)\s*\)\s*[,;]?\s*(?://.*)?$',
    re.MULTILINE,
)


def split_args(s):
    """Split a Java argument list on top-level commas."""
    out, depth, cur, in_str = [], 0, "", False
    i = 0
    while i < len(s):
        c = s[i]
        if in_str:
            cur += c
            if c == "\\":
                cur += s[i + 1]
                i += 2
                continue
            if c == '"':
                in_str = False
        elif c == '"':
            in_str = True
            cur += c
        elif c in "([":
            depth += 1
            cur += c
        elif c in ")]":
            depth -= 1
            cur += c
        elif c == "," and depth == 0:
            out.append(cur.strip())
            cur = ""
        else:
            cur += c
        i += 1
    if cur.strip():
        out.append(cur.strip())
    return out


def parse_produce(src):
    """
    -> list of dicts {const, name, contractName, impl, itemExpr, tickrate, stages,
                      regrowTickrate, harvestStages}

    Constructor overloads (see Produce.java):
      (name, itemID, tickrate, stages, regrowTickrate, harvestStages)
      (name, impl, itemID, tickrate, stages, regrowTickrate, harvestStages)
      (name, contractName, impl, itemID, tickrate, stages, regrowTickrate, harvestStages)
      (name, contractName, impl, itemID, tickrate, stages)      -> regrow 0, harvest 1
      (name, impl, itemID, tickrate, stages)                    -> regrow 0, harvest 1
      (name, itemID, tickrate, stages)                          -> regrow 0, harvest 1
    """
    body = src[src.index("public enum Produce") : src.index("\n\tProduce(")]
    out = []
    for m in PRODUCE_RE.finditer(body):
        const, arglist = m.group(1), m.group(2)
        args = split_args(arglist)

        name = args[0]
        rest = args[1:]

        # Optional contractName: a second string literal in position 1.
        if rest and rest[0].startswith('"'):
            contract = rest[0]
            rest = rest[1:]
        else:
            contract = name

        # Optional PatchImplementation reference.
        if rest and rest[0].startswith("PatchImplementation."):
            impl = rest[0].split(".", 1)[1]
            rest = rest[1:]
        else:
            impl = None

        item_expr = rest[0]
        nums = [int(x) for x in rest[1:]]
        if len(nums) == 2:
            tickrate, stages, regrow, harvest = nums[0], nums[1], 0, 1
        elif len(nums) == 4:
            tickrate, stages, regrow, harvest = nums
        else:
            raise SystemExit(f"unexpected Produce arity for {const}: {rest}")

        out.append(
            dict(
                const=const,
                name=name,
                contract=contract,
                impl=impl,
                item=item_expr,
                tickrate=tickrate,
                stages=stages,
                regrow=regrow,
                harvest=harvest,
            )
        )
    return out


def emit_produce(produce, produce_src):
    # Item constants are copied verbatim, so take their imports from the source too.
    item_classes = {p["item"].split(".")[0] for p in produce}
    lines = [
        HEADER,
        "",
        "import java.util.HashMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        *api_imports(produce_src, item_classes),
        "",
        "/**",
        " * Everything that can occupy a farming patch, with its growth timing.",
        " *",
        " * <p>{@code tickrate} is minutes per growth tick and {@code stages} the number of",
        " * growth states; {@code regrowTickrate}/{@code harvestStages} cover crops that",
        " * regrow after picking (\"lives\").",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum Produce",
        "{",
    ]
    for i, p in enumerate(produce):
        impl = f"PatchImplementation.{p['impl']}" if p["impl"] else "null"
        sep = "," if i < len(produce) - 1 else ";"
        lines.append(
            f"\t{p['const']}({p['name']}, {p['contract']}, {impl}, {p['item']}, "
            f"{p['tickrate']}, {p['stages']}, {p['regrow']}, {p['harvest']}){sep}"
        )
    lines += [
        "",
        "\tprivate final String name;",
        "\tprivate final String contractName;",
        "\t@Nullable",
        "\tprivate final PatchImplementation patchImplementation;",
        "\tprivate final int itemID;",
        "\t/** Minutes per growth tick. */",
        "\tprivate final int tickrate;",
        "\t/** Number of growth states, typically tick count + 1. */",
        "\tprivate final int stages;",
        "\t/** Minutes to regrow after harvesting, or 0 if it does not regrow. */",
        "\tprivate final int regrowTickrate;",
        "\t/** Number of harvest states, often called lives. */",
        "\tprivate final int harvestStages;",
        "",
        "\t/** True for the filler entries that are not a real crop. */",
        "\tpublic boolean isCrop()",
        "\t{",
        "\t\treturn this != WEEDS && this != SCARECROW;",
        "\t}",
        "",
        "\t/** Minimum minutes from planting to fully grown, ignoring disease setbacks. */",
        "\tpublic int getMinutesToGrow()",
        "\t{",
        "\t\treturn tickrate * (stages - 1);",
        "\t}",
        "",
        "\tprivate static final Map<Integer, Produce> BY_ITEM_ID = new HashMap<>();",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (Produce produce : values())",
        "\t\t{",
        "\t\t\t// First declaration wins, because item ids are not unique here — ANYHERB carries",
        "\t\t\t// a guam leaf and the big compost bin's tiers repeat the small one's buckets. Core",
        "\t\t\t// answers this question with a linear scan over values(), so first-wins is not a",
        "\t\t\t// preference but the behaviour that has to be matched: a contract stored as a guam",
        "\t\t\t// leaf must read back as GUAM, exactly as Time Tracking wrote it.",
        "\t\t\tBY_ITEM_ID.putIfAbsent(produce.itemID, produce);",
        "\t\t}",
        "\t}",
        "",
        "\t/**",
        "\t * What grows into this item, or null for an item nothing produces.",
        "\t *",
        "\t * <p>Exists for the farming contract, which Time Tracking stores as the harvested",
        "\t * item's id and nothing else. See {@code com.dooglemaps.state.ContractState}.",
        "\t */",
        "\t@Nullable",
        "\tpublic static Produce getByItemID(int itemID)",
        "\t{",
        "\t\treturn BY_ITEM_ID.get(itemID);",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# PatchImplementation.java
# --------------------------------------------------------------------------------------

# Tab labels. One tab per patch implementation, so the sidebar's grouping and the seed
# selector's grouping are the same thing and cannot drift apart. Anything not named here
# falls back to the enum name in sentence case, which is already right for most of them.
DISPLAY_NAMES = {
    "HARDWOOD_TREE": "Hardwood",
    "BIG_COMPOST": "Big compost bin",
    "COMPOST": "Compost bin",
    "GRAPES": "Grape",
    "ANIMA": "Anima",
    "SEAWEED": "Giant seaweed",
}


# Tab order: the patches a farm run actually visits first, then the one-offs, then the
# compost bins. Source order would put mushroom and hespori first, which is nobody's
# priority. Anything missing here keeps its source position at the end, so a patch added
# by a game update still shows up.
TAB_ORDER = [
    "HERB", "ALLOTMENT", "FLOWER", "HOPS", "BUSH",
    "TREE", "FRUIT_TREE", "HARDWOOD_TREE",
    "GRAPES", "CACTUS", "CALQUAT", "CELASTRUS", "REDWOOD", "SPIRIT_TREE",
    "CRYSTAL_TREE", "SEAWEED", "CORAL", "MUSHROOM", "BELLADONNA", "HESPORI", "ANIMA",
    "COMPOST", "BIG_COMPOST",
]


def display_name(const):
    if const in DISPLAY_NAMES:
        return DISPLAY_NAMES[const]
    return const.replace("_", " ").capitalize()

IMPL_DECL_RE = re.compile(r'^\t([A-Z][A-Z0-9_]*)\(Tab\.([A-Z_]+), "([^"]*)", (true|false)\)', re.MULTILINE)
RANGE_RE = re.compile(r"^\s*if \(value >= (\d+) && value <= (\d+)\)")
EQ_RE = re.compile(r"^\s*if \(value == (\d+)\)")
EQ2_RE = re.compile(r"^\s*if \(value == (\d+) \|\| value == (\d+)\)")
RET_RE = re.compile(r"^\s*return new PatchState\(Produce\.(\w+), CropState\.(\w+), (.+)\);")


def parse_stage_expr(expr, produce_stages):
    """Reduce a stage expression to (base, coefficient-on-value)."""
    expr = expr.strip()
    m = re.fullmatch(r"(\d+)", expr)
    if m:
        return int(m.group(1)), 0
    m = re.fullmatch(r"value - (\d+)", expr)
    if m:
        return -int(m.group(1)), 1
    m = re.fullmatch(r"(\d+) - value", expr)
    if m:
        return int(m.group(1)), -1
    m = re.fullmatch(r"(\d+) \+ value\s+- (\d+)", expr)
    if m:
        return int(m.group(1)) - int(m.group(2)), 1
    m = re.fullmatch(r"Produce\.(\w+)\.getStages\(\) - 1", expr)
    if m:
        return produce_stages[m.group(1)] - 1, 0
    raise SystemExit(f"unhandled stage expression: {expr!r}")


def parse_implementations(src, produce_stages):
    """-> list of dicts {const, tab, contract, healthCheck, rules:[(lo,hi,produce,state,base,coef)]}"""
    decls = list(IMPL_DECL_RE.finditer(src))
    out = []
    for i, m in enumerate(decls):
        end = decls[i + 1].start() if i + 1 < len(decls) else len(src)
        body = src[m.end() : end]

        rules = []
        pending = None  # list of (lo, hi) awaiting their return statement
        for line in body.split("\n"):
            r = RANGE_RE.match(line)
            if r:
                pending = [(int(r.group(1)), int(r.group(2)))]
                continue
            r = EQ2_RE.match(line)
            if r:
                pending = [(int(r.group(1)), int(r.group(1))), (int(r.group(2)), int(r.group(2)))]
                continue
            r = EQ_RE.match(line)
            if r:
                pending = [(int(r.group(1)), int(r.group(1)))]
                continue
            r = RET_RE.match(line)
            if r and pending is not None:
                base, coef = parse_stage_expr(r.group(3), produce_stages)
                for lo, hi in pending:
                    rules.append((lo, hi, r.group(1), r.group(2), base, coef))
                pending = None

        if not rules:
            raise SystemExit(f"no rules parsed for PatchImplementation.{m.group(1)}")

        out.append(
            dict(
                const=m.group(1),
                tab=m.group(2),
                displayName=display_name(m.group(1)),
                contract=m.group(3),
                health=m.group(4) == "true",
                rules=rules,
            )
        )
    return out


def order_implementations(impls):
    """Sorts into TAB_ORDER, leaving anything unlisted in source order at the end."""
    return sorted(impls, key=lambda im: (
        TAB_ORDER.index(im["const"]) if im["const"] in TAB_ORDER else len(TAB_ORDER),
    ))


def emit_implementations(impls):
    """The enum itself. The bulky varbit table lives in PatchRules, see emit_rules."""
    impls = order_implementations(impls)
    lines = [
        HEADER,
        "",
        "import java.util.ArrayList;",
        "import java.util.Collections;",
        "import java.util.HashMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        "",
        "/**",
        " * A kind of farming patch: the decoder from its state varbit to what is growing",
        " * there, and the unit everything else groups by.",
        " *",
        " * <p>Each patch in the world has one varbit whose value encodes crop, crop state and",
        " * growth stage all at once. {@link #forVarbitValue(int)} decodes it.",
        " *",
        " * <p>This is deliberately also the sidebar's tab and the seed selector's grouping.",
        " * A loadout is chosen per patch type (\"all herb patches get ranarr\"), so the tab you",
        " * are looking at and the set of seeds you can pick from have to be the same concept —",
        " * keeping them as two enums would let them drift.",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum PatchImplementation",
        "{",
    ]

    for i, im in enumerate(impls):
        sep = "," if i < len(impls) - 1 else ";"
        lines.append(
            f'\t{im["const"]}("{im["displayName"]}", "{im["contract"]}", '
            f'{"true" if im["health"] else "false"}){sep}'
        )

    lines += [
        "",
        "\t/** Tab label. */",
        "\tprivate final String displayName;",
        "\t/** Farming contract name, empty when contracts never target this patch. */",
        "\tprivate final String contractName;",
        "\t/**",
        "\t * True when the crop must be checked for health before it can be harvested, so a",
        "\t * GROWING -> HARVESTABLE transition is a player action rather than a growth tick.",
        "\t */",
        "\tprivate final boolean healthCheckRequired;",
        "",
        "\t/**",
        "\t * Decodes a raw patch varbit value.",
        "\t *",
        "\t * @return the patch contents, or null if the value is not one this patch uses",
        "\t */",
        "\t@Nullable",
        "\tpublic ProduceState forVarbitValue(int value)",
        "\t{",
        "\t\treturn PatchRules.decode(this, value);",
        "\t}",
        "",
        "\t/**",
        "\t * Everything plantable in this kind of patch.",
        "\t *",
        "\t * <p>Derived from {@link Produce} rather than listed separately, so a crop added by",
        "\t * a game update turns up here as soon as the data is regenerated.",
        "\t */",
        "\tpublic List<Produce> getCrops()",
        "\t{",
        "\t\tList<Produce> crops = new ArrayList<>();",
        "\t\tfor (Produce produce : Produce.values())",
        "\t\t{",
        "\t\t\tif (produce.getPatchImplementation() == this && produce.isCrop())",
        "\t\t\t{",
        "\t\t\t\tcrops.add(produce);",
        "\t\t\t}",
        "\t\t}",
        "\t\treturn Collections.unmodifiableList(crops);",
        "\t}",
        "",
        "\t/**",
        "\t * Item icon for the tab, taken from the first crop this patch grows.",
        "\t *",
        "\t * <p>Derived rather than hand-picked so there is no per-patch icon list to keep in",
        "\t * step with the game.",
        "\t */",
        "\tpublic int getItemID()",
        "\t{",
        "\t\tList<Produce> crops = getCrops();",
        "\t\tfor (Produce crop : crops)",
        "\t\t{",
        "\t\t\tif (crop.getItemID() > 0)",
        "\t\t\t{",
        "\t\t\t\treturn crop.getItemID();",
        "\t\t\t}",
        "\t\t}",
        "\t\treturn -1;",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


def emit_rules(impls):
    """
    The varbit decode table, deliberately in its own class.

    Produce's constants name their PatchImplementation, so Produce depends on
    PatchImplementation at class-initialisation time. If PatchImplementation's constants
    named Produce back, the two enums would initialise circularly and whichever went
    second would see nulls. Holding the table here breaks that: nothing touches this class
    until the first decode call, by which point both enums are fully built.
    """
    lines = [
        HEADER,
        "",
        "import java.util.EnumMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "",
        "/**",
        " * The varbit value ranges each kind of patch uses.",
        " *",
        " * <p>Within a range the growth stage is {@code base + coefficient * value}, which is",
        " * enough to express every pattern the game uses: a fixed stage, one counting up with",
        " * the varbit, or one counting down.",
        " *",
        " * <p>Kept apart from {@link PatchImplementation} on purpose. {@link Produce} names its",
        " * patch implementation, so Produce initialises after PatchImplementation; if",
        " * PatchImplementation's constants named Produce back, the two would initialise in a",
        " * cycle and one of them would see half-built constants. Nothing loads this class until",
        " * the first {@link #decode} call, long after both enums exist.",
        " */",
        "final class PatchRules",
        "{",
        "\tprivate static final Map<PatchImplementation, Rule[]> RULES =",
        "\t\tnew EnumMap<>(PatchImplementation.class);",
        "",
        "\tstatic",
        "\t{",
    ]

    for im in impls:
        lines.append(f'\t\tRULES.put(PatchImplementation.{im["const"]}, new Rule[]{{')
        for lo, hi, prod, state, base, coef in im["rules"]:
            lines.append(
                f"\t\t\tnew Rule({lo}, {hi}, Produce.{prod}, CropState.{state}, {base}, {coef}),"
            )
        lines.append("\t\t});")
        lines.append("")

    lines += [
        "\t}",
        "",
        "\tprivate PatchRules()",
        "\t{",
        "\t}",
        "",
        "\t@Nullable",
        "\tstatic ProduceState decode(PatchImplementation implementation, int value)",
        "\t{",
        "\t\tRule[] rules = RULES.get(implementation);",
        "\t\tif (rules == null)",
        "\t\t{",
        "\t\t\treturn null;",
        "\t\t}",
        "",
        "\t\tfor (Rule rule : rules)",
        "\t\t{",
        "\t\t\tif (value >= rule.low && value <= rule.high)",
        "\t\t\t{",
        "\t\t\t\treturn new ProduceState(rule.produce, rule.cropState,",
        "\t\t\t\t\trule.base + (rule.coefficient * value));",
        "\t\t\t}",
        "\t\t}",
        "\t\treturn null;",
        "\t}",
        "",
        "\t/** One varbit value range and the patch contents it decodes to. */",
        "\tprivate static final class Rule",
        "\t{",
        "\t\tfinal int low;",
        "\t\tfinal int high;",
        "\t\tfinal Produce produce;",
        "\t\tfinal CropState cropState;",
        "\t\tfinal int base;",
        "\t\tfinal int coefficient;",
        "",
        "\t\tRule(int low, int high, Produce produce, CropState cropState, int base, int coefficient)",
        "\t\t{",
        "\t\t\tthis.low = low;",
        "\t\t\tthis.high = high;",
        "\t\t\tthis.produce = produce;",
        "\t\t\tthis.cropState = cropState;",
        "\t\t\tthis.base = base;",
        "\t\t\tthis.coefficient = coefficient;",
        "\t\t}",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# FarmingWorld.java
# --------------------------------------------------------------------------------------

REGION_RE = re.compile(
    r'add\((?:\w+ = )?new FarmingRegion\("([^"]*)", (\d+), (true|false),\s*(.*?)\n\t{2,3}\)',
    re.DOTALL,
)
PATCH_RE = re.compile(
    r'new FarmingPatch\("([^"]*)", ((?:Varbits|VarbitID)\.\w+), PatchImplementation\.(\w+)'
    r'(?:, (NpcID\.\w+))?(?:, (\d+))?\)'
)


def api_imports(src, class_names):
    """
    The source file's own import lines for the given API classes.

    RuneLite moves these around between versions — Varbits became VarbitID, and ItemID and
    NpcID moved into net.runelite.api.gameval — so copy the imports rather than assuming a
    package.
    """
    imports = []
    for name in sorted(class_names):
        match = re.search(r"^import (net\.runelite\.api\.[\w.]*" + name + ");$", src, re.MULTILINE)
        if not match:
            raise SystemExit(f"could not find the import for {name}")
        imports.append("import " + match.group(1) + ";")
    return imports


def parse_world(src):
    """-> list of dicts {name, regionId, definite, patches, extraRegions}"""
    body = src[src.index("FarmingWorld()") :]
    out = []
    for m in REGION_RE.finditer(body):
        name, region_id, definite, patch_block = m.groups()

        patches = []
        for p in PATCH_RE.finditer(patch_block):
            patches.append(
                dict(
                    name=p.group(1),
                    varbit=p.group(2),
                    impl=p.group(3),
                    farmer=p.group(4),
                    number=p.group(5),
                )
            )
        if not patches:
            raise SystemExit(f"no patches parsed for region {name} ({region_id})")

        # After the constructor's closing paren comes either an anonymous-subclass body
        # (overriding isInBounds) or straight to the trailing extra region ids. Either
        # way the add(...) call ends at the first ");" that is not inside that body.
        tail = body[m.end() :]
        depth, end = 0, None
        for i, c in enumerate(tail):
            if c == "{":
                depth += 1
            elif c == "}":
                depth -= 1
            elif c == ")" and depth == 0 and tail[i : i + 2] == ");":
                end = i
                break
        if end is None:
            raise SystemExit(f"could not find end of add() for region {name} ({region_id})")

        tail = tail[:end]
        bounded = "isInBounds" in tail
        # Region ids are the only bare 4-5 digit numbers outside the isInBounds body.
        after_bounds = tail[tail.rindex("}") + 1 :] if bounded else tail
        extra = [int(x) for x in re.findall(r"\b(\d{4,5})\b", after_bounds)]

        out.append(
            dict(
                name=name,
                region_id=int(region_id),
                definite=definite == "true",
                patches=patches,
                extra=extra,
                bounded=bounded,
            )
        )
    return out


def emit_world(regions, world_src):
    constant_classes = set()
    for region in regions:
        for patch in region["patches"]:
            constant_classes.add(patch["varbit"].split(".")[0])
            if patch["farmer"]:
                constant_classes.add(patch["farmer"].split(".")[0])

    lines = [
        HEADER,
        "",
        "import java.util.ArrayList;",
        "import java.util.Arrays;",
        "import java.util.Collection;",
        "import java.util.Collections;",
        "import java.util.EnumMap;",
        "import java.util.LinkedHashMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "import net.runelite.api.coords.WorldPoint;",
        "",
        "/**",
        " * Every farming patch in the game, grouped by the map region whose varbits carry it.",
        " *",
        " * <p>A patch is identified by (region id, varbit): the same varbit number is reused",
        " * across regions, so neither half is unique on its own.",
        " */",
        "public final class FarmingWorldData",
        "{",
        "\tprivate static final List<FarmRegion> REGIONS = new ArrayList<>();",
        "\tprivate static final Map<Integer, List<FarmRegion>> BY_REGION_ID = new LinkedHashMap<>();",
        "\tprivate static final Map<PatchImplementation, List<FarmPatch>> BY_TYPE = new EnumMap<>(PatchImplementation.class);",
        "\tprivate static final Map<String, FarmPatch> BY_KEY = new LinkedHashMap<>();",
        "",
        "\tstatic",
        "\t{",
    ]

    for r in regions:
        patch_args = []
        for p in r["patches"]:
            farmer = p["farmer"] if p["farmer"] else "-1"
            number = p["number"] if p["number"] is not None else "-1"
            patch_args.append(
                f'\t\t\tnew FarmPatch("{p["name"]}", {p["varbit"]}, '
                f'PatchImplementation.{p["impl"]}, {farmer}, {number})'
            )
        bounds = f"RegionBounds.forRegion({r['region_id']})" if r["bounded"] else "RegionBounds.ALWAYS"
        extra = ", ".join(str(x) for x in r["extra"])
        lines.append(
            f'\t\tadd(new FarmRegion("{r["name"]}", {r["region_id"]}, '
            f'{"true" if r["definite"] else "false"}, {bounds},'
        )
        lines.append(",\n".join(patch_args))
        lines.append(f"\t\t){', ' + extra if extra else ''});")
        lines.append("")

    lines += [
        "\t\tfor (Map.Entry<PatchImplementation, List<FarmPatch>> e : BY_TYPE.entrySet())",
        "\t\t{",
        "\t\t\te.setValue(Collections.unmodifiableList(e.getValue()));",
        "\t\t}",
        "\t}",
        "",
        "\tprivate FarmingWorldData()",
        "\t{",
        "\t}",
        "",
        "\tprivate static void add(FarmRegion region, int... extraRegionIds)",
        "\t{",
        "\t\tREGIONS.add(region);",
        "\t\tBY_REGION_ID.computeIfAbsent(region.getRegionId(), k -> new ArrayList<>()).add(region);",
        "\t\tfor (int extra : extraRegionIds)",
        "\t\t{",
        "\t\t\tBY_REGION_ID.computeIfAbsent(extra, k -> new ArrayList<>()).add(region);",
        "\t\t}",
        "\t\tfor (FarmPatch patch : region.getPatches())",
        "\t\t{",
        "\t\t\tBY_TYPE.computeIfAbsent(patch.getImplementation(), k -> new ArrayList<>()).add(patch);",
        "\t\t\tBY_KEY.put(patch.getKey(), patch);",
        "\t\t}",
        "\t}",
        "",
        "\tpublic static List<FarmRegion> getRegions()",
        "\t{",
        "\t\treturn Collections.unmodifiableList(REGIONS);",
        "\t}",
        "",
        "\t/** Regions whose varbits are live at the given location. */",
        "\tpublic static Collection<FarmRegion> getRegionsForLocation(WorldPoint location)",
        "\t{",
        "\t\tList<FarmRegion> candidates = BY_REGION_ID.get(location.getRegionID());",
        "\t\tif (candidates == null)",
        "\t\t{",
        "\t\t\treturn Collections.emptyList();",
        "\t\t}",
        "",
        "\t\tList<FarmRegion> result = new ArrayList<>(candidates.size());",
        "\t\tfor (FarmRegion region : candidates)",
        "\t\t{",
        "\t\t\tif (region.isInBounds(location))",
        "\t\t\t{",
        "\t\t\t\tresult.add(region);",
        "\t\t\t}",
        "\t\t}",
        "\t\treturn result;",
        "\t}",
        "",
        "\tpublic static List<FarmPatch> getPatches(PatchImplementation type)",
        "\t{",
        "\t\treturn BY_TYPE.getOrDefault(type, Collections.emptyList());",
        "\t}",
        "",
        "\tpublic static Collection<FarmPatch> getAllPatches()",
        "\t{",
        "\t\treturn Collections.unmodifiableCollection(BY_KEY.values());",
        "\t}",
        "",
        "\t/** Looks a patch up by its {@code regionId.varbit} key. */",
        "\tpublic static FarmPatch getPatch(String key)",
        "\t{",
        "\t\treturn BY_KEY.get(key);",
        "\t}",
        "}",
        "",
    ]
    text = "\n".join(lines)
    # The region table names varbit and NPC constants; import them as the source does.
    return text.replace(
        "import net.runelite.api.coords.WorldPoint;",
        "\n".join([*api_imports(world_src, constant_classes), "import net.runelite.api.coords.WorldPoint;"]),
    )




# --------------------------------------------------------------------------------------
# Seed.java
# --------------------------------------------------------------------------------------

# Farming level needed to plant each crop, from the OSRS Wiki's seed tables. Not in
# RuneLite's data, which only cares about what a patch is doing, not what may go in it.
SEED_LEVELS = {
    # Allotment
    "POTATO": 1, "ONION": 5, "CABBAGE": 7, "TOMATO": 12, "SWEETCORN": 20,
    "STRAWBERRY": 31, "WATERMELON": 47, "SNAPE_GRASS": 61,
    # Flower
    "MARIGOLD": 2, "ROSEMARY": 11, "NASTURTIUM": 24, "WOAD": 25, "LIMPWURT": 26,
    "WHITE_LILY": 58,
    # Herb
    "GUAM": 9, "MARRENTILL": 14, "TARROMIN": 19, "HARRALANDER": 26, "GOUTWEED": 29,
    "RANARR": 32, "TOADFLAX": 38, "IRIT": 44, "AVANTOE": 50, "KWUARM": 56,
    "SNAPDRAGON": 62, "HUASCA": 65, "CADANTINE": 67, "LANTADYME": 73, "DWARF_WEED": 79,
    "TORSTOL": 85,
    # Hops
    "BARLEY": 3, "HAMMERSTONE": 4, "ASGARNIAN": 8, "JUTE": 13, "YANILLIAN": 16,
    "FLAX": 18, "KRANDORIAN": 21, "WILDBLOOD": 28, "HEMP": 37, "COTTON": 71,
    # Bush
    "REDBERRIES": 10, "CADAVABERRIES": 22, "DWELLBERRIES": 36, "JANGERBERRIES": 48,
    "WHITEBERRIES": 59, "POISON_IVY": 70,
    # Tree
    "OAK": 15, "WILLOW": 30, "MAPLE": 45, "YEW": 60, "MAGIC": 75,
    # Fruit tree
    "APPLE": 27, "BANANA": 33, "ORANGE": 39, "CURRY": 42, "PINEAPPLE": 51,
    "PAPAYA": 57, "PALM": 68, "DRAGONFRUIT": 81,
    # Hardwood
    "TEAK": 35, "MAHOGANY": 55, "CAMPHOR": 66, "IRONWOOD": 80, "ROSEWOOD": 92,
    # Coral
    "ELKHORN_CORAL": 28, "PILLAR_CORAL": 52, "UMBRAL_CORAL": 77,
    # Everything else
    "SEAWEED": 23, "GRAPE": 36, "MUSHROOM": 53, "BELLADONNA": 63, "HESPORI": 65,
    "CALQUAT": 72, "CRYSTAL_TREE": 74, "SPIRIT_TREE": 83, "CELASTRUS": 85,
    "REDWOOD": 90, "CACTUS": 55, "POTATO_CACTUS": 64,
    "KRONOS": 76, "IASOR": 76, "ATTAS": 76,
}

# Most seeds are ItemID.<PRODUCE>_SEED. These are not.
SEED_ITEM_OVERRIDES = {
    "REDBERRIES": "REDBERRY_BUSH_SEED",
    "CADAVABERRIES": "CADAVABERRY_BUSH_SEED",
    "DWELLBERRIES": "DWELLBERRY_BUSH_SEED",
    "JANGERBERRIES": "JANGERBERRY_BUSH_SEED",
    "WHITEBERRIES": "WHITEBERRY_BUSH_SEED",
    "POISON_IVY": "POISONIVY_BUSH_SEED",
    "HAMMERSTONE": "HAMMERSTONE_HOP_SEED",
    "ASGARNIAN": "ASGARNIAN_HOP_SEED",
    "YANILLIAN": "YANILLIAN_HOP_SEED",
    "KRANDORIAN": "KRANDORIAN_HOP_SEED",
    "WILDBLOOD": "WILDBLOOD_HOP_SEED",
    "OAK": "ACORN",
    "MAGIC": "MAGIC_TREE_SEED",
    "APPLE": "APPLE_TREE_SEED",
    "BANANA": "BANANA_TREE_SEED",
    "ORANGE": "ORANGE_TREE_SEED",
    "CURRY": "CURRY_TREE_SEED",
    "PINEAPPLE": "PINEAPPLE_TREE_SEED",
    "PAPAYA": "PAPAYA_TREE_SEED",
    "PALM": "PALM_TREE_SEED",
    "DRAGONFRUIT": "DRAGONFRUIT_TREE_SEED",
    "CALQUAT": "CALQUAT_TREE_SEED",
    "CELASTRUS": "CELASTRUS_TREE_SEED",
    "REDWOOD": "REDWOOD_TREE_SEED",
    "ELKHORN_CORAL": "CORAL_ELKHORN_FRAG",
    "PILLAR_CORAL": "CORAL_PILLAR_FRAG",
    "UMBRAL_CORAL": "CORAL_UMBRAL_FRAG",
}

# Crops with no plantable seed: filler entries and the compost bins' contents.
NOT_PLANTABLE = {"WEEDS", "SCARECROW", "ANYHERB", "GOUTWEED"}

# Patch types you plant a *sapling* in rather than a seed.
#
# The seed alone will not go in the ground: it has to be put in a filled plant pot, watered,
# and left to become a sapling first. So a player stocking up for a tree run owns saplings and
# often no seeds at all, and a seed list that only knew about acorns showed them nothing.
SAPLING_PATCH_TYPES = {
    "TREE", "FRUIT_TREE", "HARDWOOD_TREE", "CALQUAT",
    "SPIRIT_TREE", "CELASTRUS", "REDWOOD", "CRYSTAL_TREE",
}

# Saplings are ItemID.PLANTPOT_<PRODUCE>_SAPLING. These few carry "TREE" in the item name
# where the produce constant does not.
SAPLING_ITEM_OVERRIDES = {
    "MAGIC": "PLANTPOT_MAGIC_TREE_SAPLING",
    "CELASTRUS": "PLANTPOT_CELASTRUS_TREE_SAPLING",
    "REDWOOD": "PLANTPOT_REDWOOD_TREE_SAPLING",
}


def sapling_item(produce_const, impl):
    """The sapling item constant for a crop, or None where you plant the seed directly."""
    if impl not in SAPLING_PATCH_TYPES:
        return None
    return SAPLING_ITEM_OVERRIDES.get(produce_const, f"PLANTPOT_{produce_const}_SAPLING")


def emit_seeds(produce, produce_src):
    plantable = [
        p for p in produce
        if p["impl"] and p["const"] not in NOT_PLANTABLE and p["const"] in SEED_LEVELS
    ]
    missing = [
        p["const"] for p in produce
        if p["impl"] and p["const"] not in NOT_PLANTABLE and p["const"] not in SEED_LEVELS
        and "COMPOST" not in p["impl"]
    ]
    if missing:
        print(f"  note: no level requirement recorded for {', '.join(missing)}")

    lines = [
        HEADER,
        "",
        "import java.util.ArrayList;",
        "import java.util.Collections;",
        "import java.util.HashMap;",
        "import java.util.List;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        *api_imports(produce_src, {"ItemID"}),
        "",
        "/**",
        " * Everything that can be planted, and what it takes to plant it.",
        " *",
        " * <p>Not derived from RuneLite: its data describes what a patch is <i>doing</i>, keyed",
        " * on the harvested item, and says nothing about the seed that started it or the level",
        " * needed to sow it. Level requirements come from the OSRS Wiki's seed tables.",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum Seed",
        "{",
    ]

    for i, p in enumerate(plantable):
        item = SEED_ITEM_OVERRIDES.get(p["const"], p["const"] + "_SEED")
        sapling = sapling_item(p["const"], p["impl"])
        sapling_ref = f"ItemID.{sapling}" if sapling else "-1"
        sep = "," if i < len(plantable) - 1 else ";"
        lines.append(
            f'\t{p["const"]}(ItemID.{item}, {sapling_ref}, Produce.{p["const"]}, '
            f'{SEED_LEVELS[p["const"]]}){sep}'
        )

    lines += [
        "",
        "\tprivate final int itemID;",
        "\t/**",
        "\t * The sapling this seed becomes, or -1 where the seed goes straight in the ground.",
        "\t *",
        "\t * <p>Trees, fruit trees, hardwoods, calquats, celastrus, redwoods, spirit trees and",
        "\t * the crystal tree are all planted as saplings: the seed has to spend time in a",
        "\t * filled plant pot first. Anyone stocked up for a tree run therefore owns saplings",
        "\t * and quite possibly no seeds at all, so both count as having the crop.",
        "\t */",
        "\tprivate final int saplingItemID;",
        "\tprivate final Produce produce;",
        "\t/** Farming level needed to plant it. */",
        "\tprivate final int levelRequirement;",
        "",
        "\tpublic PatchImplementation getPatchType()",
        "\t{",
        "\t\treturn produce.getPatchImplementation();",
        "\t}",
        "",
        "\t/**",
        "\t * How many seeds one patch takes.",
        "\t *",
        "\t * <p>Allotments take three and hops four, except jute at three; everything else is",
        "\t * a single seed or sapling.",
        "\t */",
        "\tpublic int getSeedsPerPatch()",
        "\t{",
        "\t\tif (this == JUTE)",
        "\t\t{",
        "\t\t\treturn 3;",
        "\t\t}",
        "\t\tswitch (getPatchType())",
        "\t\t{",
        "\t\t\tcase ALLOTMENT:",
        "\t\t\t\treturn 3;",
        "\t\t\tcase HOPS:",
        "\t\t\t\treturn 4;",
        "\t\t\tdefault:",
        "\t\t\t\treturn 1;",
        "\t\t}",
        "\t}",
        "",
        "\tpublic String getName()",
        "\t{",
        "\t\treturn produce.getName();",
        "\t}",
        "",
        "\t/** Every seed that goes in a given kind of patch, easiest first. */",
        "\tpublic static List<Seed> forPatchType(PatchImplementation type)",
        "\t{",
        "\t\tList<Seed> seeds = new ArrayList<>();",
        "\t\tfor (Seed seed : values())",
        "\t\t{",
        "\t\t\tif (seed.getPatchType() == type)",
        "\t\t\t{",
        "\t\t\t\tseeds.add(seed);",
        "\t\t\t}",
        "\t\t}",
        "\t\tseeds.sort((a, b) -> Integer.compare(a.levelRequirement, b.levelRequirement));",
        "\t\treturn Collections.unmodifiableList(seeds);",
        "\t}",
        "",
        "\t/**",
        "\t * Whether this crop is planted as a sapling rather than as a seed.",
        "\t */",
        "\tpublic boolean isSapling()",
        "\t{",
        "\t\treturn saplingItemID != -1;",
        "\t}",
        "",
        "\t/**",
        "\t * The item that actually goes in the ground.",
        "\t *",
        "\t * <p>The sapling for a tree, the seed for everything else. This is what a seed list",
        "\t * should draw and count, because it is what the player carries to the patch.",
        "\t */",
        "\tpublic int getPlantedItemID()",
        "\t{",
        "\t\treturn isSapling() ? saplingItemID : itemID;",
        "\t}",
        "",
        "\tprivate static final Map<Integer, Seed> BY_ITEM_ID = new HashMap<>();",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (Seed seed : values())",
        "\t\t{",
        "\t\t\tBY_ITEM_ID.put(seed.itemID, seed);",
        "\t\t\t// Both forms count as owning the crop: the seed you have not potted yet and the",
        "\t\t\t// sapling it becomes. Only the sapling can be planted, but someone holding the",
        "\t\t\t// seed still has the tree, and a list that ignored either would be wrong.",
        "\t\t\tif (seed.isSapling())",
        "\t\t\t{",
        "\t\t\t\tBY_ITEM_ID.put(seed.saplingItemID, seed);",
        "\t\t\t}",
        "\t\t}",
        "\t}",
        "",
        "\tprivate static final Map<Produce, Seed> BY_PRODUCE = new HashMap<>();",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (Seed seed : values())",
        "\t\t{",
        "\t\t\tBY_PRODUCE.put(seed.produce, seed);",
        "\t\t}",
        "\t}",
        "",
        "\t/**",
        "\t * The seed that grows a given crop, or null if nothing plants it.",
        "\t *",
        "\t * <p>A patch varbit says what is <i>growing</i>, never which seed went in, so this is",
        "\t * how anything reading a patch gets back to the seed's level and yield data.",
        "\t */",
        "\t@Nullable",
        "\tpublic static Seed forProduce(@Nullable Produce produce)",
        "\t{",
        "\t\treturn produce == null ? null : BY_PRODUCE.get(produce);",
        "\t}",
        "",
        "\t/**",
        "\t * The seed an item id refers to, or null if it is not a seed.",
        "\t *",
        "\t * <p>A map rather than a scan because this is called once per item in whatever",
        "\t * container just changed. Opening a bank means a thousand-odd lookups at once, and a",
        "\t * linear walk of every seed for each of them was measurable when the bank opened.",
        "\t */",
        "\t@Nullable",
        "\tpublic static Seed forItemId(int itemId)",
        "\t{",
        "\t\treturn BY_ITEM_ID.get(itemId);",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# ProtectionPayment.java
# --------------------------------------------------------------------------------------

# What a farmer wants to protect each crop, from the OSRS Wiki's payment tables.
#
# Payment may be noted, but must be in the exact form asked for - a farmer will not take
# five apples instead of a basket of apples - so the item here is the exact one handed
# over. A "basket" is the five-fruit item and a "sack" the ten-vegetable one.
#
# Crops absent from this table cannot be farmer-protected at all: herbs, flowers,
# mushrooms, belladonna, and the inherently immune crops.
PROTECTION_PAYMENTS = {
    # Allotment
    "POTATO": ("BUCKET_COMPOST", 2),
    "ONION": ("SACK_POTATO_10", 1),
    "CABBAGE": ("SACK_ONION_10", 1),
    "TOMATO": ("SACK_CABBAGE_10", 2),
    "SWEETCORN": ("JUTE_FIBRE", 10),
    "STRAWBERRY": ("BASKET_APPLE_5", 1),
    "WATERMELON": ("CURRY_LEAF", 10),
    "SNAPE_GRASS": ("JANGERBERRIES", 5),
    # Hops
    "BARLEY": ("BUCKET_COMPOST", 3),
    "HAMMERSTONE": ("MARIGOLD", 1),
    "ASGARNIAN": ("SACK_ONION_10", 1),
    "JUTE": ("BARLEY_MALT", 6),
    "YANILLIAN": ("BASKET_TOMATO_5", 1),
    "FLAX": ("GRAIN", 6),
    "KRANDORIAN": ("SACK_CABBAGE_10", 3),
    "WILDBLOOD": ("NASTURTIUM", 1),
    "HEMP": ("FLAX", 6),
    "COTTON": ("HEMP", 6),
    # Bush
    "REDBERRIES": ("SACK_CABBAGE_10", 4),
    "CADAVABERRIES": ("BASKET_TOMATO_5", 3),
    "DWELLBERRIES": ("BASKET_STRAWBERRY_5", 3),
    "JANGERBERRIES": ("WATERMELON", 6),
    "WHITEBERRIES": ("BITTERCAP_MUSHROOM", 8),
    # Tree
    "OAK": ("BASKET_TOMATO_5", 1),
    "WILLOW": ("BASKET_APPLE_5", 1),
    "MAPLE": ("BASKET_ORANGE_5", 1),
    "YEW": ("CACTUS_SPINE", 10),
    "MAGIC": ("COCONUT", 25),
    # Fruit tree
    "APPLE": ("SWEETCORN", 9),
    "BANANA": ("BASKET_APPLE_5", 4),
    "ORANGE": ("BASKET_STRAWBERRY_5", 3),
    "CURRY": ("BASKET_BANANA_5", 5),
    "PINEAPPLE": ("WATERMELON", 10),
    "PAPAYA": ("PINEAPPLE", 10),
    "PALM": ("PAPAYA", 15),
    "DRAGONFRUIT": ("COCONUT", 15),
    # Hardwood
    "TEAK": ("LIMPWURT_ROOT", 15),
    "MAHOGANY": ("YANILLIAN_HOPS", 25),
    "CAMPHOR": ("WHITE_BERRIES", 10),
    "IRONWOOD": ("CURRY_LEAF", 10),
    "ROSEWOOD": ("DRAGONFRUIT", 8),
    # Everything else that takes a payment
    "SEAWEED": ("FOSSIL_NUMULITE", 200),
    "CALQUAT": ("POISONIVY_BERRIES", 8),
    "CELASTRUS": ("CACTUS_POTATO", 8),
    "REDWOOD": ("DRAGONFRUIT", 6),
    "CACTUS": ("CADAVABERRIES", 6),
    "POTATO_CACTUS": ("SNAPE_GRASS", 8),
    "ELKHORN_CORAL": ("GIANT_SEAWEED", 5),
    "PILLAR_CORAL": ("CORAL_ELKHORN", 5),
    "UMBRAL_CORAL": ("CORAL_PILLAR", 5),
    # Spirit tree is the one payment that is not a single item - five monkey nuts, a monkey
    # bar and a ground tooth - so it is left out rather than misrepresented as one.
}


def emit_payments(produce, produce_src):
    known = {p["const"] for p in produce}
    missing = [c for c in PROTECTION_PAYMENTS if c not in known]
    if missing:
        raise SystemExit(f"payment listed for unknown produce: {', '.join(missing)}")

    entries = [p for p in produce if p["const"] in PROTECTION_PAYMENTS]

    lines = [
        HEADER,
        "",
        "import java.util.EnumMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        *api_imports(produce_src, {"ItemID"}),
        "",
        "/**",
        " * What a farmer charges to protect a crop.",
        " *",
        " * <p>Payment may be noted, but has to be the exact item asked for — a farmer will not",
        " * take five apples in place of a basket of apples — so these are the precise items,",
        " * with a basket meaning the five-fruit one and a sack the ten-vegetable one.",
        " *",
        " * <p>Because it can be noted, a payment costs one inventory slot however large the",
        " * quantity: twenty-five coconuts for a magic tree travel as a single noted stack.",
        " *",
        " * <p>Crops missing from here cannot be farmer-protected at all — herbs, flowers,",
        " * mushrooms and belladonna have no protection option, and the immune crops need none.",
        " * The spirit tree is absent for a different reason: it is the one crop whose payment",
        " * is several different items at once, so it is left out rather than misrepresented.",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum ProtectionPayment",
        "{",
    ]

    for i, p in enumerate(entries):
        item, qty = PROTECTION_PAYMENTS[p["const"]]
        sep = "," if i < len(entries) - 1 else ";"
        lines.append(f"\t{p['const']}(Produce.{p['const']}, ItemID.{item}, {qty}){sep}")

    lines += [
        "",
        "\tprivate static final Map<Produce, ProtectionPayment> BY_PRODUCE =",
        "\t\tnew EnumMap<>(Produce.class);",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (ProtectionPayment payment : values())",
        "\t\t{",
        "\t\t\tBY_PRODUCE.put(payment.produce, payment);",
        "\t\t}",
        "\t}",
        "",
        "\tprivate final Produce produce;",
        "\tprivate final int itemID;",
        "\t/** How many of the item the farmer wants. */",
        "\tprivate final int quantity;",
        "",
        "\t/** The payment for a crop, or null if it cannot be protected. */",
        "\t@Nullable",
        "\tpublic static ProtectionPayment forProduce(@Nullable Produce produce)",
        "\t{",
        "\t\treturn produce == null ? null : BY_PRODUCE.get(produce);",
        "\t}",
        "",
        "\t/** The payment for a seed's crop, or null if it cannot be protected. */",
        "\t@Nullable",
        "\tpublic static ProtectionPayment forSeed(@Nullable Seed seed)",
        "\t{",
        "\t\treturn seed == null ? null : forProduce(seed.getProduce());",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# CropYield.java
# --------------------------------------------------------------------------------------


def parse_crop_yield(path, known_seeds):
    """
    Reads the scraped chance-to-save constants.

    The file's ``family`` column is documentation only. Which boosts apply is a rule, not
    data - the Farming cape counts on herbs and nowhere else, and magic secateurs do not
    work underwater - and that rule already falls out of the seed's patch type. Emitting it
    a second time would just be a copy that can drift.
    """
    rows = []
    skipped = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("seed\t"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            const, _family, low, high = parts[:4]
            const = const.strip()
            if const not in known_seeds:
                skipped.append(const)
                continue
            rows.append((const, int(low), int(high)))
    return rows, skipped


def emit_crop_yield(rows):
    lines = [
        HEADER,
        "",
        "import java.util.EnumMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        "",
        "/**",
        " * The two chance-to-save constants that drive a crop\'s yield.",
        " *",
        " * <p>Herbs, allotments, hops, celastrus and giant seaweed are harvested until their",
        " * \"lives\" run out, and every pick has a chance to cost no life at all. That chance is",
        " * interpolated between a level-1 value and a level-99 value, both fixed per crop and",
        " * both measured in 256ths. They are what makes a ranarr patch give nine herbs rather",
        " * than the three it starts with.",
        " *",
        " * <p>Scraped from each seed\'s OSRS Wiki page; see {@code tools/crop-yield.tsv}. Crops",
        " * that do not use the lives mechanic are absent, as are celastrus and the flowers,",
        " * whose constants Jagex has never published. {@link com.dooglemaps.timer.YieldEstimate}",
        " * turns these into an expected harvest.",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum CropYield",
        "{",
    ]
    for i, (const, low, high) in enumerate(rows):
        sep = "," if i < len(rows) - 1 else ";"
        lines.append(f"\t{const}(Seed.{const}, {low}, {high}){sep}")

    lines += [
        "",
        "\tprivate static final Map<Seed, CropYield> BY_SEED = new EnumMap<>(Seed.class);",
        "\tprivate static final Map<Produce, CropYield> BY_PRODUCE = new EnumMap<>(Produce.class);",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (CropYield yield : values())",
        "\t\t{",
        "\t\t\tBY_SEED.put(yield.seed, yield);",
        "\t\t\tBY_PRODUCE.put(yield.seed.getProduce(), yield);",
        "\t\t}",
        "\t}",
        "",
        "\tprivate final Seed seed;",
        "\t/** Chance to save a life at Farming level 1, in 256ths. */",
        "\tprivate final int ctsLow;",
        "\t/** Chance to save a life at Farming level 99, in 256ths. */",
        "\tprivate final int ctsHigh;",
        "",
        "\t@Nullable",
        "\tpublic static CropYield forSeed(@Nullable Seed seed)",
        "\t{",
        "\t\treturn seed == null ? null : BY_SEED.get(seed);",
        "\t}",
        "",
        "\t/** Yield constants for whatever is growing in a patch, or null if it has none. */",
        "\t@Nullable",
        "\tpublic static CropYield forProduce(@Nullable Produce produce)",
        "\t{",
        "\t\treturn produce == null ? null : BY_PRODUCE.get(produce);",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------
# CropXp.java
# --------------------------------------------------------------------------------------

# Wiki seed names that do not fall out of the enum name automatically.
XP_NAME_OVERRIDES = {
    "Acorn": "OAK",
    "Redberry": "REDBERRIES",
    "Cadavaberry": "CADAVABERRIES",
    "Dwellberry": "DWELLBERRIES",
    "Jangerberry": "JANGERBERRIES",
    "Whiteberry": "WHITEBERRIES",
}


def parse_crop_xp(path, known_seeds):
    """
    Reads the scraped XP table.

    Which columns a row carries depends on its patch family, and the file records that per
    row, because the families genuinely differ: trees give a big one-off check-health award
    and no per-harvest experience at all, while herbs give a little on planting and more per
    herb picked.
    """
    rows = []
    skipped = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            if line.startswith("#") or line.startswith("patch_type"):
                continue
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 7:
                continue
            _, name, _level, xp1, xp2, xp3, columns = parts[:7]
            cols = columns.split(",")

            const = XP_NAME_OVERRIDES.get(name.strip())
            if not const:
                guess = name.strip().upper().replace(" ", "_").replace("'", "")
                for cand in (guess, guess.replace("_TREE", ""), guess + "S", guess.rstrip("S")):
                    if cand in known_seeds:
                        const = cand
                        break
            if not const or const not in known_seeds:
                skipped.append(name.strip())
                continue

            values = {}
            for col, raw in zip(cols, (xp1, xp2, xp3)):
                if raw:
                    values[col] = float(raw)

            # A single unlabelled figure cannot be placed, so it is left out rather than
            # guessed at - see the note in TODO.md about fruit trees.
            if "unlabelled" in values or not values.get("plant"):
                skipped.append(name.strip())
                continue

            rows.append((const, values.get("plant", 0.0), values.get("check", 0.0),
                         values.get("harvest", 0.0)))
    return rows, skipped


def emit_crop_xp(rows):
    lines = [
        HEADER,
        "",
        "import java.util.EnumMap;",
        "import java.util.Map;",
        "import javax.annotation.Nullable;",
        "import lombok.Getter;",
        "import lombok.RequiredArgsConstructor;",
        "",
        "/**",
        " * Farming experience for each crop, per seed rather than per patch.",
        " *",
        " * <p>Scraped from the OSRS Wiki's seed tables; see {@code tools/crop-xp.tsv}. The",
        " * three awards are not all present for every crop, and the differences are the point:",
        " *",
        " * <ul>",
        " *   <li><b>Planting</b> — every crop, when the seed goes in.</li>",
        " *   <li><b>Check-health</b> — trees, hardwoods and bushes only, and it dwarfs the",
        " *       rest. A magic tree is over 13,000 for one click.</li>",
        " *   <li><b>Harvest</b> — per item picked. Trees give none at all: their logs are",
        " *       Woodcutting experience, not Farming.</li>",
        " * </ul>",
        " *",
        " * <p><b>Fruit trees pay both</b>, which is why they were absent for so long: the",
        " * patch/Seeds table gives one unlabelled figure that is neither the check award nor",
        " * the per-fruit rate but the sum of the whole cycle. The three components are on each",
        " * seed's own page, and six of the eight reconcile exactly against that total",
        " * ({@code plant + check + 6 x harvest}), which is what makes the split safe to use",
        " * rather than a guess. See {@code tools/crop-xp.tsv} for the two that do not.",
        " */",
        "@Getter",
        "@RequiredArgsConstructor",
        "public enum CropXp",
        "{",
    ]
    for i, (const, plant, check, harvest) in enumerate(rows):
        sep = "," if i < len(rows) - 1 else ";"
        lines.append(f"\t{const}(Seed.{const}, {plant}, {check}, {harvest}){sep}")

    lines += [
        "",
        "\tprivate static final Map<Seed, CropXp> BY_SEED = new EnumMap<>(Seed.class);",
        "\tprivate static final Map<Produce, CropXp> BY_PRODUCE = new EnumMap<>(Produce.class);",
        "",
        "\tstatic",
        "\t{",
        "\t\tfor (CropXp xp : values())",
        "\t\t{",
        "\t\t\tBY_SEED.put(xp.seed, xp);",
        "\t\t\tBY_PRODUCE.put(xp.seed.getProduce(), xp);",
        "\t\t}",
        "\t}",
        "",
        "\tprivate final Seed seed;",
        "\t/** Awarded when the seed is planted. */",
        "\tprivate final double plantXp;",
        "\t/** One-off award for checking health; 0 for crops that are not checked. */",
        "\tprivate final double checkXp;",
        "\t/** Per item picked; 0 for trees, which give none. */",
        "\tprivate final double harvestXp;",
        "",
        "\t@Nullable",
        "\tpublic static CropXp forSeed(@Nullable Seed seed)",
        "\t{",
        "\t\treturn seed == null ? null : BY_SEED.get(seed);",
        "\t}",
        "",
        "\t/**",
        "\t * Experience for whatever is growing in a patch.",
        "\t *",
        "\t * <p>Keyed on the produce rather than the seed because that is what a patch can",
        "\t * actually tell us — the varbit says \"a ranarr is growing here\", never which seed",
        "\t * went in. Returns null for crops with no published figures, chiefly fruit trees.",
        "\t */",
        "\t@Nullable",
        "\tpublic static CropXp forProduce(@Nullable Produce produce)",
        "\t{",
        "\t\treturn produce == null ? null : BY_PRODUCE.get(produce);",
        "\t}",
        "",
        "\t/**",
        "\t * Experience for planting one patch and taking the given number of harvests.",
        "\t *",
        "\t * <p>For a tree the harvest count is irrelevant, which falls out naturally: its",
        "\t * harvest award is zero and the check-health award carries the whole total.",
        "\t */",
        "\tpublic double totalFor(double harvests)",
        "\t{",
        "\t\treturn plantXp + checkXp + (harvestXp * Math.max(0, harvests));",
        "\t}",
        "}",
        "",
    ]
    return "\n".join(lines)


# --------------------------------------------------------------------------------------

def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src_root = sys.argv[1]

    produce_src = read(src_root, os.path.join("farming", "Produce.java"))
    impl_src = read(src_root, os.path.join("farming", "PatchImplementation.java"))
    world_src = read(src_root, os.path.join("farming", "FarmingWorld.java"))

    produce = parse_produce(produce_src)
    seed_consts = {p["const"] for p in produce}
    produce_stages = {p["const"]: p["stages"] for p in produce}
    impls = parse_implementations(impl_src, produce_stages)
    regions = parse_world(world_src)

    os.makedirs(OUT_PKG_DIR, exist_ok=True)
    outputs = {
        "Produce.java": emit_produce(produce, produce_src),
        "PatchImplementation.java": emit_implementations(impls),
        "PatchRules.java": emit_rules(impls),
        "FarmingWorldData.java": emit_world(regions, world_src),
        "Seed.java": emit_seeds(produce, produce_src),
        "ProtectionPayment.java": emit_payments(produce, produce_src),
    }
    xp_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "crop-xp.tsv")
    if os.path.exists(xp_path):
        xp_rows, xp_skipped = parse_crop_xp(xp_path, seed_consts)
        outputs["CropXp.java"] = emit_crop_xp(xp_rows)
        print(f"  {len(xp_rows)} crops with experience data"
              + (f", skipped {', '.join(sorted(set(xp_skipped)))}" if xp_skipped else ""))

    yield_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "crop-yield.tsv")
    if os.path.exists(yield_path):
        yield_rows, yield_skipped = parse_crop_yield(yield_path, seed_consts)
        outputs["CropYield.java"] = emit_crop_yield(yield_rows)
        print(f"  {len(yield_rows)} crops with yield data"
              + (f", skipped {', '.join(sorted(set(yield_skipped)))}" if yield_skipped else ""))

    for filename, text in outputs.items():
        with open(os.path.join(OUT_PKG_DIR, filename), "w", encoding="utf-8") as f:
            f.write(text)
        print(f"wrote {filename}")

    total_rules = sum(len(i["rules"]) for i in impls)
    total_patches = sum(len(r["patches"]) for r in regions)
    print(
        f"  {len(produce)} produce, {len(impls)} patch implementations "
        f"({total_rules} varbit rules), {len(regions)} regions ({total_patches} patches)"
    )


if __name__ == "__main__":
    main()
