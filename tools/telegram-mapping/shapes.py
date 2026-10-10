#!/usr/bin/env python3
"""
Structural shapes of the Telegram classes TeleVip needs.

The names R8 gives a class change with every release, but its *shape* barely does: what it extends,
which kept types its fields have, and the signatures of its methods written in terms of kept types.
Names under org.telegram.messenger.* and org.telegram.tgnet.** are kept by Telegram's own ProGuard
rules, so they survive obfuscation and make usable anchors.

This module builds those shapes from a dex dump, so a shipped shape can be matched against whatever
the installed build happens to call the class.

  shapes.py emit  --apk <apk> --mapping <mapping.txt> --out shapes.json
  shapes.py match --apk <new apk> --shapes shapes.json [--mapping <new mapping.txt> to score it]
"""

import argparse
import json
import re
import sys
from collections import Counter

import generate_mapping as g


# ---------------------------------------------------------------- shapes

OBFUSCATED_SIMPLE_NAME = re.compile(r"[a-z][a-z0-9]{0,3}")


def is_stable_name(name):
    """
    Whether obfuscation left this type name alone, decided from the name itself so that the device,
    which has no mapping, reaches the same answer. R8 names classes with one to a few lower case
    characters; everything Telegram's keep rules preserve is a normal CamelCase name.
    """
    simple = name[name.rfind(".") + 1:]
    return not OBFUSCATED_SIMPLE_NAME.fullmatch(simple)


def stable_types(dex, mapping_classes=None):
    """Type names an obfuscated build leaves alone, so they mean the same thing in any build."""
    return {name for name in dex if is_stable_name(name)}


def canon_type(descriptor, stable=None):
    """
    Keeps a type name only when obfuscation leaves it alone, so a shape never depends on a renamed
    one. Decided from the name itself, framework and library types included, so that the device
    reaches the same answer with nothing but the class in front of it.
    """
    dims = 0
    while descriptor.startswith("["):
        dims += 1
        descriptor = descriptor[1:]
    if not descriptor.startswith("L"):
        return "[" * dims + descriptor
    name = descriptor[1:-1].replace("/", ".")
    return "[" * dims + (descriptor if is_stable_name(name) else "L?;")


def canon_descriptor(descriptor, stable):
    params = g.split_params(descriptor)
    ret = descriptor[descriptor.index(")") + 1:]
    return "(" + "".join(canon_type(p, stable) for p in params) + ")" + canon_type(ret, stable)


def super_chain(name, dex, stable):
    """
    (nearest ancestor whose name is stable, how many steps up it is). Both come straight from the
    dex, so the device can work this out for every class without loading any of them, and use it to
    narrow the candidates for a target down from twenty thousand classes to a few hundred.
    """
    seen = set()
    depth = 0
    current = name
    while current and current not in seen:
        seen.add(current)
        entry = dex.get(current)
        parent = entry["super"] if entry else None
        if parent is None:
            return (current, depth) if depth else ("java.lang.Object", 0)
        depth += 1
        if is_stable_name(parent):
            return parent, depth
        current = parent
    return "java.lang.Object", depth


def shape_of(entry, stable):
    fields = Counter(canon_type(t, stable) for t in entry["fields"].values())
    # a static initialiser is in the dex but reflection never shows one, so it is left out of the
    # shape to keep both sides describing the same set of members
    methods = Counter(canon_descriptor(d, stable) for n, d in entry["methods"] if n != "<clinit>")
    return {
        "super": canon_type("L" + (entry["super"] or "java.lang.Object").replace(".", "/") + ";", stable),
        "counts": [sum(fields.values()), sum(methods.values())],
        "fields": dict(fields),
        "methods": dict(methods),
    }


def with_chain(shape, name, dex, stable):
    ancestor, depth = super_chain(name, dex, stable)
    shape = dict(shape)
    shape["chain"] = [ancestor, depth]
    return shape


def similarity(a, b):
    """
    How much of the old shape the candidate still has. Members are added far more often than
    removed, so the score asks how much of the old shape survived rather than how equal they are,
    with a mild penalty for a candidate that is much bigger.
    """
    if a["super"] != b["super"]:
        return 0.0
    score = 0.0
    for part, weight in (("fields", 0.4), ("methods", 0.6)):
        old, new = Counter(a[part]), Counter(b[part])
        total = sum(old.values())
        if total == 0:
            score += weight if sum(new.values()) == 0 else weight * 0.5
            continue
        kept = sum((old & new).values())
        extra = max(0, sum(new.values()) - total)
        score += weight * (kept / total) * (1.0 / (1.0 + extra / (4.0 * total)))
    return score


# ---------------------------------------------------------------- anchors

def unstable_slots(descriptor, stable):
    """Positions in a descriptor whose type was renamed, in order: parameters then the return type."""
    parts = g.split_params(descriptor) + [descriptor[descriptor.index(")") + 1:]]
    return [i for i, part in enumerate(parts) if canon_type(part, stable) == "L?;"], parts


