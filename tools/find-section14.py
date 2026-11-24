import re
import sys

paths = [
    r"G:\Merqo\logs\phase2-brief-full.txt",
    r"G:\Merqo\logs\phase2-brief-readable.txt",
    r"G:\Merqo\logs\phase2-brief-extract.txt",
]

for path in paths:
    s = open(path, encoding="utf-8", errors="replace").read()
    hits = list(re.finditer(r"section 14|Hand-back report|8-section", s))
    print(path, "len", len(s), "hits", len(hits))
    for m in hits[:6]:
        print("  ", m.start(), repr(s[max(0, m.start() - 120) : m.start() + 200]))
