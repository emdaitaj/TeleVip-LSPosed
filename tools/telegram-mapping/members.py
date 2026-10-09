#!/usr/bin/env python3
"""
Measures whether the fields and methods TeleVip uses can be found structurally, the way the
on-device resolver would, once its class has been located by shapes.py.

A member is described by what survives obfuscation: the canonical type (or descriptor) of the
member, written in terms of the type names Telegram's ProGuard rules keep, plus its position among
the members of the class that share that canonical form.

  members.py measure --old-apk A --old-mapping A.txt --new-apk B --new-mapping B.txt --names n.json --members m.json
"""

import argparse
import json
from collections import defaultdict

import generate_mapping as g
import shapes as sh


def wanted_from(generated_path, class_names=None):
    """
    The fields and methods TeleVip looks up, taken from a generated mapping. It names a class by its
    simple name, so the full names are needed to tie the two together; classes obfuscation left alone
    are not listed in the mapping at all, yet their members are still renamed.
    """
    wanted_fields = defaultdict(set)
    wanted_methods = defaultdict(set)
    generated = json.load(open(generated_path, encoding="utf-8"))
    key_to_class = {c["o"].rsplit(".", 1)[-1].replace(".", "$"): c["o"] for c in generated["classes"]}
    for full in class_names or ():
        key_to_class.setdefault(full.rsplit(".", 1)[-1].replace(".", "$"), full)
    for f in generated["fields"]:
        full = key_to_class.get(f["c"])
        if full:
            wanted_fields[full].add(f["o"])
    for mm in generated["methods"]:
        full = key_to_class.get(mm["c"])
        if full and "(" not in mm["o"]:
            wanted_methods[full].add(mm["o"])
    return wanted_fields, wanted_methods


def real_method_name(mc, original):
    """
    The name of the method that actually exists. A mapping lists an inlined method once per place it
    was inlined into, and those frames carry the name of the host; only the outermost one is real.
    """
    if mc is None:
        return original
    for e in mc.methods:
        if e["name"] == original and not e["qualified"] and e["outer"]:
            return e["obf"]
    for e in mc.methods:
        if e["name"] == original and not e["qualified"]:
            return e["obf"]
    return original


def ordered_members(dex_entry, stable):
    """(name, canonical form) for fields and for methods, in the order the dex lists them."""
    fields = [(n, sh.canon_type(d, stable)) for n, d in dex_entry["fields"].items()]
    methods = [(n, sh.canon_descriptor(d, stable))
               for n, d in dex_entry["methods"] if n != "<clinit>"]
    return fields, methods


def member_index(dex_entry, stable):
    """canonical form -> ordered list of real names, for fields and for methods separately."""
    fields_list, methods_list = ordered_members(dex_entry, stable)
    fields = defaultdict(list)
    for name, canon in fields_list:
        fields[canon].append(name)
    methods = defaultdict(list)
    for name, canon in methods_list:
        methods[canon].append(name)
    return fields, methods


def describe(dex, classes, stable, class_name, wanted_fields, wanted_methods):
    """What a shipped description of these members looks like, built from the old build."""
    mc = classes.get(class_name)
    obf = mc.obf if mc else class_name
    entry = dex.get(obf)
    if entry is None:
        return None
    fields, methods = member_index(entry, stable)
    field_list, method_list = ordered_members(entry, stable)
    out = {"fields": {}, "methods": {}}

    def entry_for(real, by_canon, ordered):
        names = [n for n, _ in ordered]
        if real not in names:
            return None
        absolute = names.index(real)
        canon = ordered[absolute][1]
        same = by_canon[canon]
        # three things have to agree before the device claims a member: its position among the
        # members of the same shape, its position among all of them, and the shapes of the two it
        # sits between. A member added in one place and removed in another shifts the first two
        # together, and only the neighbours catch that.
        before = ordered[absolute - 1][1] if absolute > 0 else ""
        after = ordered[absolute + 1][1] if absolute + 1 < len(ordered) else ""
        return [canon, same.index(real), len(same), absolute, len(ordered), before, after]

    for original in wanted_fields:
        real = mc.fields[original][1] if mc and original in mc.fields else original
        found = entry_for(real, fields, field_list)
        if found:
            out["fields"][original] = found
    for original in wanted_methods:
        found = entry_for(real_method_name(mc, original), methods, method_list)
        if found:
            # the parameter types of the method as it is written in Telegram's sources, canonical so
            # they can be compared in any build: the device works out from these how R8 rewrote the
            # parameter list, which is what a hook has to be installed with
            declared = None
            if mc:
                for e in mc.methods:
                    if e["name"] == original and not e["qualified"] and e["outer"]:
                        declared = [sh.canon_type(g.descriptor(a, {}), stable) for a in e["args"]]
                        break
            out["methods"][original] = found + [declared or []]
    return out


