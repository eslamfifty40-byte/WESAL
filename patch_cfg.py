import json, os
p = "capacitor.config.json"
if os.path.exists(p):
    c = json.load(open(p, encoding="utf-8"))
    pl = c.setdefault("plugins", {})
    pl["SystemBars"] = {"style": "DARK"}
    pl["CapacitorHttp"] = {"enabled": True}
    json.dump(c, open(p, "w", encoding="utf-8"), ensure_ascii=False, indent=2)
