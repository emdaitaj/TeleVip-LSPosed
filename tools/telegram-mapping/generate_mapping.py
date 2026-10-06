#!/usr/bin/env python3
"""
Generates a TeleVip obfuscation mapping (assets/clients/<Client>/<versionCode>.json)
for an R8-obfuscated official Telegram build.

Since 12.10.2 the official Telegram release builds run R8 with obfuscation enabled
(TMessagesProj/proguard-rules.pro no longer has -dontobfuscate), so everything outside
org.telegram.messenger.* / org.telegram.tgnet.** gets renamed on every release, and R8
also inlines methods, drops unused parameters and reorders the remaining ones.

Inputs:
  --mapping   R8 mapping.txt from building the exact same Telegram commit
              (the build is reproducible, see README.md).
  --apk       the official APK the mapping is for; every generated name is verified
              against its dex, a mapping that does not belong to the APK is rejected.
  --src       TeleVip java sources, the names to map are collected from there.

Output entries (only for names TeleVip references):
  classes      original class name -> obfuscated class name
  fields       Class#field -> obfuscated name
  methods      Class#method, Class#method(ParamType,...) and alias codes -> obfuscated name
  methodAlias  Class#method -> alias code, picks the overload for plain lookups
  params       Class#method[(...)] -> parameter types of the real method when R8 changed them
  argOrder     Class#method[(...)] -> original argument index for every real parameter
               (R8 sorts parameters by type)
"""

import argparse
import json
import os
import re
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

PRIMITIVES = {
    "void": "V", "boolean": "Z", "byte": "B", "char": "C", "short": "S",
    "int": "I", "long": "J", "float": "F", "double": "D",
}
PRIMITIVE_NAMES = {v: k for k, v in PRIMITIVES.items()}

# Class keys TeleVip uses for its *own* objects (module-owned RecyclerView in the
# fallback settings adapter), these must keep their original member names.
MODULE_OWNED_KEYS = {"RecyclerListView"}

TARGET_PREFIXES = ("org.telegram.", "androidx.")

# synthetic wrappers that only call the inlined method, hooking them is equivalent
WRAPPER_PREFIXES = ("$r8$lambda$", "lambda$", "access$")


# ---------------------------------------------------------------- mapping.txt

class MClass:
    def __init__(self, orig, obf):
        self.orig = orig
        self.obf = obf
        self.fields = {}            # name -> (type, obf)
        self.methods = []           # dicts


def parse_mapping(path):
    classes = {}
    cur = None
    last_member = None
    group_key = None
    group = []
    all_classes = []

    def flush_group():
        # within a group of lines sharing the same minified range and target name the
        # last line is the outermost frame (the method that exists), the others are inlined into it
        if group:
            group[-1]["outer"] = True
            for e in group[:-1]:
                e["host"] = group[-1]

    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.rstrip("\n")
            if not line.strip():
                continue
            stripped = line.strip()
            if stripped.startswith("#"):
                if last_member is not None and "residualsignature" in stripped:
                    try:
                        last_member["residual"] = json.loads(stripped[1:].strip())["signature"]
                    except Exception:
                        pass
                continue
            if not line.startswith(" "):
                flush_group()
                group, group_key = [], None
                m = re.match(r"^(\S+) -> (\S+):$", line)
                if not m:
                    continue
                cur = MClass(m.group(1), m.group(2))
                all_classes.append(cur)
                if not cur.obf.startswith("R8$$REMOVED$$"):  # classes R8 dropped entirely
                    classes[cur.orig] = cur
                last_member = None
                continue
            if cur is None:
                continue
            m = re.match(r"^\s+(?:(\d+):(\d+):)?(\S+) ([^\s(]+)\(([^)]*)\)(?::\d+(?::\d+)?)? -> (\S+)$", line)
            if m:
                rng = (m.group(1), m.group(2))
                name = m.group(4)
                entry = {
                    "cls": cur, "ret": m.group(3), "name": name,
                    "args": tuple(a for a in m.group(5).split(",") if a),
                    "obf": m.group(6), "outer": rng[0] is None, "host": None,
                    "qualified": "." in name, "residual": None,
                }
                key = (rng, m.group(6)) if rng[0] is not None else None
                if key is None or key != group_key:
                    flush_group()
                    group, group_key = [], key
                if key is not None:
                    group.append(entry)
                cur.methods.append(entry)
                last_member = entry
                continue
            m = re.match(r"^\s+(\S+) (\S+) -> (\S+)$", line)
            if m:
                flush_group()
                group, group_key = [], None
                cur.fields[m.group(2)] = (m.group(1), m.group(3))
                last_member = None
    flush_group()

    # the residual signature is only written after the first line of a method, share it
    for mc in all_classes:
        residuals = {}
        for e in mc.methods:
            if e["residual"]:
                residuals[(e["name"], e["args"], e["obf"])] = e["residual"]
        for e in mc.methods:
            if not e["residual"]:
                e["residual"] = residuals.get((e["name"], e["args"], e["obf"]))
    return classes, all_classes