def resolve(dex_entry, stable, description):
    """Finds the members again in a build where everything was renamed."""
    fields, methods = member_index(dex_entry, stable)
    field_list, method_list = ordered_members(dex_entry, stable)
    resolved = {"fields": {}, "methods": {}}

    def pick(description_entry, by_canon, ordered):
        canon, same_index, same_count, absolute, total = description_entry[:5]
        before = description_entry[5] if len(description_entry) > 5 else ""
        after = description_entry[6] if len(description_entry) > 6 else ""
        names = by_canon.get(canon, [])
        if len(names) != same_count or same_index >= len(names):
            return None
        by_shape = names[same_index]
        if len(ordered) != total or absolute >= len(ordered):
            return None
        by_position, canon_there = ordered[absolute]
        if by_shape != by_position or canon_there != canon:
            return None
        have_before = ordered[absolute - 1][1] if absolute > 0 else ""
        have_after = ordered[absolute + 1][1] if absolute + 1 < len(ordered) else ""
        return by_shape if have_before == before and have_after == after else None

    for original, description_entry in description["fields"].items():
        found = pick(description_entry, fields, field_list)
        if found is not None:
            resolved["fields"][original] = found
    for original, description_entry in description["methods"].items():
        found = pick(description_entry, methods, method_list)
        if found is not None:
            resolved["methods"][original] = found
    return resolved


def truth_for(classes, class_name, member, kind):
    mc = classes.get(class_name)
    if mc is None:
        return member
    if kind == "fields":
        return mc.fields[member][1] if member in mc.fields else member
    return real_method_name(mc, member)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--old-apk", required=True)
    ap.add_argument("--old-mapping", required=True)
    ap.add_argument("--new-apk", required=True)
    ap.add_argument("--new-mapping", required=True)
    ap.add_argument("--members", required=True, help="generated mapping json, for the members TeleVip uses")
    ap.add_argument("--verbose", action="store_true")
    args = ap.parse_args()

    wanted_fields = defaultdict(set)
    wanted_methods = defaultdict(set)
    generated = json.load(open(args.members, encoding="utf-8"))
    key_to_class = {}
    for c in generated["classes"]:
        key_to_class[c["o"].rsplit(".", 1)[-1]] = c["o"]
    for f in generated["fields"]:
        full = key_to_class.get(f["c"])
        if full:
            wanted_fields[full].add(f["o"])
    for m in generated["methods"]:
        full = key_to_class.get(m["c"])
        if full and "(" not in m["o"]:
            wanted_methods[full].add(m["o"])

    old_dex, old_classes = sh.load(args.old_apk, args.old_mapping)
    new_dex, new_classes = sh.load(args.new_apk, args.new_mapping)
    old_stable = sh.stable_types(old_dex, old_classes)
    new_stable = sh.stable_types(new_dex, new_classes)

    right = wrong = unresolved = 0
    for class_name in sorted(set(wanted_fields) | set(wanted_methods)):
        description = describe(old_dex, old_classes, old_stable, class_name,
                               sorted(wanted_fields[class_name]), sorted(wanted_methods[class_name]))
        if description is None:
            continue
        mc_new = new_classes.get(class_name)
        new_entry = new_dex.get(mc_new.obf if mc_new else class_name)
        if new_entry is None:
            continue
        found = resolve(new_entry, new_stable, description)
        for kind in ("fields", "methods"):
            for member in description[kind]:
                want = truth_for(new_classes, class_name, member, kind)
                got = found[kind].get(member)
                if got is None:
                    unresolved += 1
                    if args.verbose:
                        print(f"  unresolved {class_name.rsplit('.', 1)[-1]}#{member}")
                elif got == want:
                    right += 1
                else:
                    wrong += 1
                    print(f"  WRONG {class_name.rsplit('.', 1)[-1]}#{member}: got {got}, wanted {want}")
    total = right + wrong + unresolved
    print(f"\nmembers resolved correctly {right}/{total}, wrong {wrong}, unresolved {unresolved}")


if __name__ == "__main__":
    main()
