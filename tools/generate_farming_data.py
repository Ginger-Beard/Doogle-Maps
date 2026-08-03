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
        "import java.util.List;",
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

def main():
    if len(sys.argv) != 2:
        raise SystemExit(__doc__)
    src_root = sys.argv[1]

    produce_src = read(src_root, os.path.join("farming", "Produce.java"))
    impl_src = read(src_root, os.path.join("farming", "PatchImplementation.java"))
    world_src = read(src_root, os.path.join("farming", "FarmingWorld.java"))

    produce = parse_produce(produce_src)
    produce_stages = {p["const"]: p["stages"] for p in produce}
    impls = parse_implementations(impl_src, produce_stages)
    regions = parse_world(world_src)

    os.makedirs(OUT_PKG_DIR, exist_ok=True)
    outputs = {
        "Produce.java": emit_produce(produce, produce_src),
        "PatchImplementation.java": emit_implementations(impls),
        "PatchRules.java": emit_rules(impls),
        "FarmingWorldData.java": emit_world(regions, world_src),
    }
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