# ---------------------------------------------------------------- dex (via dexdump)

def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") \
        or os.path.join(os.environ.get("LOCALAPPDATA", ""), "Android", "Sdk")
    bt = os.path.join(sdk, "build-tools")
    if os.path.isdir(bt):
        for v in sorted(os.listdir(bt), reverse=True):
            for exe in ("dexdump.exe", "dexdump"):
                p = os.path.join(bt, v, exe)
                if os.path.isfile(p):
                    return p
    sys.exit("dexdump not found, set ANDROID_HOME")


def parse_dex(apk):
    dexdump = find_dexdump()
    classes = {}
    with tempfile.TemporaryDirectory() as tmp, zipfile.ZipFile(apk) as z:
        for n in z.namelist():
            if not re.match(r"^classes\d*\.dex$", n):
                continue
            p = z.extract(n, tmp)
            out = subprocess.run([dexdump, "-l", "plain", p], capture_output=True,
                                 text=True, encoding="utf-8", errors="replace").stdout
            parse_dexdump(out, classes)
    return classes


def parse_dexdump(text, classes):
    cur = None
    section = None
    pending_name = None
    for line in text.splitlines():
        m = re.match(r"^\s+Class descriptor\s+: 'L(.+);'", line)
        if m:
            name = m.group(1).replace("/", ".")
            cur = {"super": None, "fields": {}, "methods": set()}
            classes[name] = cur
            section = None
            continue
        if cur is None:
            continue
        m = re.match(r"^\s+Superclass\s+: 'L(.+);'", line)
        if m:
            cur["super"] = m.group(1).replace("/", ".")
            continue
        s = line.strip()
        if s in ("Static fields     -", "Instance fields   -"):
            section = "f"
            continue
        if s in ("Direct methods    -", "Virtual methods   -"):
            section = "m"
            continue
        if s.startswith("Interfaces") or s.startswith("source_file_idx"):
            section = None
            continue
        m = re.match(r"^\s+name\s+: '(.+)'", line)
        if m:
            pending_name = m.group(1)
            continue
        m = re.match(r"^\s+type\s+: '(.+)'", line)
        if m and pending_name is not None:
            if section == "f":
                cur["fields"][pending_name] = m.group(1)
            elif section == "m":
                cur["methods"].add((pending_name, m.group(1)))
            pending_name = None


# ---------------------------------------------------------------- type helpers

def descriptor(java_type, cmap):
    dims = 0
    while java_type.endswith("[]"):
        dims += 1
        java_type = java_type[:-2]
    if java_type in PRIMITIVES:
        d = PRIMITIVES[java_type]
    else:
        mc = cmap.get(java_type)
        d = "L" + (mc.obf if mc else java_type).replace(".", "/") + ";"
    return "[" * dims + d


def original_descriptor(entry, cmap):
    return "(" + "".join(descriptor(a, cmap) for a in entry["args"]) + ")" + descriptor(entry["ret"], cmap)


def method_descriptor(entry, cmap):
    return entry["residual"] or original_descriptor(entry, cmap)


def split_params(desc):
    """'(ILjava/lang/String;[B)V' -> ['I', 'Ljava/lang/String;', '[B']"""
    body = desc[1:desc.index(")")]
    out, i = [], 0
    while i < len(body):
        j = i
        while body[j] == "[":
            j += 1
        if body[j] == "L":
            j = body.index(";", j)
        out.append(body[i:j + 1])
        i = j + 1
    return out


