#!/usr/bin/env python3
"""Measure how far the Hermes and Jeeves trees have drifted apart.

Run before and after each migration step in docs/MODULARIZATION.md. Extracting
shared code should move files out of "diverged" and into a shared module; if a
step raises the diverged count instead, it moved the wrong thing.

    python docs/tools/survey-divergence.py \
        --jeeves . \
        --hermes "../Hermes Agent Android App"

Comparison ignores blank lines and trailing whitespace, so CRLF/LF differences
and reformatting do not read as divergence — the two trees are checked out on
the same machine with different line-ending histories.
"""

import argparse
import hashlib
import io
import os
from collections import Counter, defaultdict

SRC = os.path.join("app", "src", "main", "kotlin", "com", "hermes", "agent")


def scan(root):
    """Map each .kt file, relative to the package root, to (hash, size)."""
    found = {}
    for dirpath, _, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            rel = os.path.relpath(path, root).replace(os.sep, "/")
            raw = io.open(path, encoding="utf-8", errors="replace").read()
            body = "\n".join(
                line.rstrip()
                for line in raw.replace("\r\n", "\n").split("\n")
                if line.strip()
            )
            found[rel] = (hashlib.sha1(body.encode()).hexdigest(), len(raw))
    return found


def area(path):
    """Group by the first two path segments: 'data/llm', 'ui/chat', 'util'."""
    parts = path.split("/")
    return "/".join(parts[:2]) if len(parts) > 2 else parts[0]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--jeeves", default=".")
    ap.add_argument("--hermes", required=True)
    ap.add_argument("--detail", action="store_true", help="list every diverged file")
    args = ap.parse_args()

    j = scan(os.path.join(args.jeeves, SRC))
    h = scan(os.path.join(args.hermes, SRC))
    if not j or not h:
        raise SystemExit("no sources found — check --jeeves / --hermes paths")

    shared = set(j) & set(h)
    same = sorted(f for f in shared if j[f][0] == h[f][0])
    diff = sorted(f for f in shared if j[f][0] != h[f][0])
    only_j = sorted(set(j) - set(h))
    only_h = sorted(set(h) - set(j))

    print("Jeeves .kt : %d" % len(j))
    print("Hermes .kt : %d" % len(h))
    print("shared     : %d  (identical %d / diverged %d)" % (len(shared), len(same), len(diff)))
    print("Jeeves-only: %d" % len(only_j))
    print("Hermes-only: %d" % len(only_h))

    # The headline number: of the code both apps have, how much still matches.
    if shared:
        print("\nshared-code agreement: %.1f%%" % (100.0 * len(same) / len(shared)))

    counts = defaultdict(lambda: [0, 0])
    for f in same:
        counts[area(f)][0] += 1
    for f in diff:
        counts[area(f)][1] += 1

    print("\n%-26s %6s %6s  %s" % ("AREA", "same", "diff", "verdict"))
    for name in sorted(counts, key=lambda a: -(counts[a][0] + counts[a][1])):
        s, d = counts[name]
        if d == 0:
            verdict = "identical - extract first"
        elif s >= 2 * d:
            verdict = "mostly shared"
        elif s >= d:
            verdict = "mixed"
        else:
            verdict = "mostly diverged - leave per-app"
        print("%-26s %6d %6d  %s" % (name, s, d, verdict))

    if args.detail and diff:
        print("\nDiverged files, largest first:")
        for f in sorted(diff, key=lambda x: -j[x][1]):
            print("   %-58s %5dKB" % (f, j[f][1] // 1024))

    if only_h:
        print("\nHermes-only (would be lost if Jeeves is taken as the source of truth):")
        for f in only_h:
            print("   %s" % f)


if __name__ == "__main__":
    main()
