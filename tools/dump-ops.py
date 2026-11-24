import json

path = r"G:\Merqo\logs\state-968bb657.json"
d = json.load(open(path, encoding="utf-8"))

ops = d["timeline"]["operations"]
print("operations:", len(ops))

for i, o in enumerate(ops):
    t = o.get("type")
    fs = o.get("uri", {}).get("fsPath", "")
    epoch = o.get("epoch")
    print(f"[{i}] epoch={epoch} type={t} file={fs}")