def java_name(d):
    """descriptor -> name TeleVip can load (int, java.lang.String, java.lang.String[])"""
    dims = 0
    while d.startswith("["):
        dims += 1
        d = d[1:]
    base = PRIMITIVE_NAMES[d] if d in PRIMITIVE_NAMES else d[1:-1].replace("/", ".")
    return base + "[]" * dims


def simple_name(java_type):
    return java_type.rsplit(".", 1)[-1]


def precise(member, args):
    return f"{member}({','.join(simple_name(a) for a in args)})"


def sig_code(args):
    """Signature suffix used by TeleVip's methodAlias keys, e.g. of(int,int,int,int,CharSequence,CharSequence) -> ofIIIICC."""
    out = []
    for a in args:
        base = a.rstrip("[]")
        if base in PRIMITIVES:
            out.append(PRIMITIVES[base])
        elif base.startswith(("java.", "android.")):
            out.append(base.rsplit(".", 1)[-1][0].upper())
        else:
            out.append("O")
    return "".join(out)


def stable_permutation(orig, real):
    """index into orig for every position of real, None unless real is a reordering of orig"""
    if sorted(orig) != sorted(real) or orig == real:
        return None
    used, perm = set(), []
    for t in real:
        i = next(k for k, o in enumerate(orig) if o == t and k not in used)
        used.add(i)
        perm.append(i)
    return perm


# ---------------------------------------------------------------- TeleVip sources

CLASS_LITERAL = re.compile(r'"((?:org\.telegram|androidx)\.[A-Za-z0-9_.$]+)"')
MEMBER_PAIR = re.compile(r'"([A-Za-z_][A-Za-z0-9_$]*)"\s*,\s*"([A-Za-z_<][A-Za-z0-9_$>]*)"')
MEMBER_ARRAY = re.compile(r'"([A-Za-z_][A-Za-z0-9_$]*)"\s*,\s*new String\[\]\s*\{([^}]*)\}')


def collect_sources(src):
    class_literals, pairs = set(), set()
    for root, _, files in os.walk(src):
        for fn in files:
            if not fn.endswith(".java"):
                continue
            text = open(os.path.join(root, fn), encoding="utf-8").read()
            class_literals.update(CLASS_LITERAL.findall(text))
            pairs.update(MEMBER_PAIR.findall(text))
            for key, body in MEMBER_ARRAY.findall(text):
                for name in re.findall(r'"([^"]+)"', body):
                    pairs.add((key, name))
    return class_literals, pairs


def resolve_literal(lit, mclasses, dex):
    """Class literal as written in TeleVip -> original binary name (handles Outer.Inner)."""
    candidate = lit
    while True:
        if candidate in mclasses or candidate in dex:
            return candidate
        if "." not in candidate:
            return None
        head, tail = candidate.rsplit(".", 1)
        if head.rsplit(".", 1)[-1][:1].islower():  # reached the package, give up
            return None
        candidate = head + "$" + tail


def key_forms(orig):
    simple = orig.rsplit(".", 1)[-1]
    parts = simple.split("$")
    return {"$".join(parts[i:]) for i in range(len(parts))}


