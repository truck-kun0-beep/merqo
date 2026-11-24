import json, sys

path = r"G:\Merqo\logs\state-968bb657.json"
d = json.load(open(path, encoding="utf-8"))
t = d["timeline"]
print("epochCounter:", t.get("epochCounter"), "currentEpoch:", t.get("currentEpoch"))

ops = t.get("operations", [])
print("operations:", type(ops), len(ops))

cps = t.get("checkpoints", [])
print("checkpoints:", len(cps))
if cps:
    print("cp[0] keys:", list(cps[0].keys()) if isinstance(cps[0], dict) else type(cps[0]))
    print("cp[-1]:", json.dumps(cps[-1])[:1500])

if isinstance(ops, dict):
    print("op keys:", list(ops.keys()))
    for k, v in ops.items():
        print("  ", k, type(v), len(v) if isinstance(v, (list, dict, str)) else v)
elif isinstance(ops, list):
    for o in ops[-5:]:
        print(json.dumps(o)[:800])
        print("---")
