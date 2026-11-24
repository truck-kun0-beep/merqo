import json, re, sys

path = r"G:\Merqo\logs\phase2-brief-readable.txt"
raw = open(path, encoding="utf-8", errors="replace").read()

# The file is a JSON-ish dump of chat messages. Pull out the long "user" message
# that holds the Phase 2 brief, then un-wrap it.
try:
    data = json.loads(raw)
except Exception:
    data = None

def walk(node):
    if isinstance(node, dict):
        for k, v in node.items():
            yield from walk(v)
    elif isinstance(node, list):
        for v in node:
            yield from walk(v)
    elif isinstance(node, str):
        yield node

if data is not None:
    strings = list(walk(data))
else:
    strings = [raw]

best = ""
for s in strings:
    if "Phase 2" in s and len(s) > len(best):
        best = s

print("longest brief candidate length:", len(best))

# Undo the hard wrapping: the dump wrapped lines and inserted spaces.
out = best.replace("\\n", "\n")
out = re.sub(r"[ \t]{2,}", " ", out)
out = re.sub(r"\n ", "\n", out)

target = r"G:\Merqo\logs\phase2-brief-clean.txt"
with open(target, "w", encoding="utf-8") as f:
    f.write(out)
print("written:", target, len(out))