# ---------------------------------------------------------------- main

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--mapping", required=True)
    ap.add_argument("--apk", required=True)
    ap.add_argument("--src", required=True)
    ap.add_argument("--alias", nargs="*", default=[], help="existing Alias/*.json to take overload choices from")
    ap.add_argument("--out", required=True)
    ap.add_argument("--version-code", required=True)
    ap.add_argument("--version-name", required=True)
    args = ap.parse_args()

    mclasses, all_mclasses = parse_mapping(args.mapping)
    dex = parse_dex(args.apk)
    inverse = {mc.obf: mc for mc in mclasses.values()}

    # sanity check: the mapping has to describe this exact APK
    checked = bad = 0
    for mc in mclasses.values():
        if mc.orig.startswith("org.telegram."):
            checked += 1
            if mc.obf not in dex:
                bad += 1
    if checked == 0 or bad > 0:
        sys.exit(f"mapping does not match the APK ({bad}/{checked} org.telegram classes missing)")

    literals, pairs = collect_sources(args.src)

    aliases = {}
    for p in args.alias:
        aliases.update(json.load(open(p, encoding="utf-8")).get("methodAlias", {}))

    report = defaultdict(list)
    out_classes, out_fields, out_methods, out_alias, out_params, out_order = {}, {}, {}, {}, {}, {}

    # ---- classes
    referenced = set()
    for lit in sorted(literals):
        orig = resolve_literal(lit, mclasses, dex)
        if orig is None:
            if lit.rsplit(".", 1)[-1][:1].islower():
                continue  # package name
            report["class removed by R8"].append(lit)
            continue
        referenced.add(orig)
        mc = mclasses.get(orig)
        if mc and mc.obf != lit:
            out_classes[lit] = mc.obf

    # ---- key index
    index = defaultdict(set)
    for orig in mclasses:
        if orig.startswith(TARGET_PREFIXES):
            for form in key_forms(orig):
                index[form].add(orig)

    def chain(orig):
        out = [orig]
        mc = mclasses.get(orig)
        obf = mc.obf if mc else orig
        while obf in dex and dex[obf]["super"]:
            obf = dex[obf]["super"]
            o = inverse.get(obf)
            out.append(o.orig if o else obf)
        return out

    def exists(e):
        dexcls = dex.get(e["cls"].obf)
        return dexcls is not None and (e["obf"], method_descriptor(e, mclasses)) in dexcls["methods"]

    # frames of methods inlined into other classes carry the qualified original name
    inlined_elsewhere = defaultdict(list)
    for mc in all_mclasses:
        for e in mc.methods:
            if e["qualified"] and e["host"] is not None:
                inlined_elsewhere[e["name"]].append(e)

    def inline_hosts(cls, name):
        hosts = {}
        frames = [e for e in mclasses[cls].methods if e["name"] == name] + inlined_elsewhere.get(cls + "." + name, [])
        for e in frames:
            if e["host"] is not None:
                h = e["host"]
                hosts[(h["cls"].orig, h["name"], h["args"], h["obf"])] = (e, h)
        return list(hosts.values())

    def is_wrapper(host, name):
        short = host["name"].split(".")[-1]
        return short.startswith(WRAPPER_PREFIXES) or re.fullmatch(re.escape(name) + r"\$\d+", short) is not None

    def lookup_methods(orig, name):
        """[(original entry, real entry)] for every overload of `name` in `orig` or its superclasses"""
        for cls in chain(orig):
            mc = mclasses.get(cls)
            if not mc:
                continue
            found = {}
            for e in mc.methods:
                if e["name"] == name and not e["qualified"] and e["outer"] and exists(e):
                    found[e["args"]] = (e, e)
            hosts = inline_hosts(cls, name) if not found else []
            if hosts:
                # only a wrapper in the same class: the name is emitted under that class, so a
                # wrapper living elsewhere would send the hook to a class that does not have it
                if len(hosts) == 1 and is_wrapper(hosts[0][1], name)                         and hosts[0][1]["cls"].orig == mclasses[cls].orig and exists(hosts[0][1]):
                    inlinee, host = hosts[0]
                    found[inlinee["args"]] = (inlinee, host)
                    report["inlined into a synthetic wrapper (hooked there instead)"].append(
                        f"{simple_name(cls)}#{precise(name, inlinee['args'])} -> {host['obf']}{method_descriptor(host, mclasses)}")
                else:
                    where = sorted({f"{h['cls'].orig}.{h['name']}" for _, h in hosts})
                    report["inlined by R8, not hookable"].append(f"{simple_name(cls)}#{name} -> {', '.join(where[:4]) or '?'}")
                    return []
            if found:
                return list(found.values())
        return []

    def lookup_field(orig, name):
        for cls in chain(orig):
            mc = mclasses.get(cls)
            if mc and name in mc.fields:
                fobf = mc.fields[name][1]
                dexcls = dex.get(mc.obf)
                if dexcls is not None and fobf in dexcls["fields"]:
                    return fobf
        return None

    for key, member in sorted(pairs):
        if key in MODULE_OWNED_KEYS:
            continue
        cands = index.get(key)
        if not cands:
            continue
        pref = [c for c in cands if c in referenced] or sorted(cands)

        method_hits = {c: h for c in pref for h in [lookup_methods(c, member)] if h}
        field_hits = {c: h for c in pref for h in [lookup_field(c, member)] if h}

        # ---- fields
        fobfs = set(field_hits.values())
        if len(fobfs) > 1:
            report["ambiguous class key (field)"].append(f"{key}#{member} -> {sorted(field_hits)}")
        elif fobfs:
            fobf = fobfs.pop()
            if fobf != member:
                out_fields[f"{key}#{member}"] = fobf

        if not method_hits:
            continue

        # ---- methods
        overloads = defaultdict(set)
        for hits in method_hits.values():
            for orig_e, real in hits:
                overloads[orig_e["args"]].add((real["obf"], method_descriptor(real, mclasses), id(orig_e) != id(real)))
        if any(len({o for o, _, _ in v}) > 1 for v in overloads.values()):
            report["ambiguous class key (method)"].append(f"{key}#{member} -> {sorted(method_hits)}")
            continue

        resolved = {a: next(iter(v)) for a, v in overloads.items()}   # args -> (obf, real desc, inlined)
        obfs = {o for o, _, _ in resolved.values()}
        renamed = any(o != member for o in obfs) or len(obfs) > 1

        codes = defaultdict(set)
        for a, (o, _, _) in resolved.items():
            codes[sig_code(a)].add(o)
            if renamed:
                out_methods[f"{key}#{precise(member, a)}"] = o
        for code, os_ in codes.items():
            if len(os_) == 1 and (renamed or next(iter(os_)) != member):
                out_methods[f"{key}#{member}{code}"] = next(iter(os_))

        plain = None
        if len(obfs) == 1:
            plain = next(iter(resolved))
            o = next(iter(obfs))
            if o != member:
                out_methods[f"{key}#{member}"] = o
        alias = aliases.get(f"{key}#{member}")
        if alias and f"{key}#{alias}" in out_methods:
            out_alias[f"{key}#{member}"] = alias
            plain = next((a for a in resolved if member + sig_code(a) == alias), plain)
        elif len(obfs) > 1:
            report["overloads renamed differently, call them with a signature"].append(
                f"{key}#{member}: " + ", ".join(f"{precise(member, a)}->{o}" for a, (o, _, _) in resolved.items()))

        # ---- changed parameter lists
        for a, (o, real_desc, inlined) in resolved.items():
            orig_params = [descriptor(t, mclasses) for t in a]
            real_params = split_params(real_desc)
            if orig_params == real_params:
                continue
            keys = [f"{key}#{precise(member, a)}"] + ([f"{key}#{member}"] if plain == a else [])
            for k in keys:
                out_params[k] = [java_name(d) for d in real_params]
            perm = None if inlined else stable_permutation(orig_params, real_params)
            if perm is not None:
                for k in keys:
                    out_order[k] = perm
            report["parameters changed by R8"].append(
                f"{key}#{precise(member, a)} -> {o}({', '.join(java_name(d) for d in real_params)})" + (f" order={perm}" if perm else ""))

    result = {
        "meta": {"versionCode": int(args.version_code), "versionName": args.version_name,
                 "generator": "tools/telegram-mapping/generate_mapping.py"},
        "classes": [{"o": o, "r": r} for o, r in sorted(out_classes.items())],
        "fields": [{"c": k.split("#", 1)[0], "o": k.split("#", 1)[1], "r": r} for k, r in sorted(out_fields.items())],
        "methods": [{"c": k.split("#", 1)[0], "o": k.split("#", 1)[1], "r": r} for k, r in sorted(out_methods.items())],
        "methodAlias": dict(sorted(out_alias.items())),
        "params": dict(sorted(out_params.items())),
        "argOrder": dict(sorted(out_order.items())),
    }
    os.makedirs(os.path.dirname(os.path.abspath(args.out)), exist_ok=True)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, indent=2, ensure_ascii=False)
        f.write("\n")

    print(f"classes={len(out_classes)} fields={len(out_fields)} methods={len(out_methods)} "
          f"aliases={len(out_alias)} params={len(out_params)} argOrder={len(out_order)} -> {args.out}")
    for section, items in report.items():
        print(f"\n[{section}] {len(set(items))}")
        for i in sorted(set(items)):
            print("  " + i)


if __name__ == "__main__":
    main()