def anchors_for(target_obf, dex, classes, stable, resolvable):
    """
    Ways to find a class again through one that is easier to recognise: a class whose own shape is
    distinctive enough, and that names this one in a field or a method signature. Used for the small
    callback interfaces, whose shapes are identical to each other and cannot be told apart alone.
    """
    found = []
    for orig in resolvable:
        mc = classes.get(orig)
        obf = mc.obf if mc else orig
        entry = dex.get(obf)
        if entry is None:
            continue
        anchor_stable = obf == orig
        by_canon = {}
        for name, descriptor in sorted(entry["methods"]):
            by_canon.setdefault(canon_descriptor(descriptor, stable), []).append((name, descriptor))
        for canon, members in by_canon.items():
            for index, (name, descriptor) in enumerate(members):
                slots, parts = unstable_slots(descriptor, stable)
                for slot_number, position in enumerate(slots):
                    if parts[position] == "L" + target_obf.replace(".", "/") + ";":
                        found.append({"class": orig, "stable": anchor_stable, "canon": canon,
                                      "member": index, "count": len(members), "slot": slot_number})
                        if len(found) >= 3:
                            return found
    return found


def resolve_by_anchor(anchor, dex, resolved_classes, stable):
    """Reads the type back out of the signature the anchor pointed at."""
    owner = anchor["class"] if anchor.get("stable") else resolved_classes.get(anchor["class"])
    entry = dex.get(owner) if owner else None
    if entry is None:
        return None
    members = []
    for name, descriptor in sorted(entry["methods"]):
        if canon_descriptor(descriptor, stable) == anchor["canon"]:
            members.append(descriptor)
    if len(members) != anchor["count"] or anchor["member"] >= len(members):
        return None
    descriptor = members[anchor["member"]]
    slots, parts = unstable_slots(descriptor, stable)
    if anchor["slot"] >= len(slots):
        return None
    part = parts[slots[anchor["slot"]]]
    return part[1:-1].replace("/", ".") if part.startswith("L") else None


def looks_right(found, wanted, dex, stable):
    """
    An anchor names a class by where it is mentioned in another one, so a member that moved can send
    it to something else entirely. What it names still has to look like what we are after.
    """
    entry = dex.get(found)
    if entry is None or wanted is None:
        return entry is not None
    chain = wanted.get("chain")
    if chain and list(super_chain(found, dex, stable)) != list(chain):
        return False
    counts = wanted.get("counts")
    if counts and not plausible_size(entry, counts[0], counts[1]):
        return False
    return similarity(wanted, shape_of(entry, stable)) >= 0.75


def plausible_size(entry, want_fields, want_methods):
    have_fields = len(entry["fields"])
    have_methods = len([1 for n, _ in entry["methods"] if n != "<clinit>"])
    return within(have_fields, want_fields) and within(have_methods, want_methods)


def within(have, want):
    # a release adds and removes a few members; beyond this it is a different class
    return want * 0.6 - 3 <= have <= want * 1.8 + 5


# ---------------------------------------------------------------- dex side

def load(apk, mapping_path):
    dex = g.parse_dex(apk)
    classes, _ = g.parse_mapping(mapping_path)
    return dex, classes


def binary_name(literal, classes, dex):
    """TeleVip spells inner classes with a dot; the mapping and the dex use Outer$Inner."""
    resolved = g.resolve_literal(literal, classes, dex)
    return resolved or literal


def shapes_for(dex, classes, names):
    stable = stable_types(dex, classes)
    out = {}
    for literal in names:
        orig = binary_name(literal, classes, dex)
        mc = classes.get(orig)
        entry = dex.get(mc.obf if mc else orig)
        if entry is None:
            continue
        out[orig] = shape_of(entry, stable)
    return out, stable


def decide_classes(data, dex, stable, threshold=0.75, margin=0.05):
    """
    What the device would make of this build: original class name -> the name it goes by here, or
    None where nothing fits clearly enough. The rules are the ones StructuralResolver applies.
    """
    candidates = {name: with_chain(shape_of(entry, stable), name, dex, stable)
                  for name, entry in dex.items()}
    decided = {}
    for orig, shape in sorted(data["shapes"].items()):
        chain = shape.get("chain")
        counts = shape.get("counts")
        scored = []
        for name, candidate in candidates.items():
            if chain and candidate.get("chain") != list(chain):
                continue
            if counts and not plausible_size(dex[name], counts[0], counts[1]):
                continue
            score = similarity(shape, candidate)
            if score >= threshold:
                scored.append((score, name))
        scored.sort(reverse=True)
        best = scored[0] if scored else None
        runner_up = scored[1][0] if len(scored) > 1 else 0.0
        decided[orig] = best[1] if best and best[0] - runner_up >= margin else None
    # a class whose shape is shared with others may still be named in one that was matched
    for orig in list(decided):
        if decided[orig]:
            continue
        for anchor in data.get("anchors", {}).get(orig, []):
            found = resolve_by_anchor(anchor, dex, decided, stable)
            if found and looks_right(found, data["shapes"].get(orig), dex, stable):
                decided[orig] = found
                break
    return decided


# ---------------------------------------------------------------- commands

def cmd_emit(args):
    dex, classes = load(args.apk, args.mapping)
    names = json.load(open(args.names, encoding="utf-8")) if args.names else sorted(
        n for n in classes if n.startswith("org.telegram.ui."))
    all_shapes, stable = shapes_for(dex, classes, names)
    # a class whose name obfuscation leaves alone needs no matching: it is found by that name. Its
    # members may still have been renamed, which is why those are described further down regardless.
    shapes = {orig: shape for orig, shape in all_shapes.items()
              if classes.get(orig) is not None and classes[orig].obf != orig}
    for orig in shapes:
        mc = classes.get(orig)
        shapes[orig] = with_chain(shapes[orig], mc.obf if mc else orig, dex, stable)
    # classes that keep their name are the most useful anchors, then the ones we resolve by shape
    anchor_pool = [n for n in classes if n.startswith("org.telegram.") and classes[n].obf == n]
    anchor_pool += [n for n in shapes if n not in anchor_pool]
    anchors = {}
    for orig in shapes:
        mc = classes.get(orig)
        found = anchors_for(mc.obf if mc else orig, dex, classes, stable,
                            [n for n in anchor_pool if n != orig])
        if found:
            anchors[orig] = found
    members = {}
    if args.members:
        import members as mem
        wanted_fields, wanted_methods = mem.wanted_from(args.members, list(all_shapes))
        for orig in all_shapes:
            described = mem.describe(dex, classes, stable, orig,
                                     sorted(wanted_fields.get(orig, ())),
                                     sorted(wanted_methods.get(orig, ())))
            if described and (described["fields"] or described["methods"]):
                members[orig] = described
    json.dump({"version": args.version, "shapes": shapes, "anchors": anchors, "members": members},
              open(args.out, "w", encoding="utf-8"), indent=1)
    print(f"{len(shapes)} shapes, {len(anchors)} with anchors, {len(members)} with members -> {args.out}")


def cmd_match(args):
    data = json.load(open(args.shapes, encoding="utf-8"))
    dex = g.parse_dex(args.apk)
    truth, _ = (g.parse_mapping(args.mapping) if args.mapping else ({}, None))
    stable = stable_types(dex, truth or {})
    decided = decide_classes(data, dex, stable, args.threshold, args.margin)
    members = {}
    if args.members:
        import members as mem
        for orig, description in data.get("members", {}).items():
            entry = dex.get(decided.get(orig) or orig)
            if entry is not None:
                members[orig] = mem.resolve(entry, stable, description)

    expected = {mc.orig: mc.obf for mc in truth.values()} if truth else {}
    right = wrong = unresolved = 0
    for orig in sorted(data["shapes"]):
        found = decided.get(orig)
        if not expected:
            print(f"{orig} -> {found}")
            continue
        want = expected.get(orig)
        if found is None:
            unresolved += 1
            if args.verbose:
                print(f"  unresolved {orig}")
        elif found == want:
            right += 1
        else:
            wrong += 1
            print(f"  WRONG {orig}: picked {found}, wanted {want}")
    if expected:
        print("")
        print(f"classes resolved correctly {right}/{right + wrong + unresolved}, wrong {wrong}, unresolved {unresolved}")

    if not members:
        return
    right = wrong = unresolved = 0
    for orig, found in sorted(members.items()):
        described = data["members"][orig]
        for kind in ("fields", "methods"):
            for member in described[kind]:
                got = found[kind].get(member)
                want = mem.truth_for(truth, orig, member, kind) if truth else None
                if got is None:
                    unresolved += 1
                    if args.verbose:
                        print(f"  unresolved {orig.rsplit('.', 1)[-1]}#{member}")
                elif want is None or got == want:
                    right += 1
                else:
                    wrong += 1
                    print(f"  WRONG {orig.rsplit('.', 1)[-1]}#{member}: got {got}, wanted {want}")
    print(f"members resolved {right}/{right + wrong + unresolved}, wrong {wrong}, unresolved {unresolved}")


def main():
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)

    e = sub.add_parser("emit")
    e.add_argument("--apk", required=True)
    e.add_argument("--mapping", required=True)
    e.add_argument("--names", help="json list of original class names, default: all of org.telegram.ui")
    e.add_argument("--members", help="generated mapping json, to describe the members TeleVip uses")
    e.add_argument("--version", default="")
    e.add_argument("--out", required=True)
    e.set_defaults(func=cmd_emit)

    m = sub.add_parser("match")
    m.add_argument("--apk", required=True)
    m.add_argument("--shapes", required=True)
    m.add_argument("--mapping", help="mapping of the APK, to score the result")
    m.add_argument("--threshold", type=float, default=0.75)
    m.add_argument("--margin", type=float, default=0.05)
    m.add_argument("--members", action="store_true", help="also locate the members described in the shapes")
    m.add_argument("--verbose", action="store_true")
    m.set_defaults(func=cmd_match)

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
