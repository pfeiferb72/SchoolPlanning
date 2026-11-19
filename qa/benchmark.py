"""Synthetic two-week benchmark against a fresh, private demo instance (stdlib only)."""
import datetime as dt
import json
import os
import statistics
import time
import urllib.parse
import urllib.request
from http.cookiejar import CookieJar

base = os.environ.get("APP_URL", "http://127.0.0.1:8080")
http = urllib.request.build_opener(urllib.request.ProxyHandler({}), urllib.request.HTTPCookieProcessor(CookieJar()))
token = ""


def call(path, body=None, method=None):
    request = urllib.request.Request(base + path, method=method,
        headers={"Content-Type": "application/json", "X-CSRF-TOKEN": token},
        data=None if body is None else json.dumps(body).encode())
    with http.open(request, timeout=45) as response:
        raw = response.read()
        return json.loads(raw) if raw else None


token = call("/session")["csrf"]
http.open(urllib.request.Request(base + "/login", data=urllib.parse.urlencode(
    {"username": "plan", "password": "schulplan-demo"}).encode(), headers={"X-CSRF-TOKEN": token})).read()
token = call("/session")["csrf"]
root = "/api/v1/schools/demo"
pid = call(root + "/scenarios", {"name": "Lastprofil " + str(time.time_ns())})["id"]
path = root + "/scenarios/" + pid
c = call(path)["scenario"]["plan"]["catalog"]
c["slots"] = [{"start": f"{8+i:02}:00", "end": f"{8+i:02}:45"} for i in range(10)]
c["subjects"] = [{"id": "M", "name": "Mathematik"}, {"id": "D", "name": "Deutsch"}]
for i in range(40):
    gid, rid = f"K{i}", f"R{i}"
    c["groups"].append({"id": gid, "name": gid, "size": 22, "home": rid, "cohorts": [gid]})
    c["rooms"].append({"id": rid, "name": rid, "capacity": 25, "equipment": [gid], "blocked": []})
    for subject in ("M", "D"):
        teacher = subject + str(i)
        c["teachers"].append({"id": teacher, "name": teacher, "subjects": [subject], "load": 25, "daily": 10, "blocked": [], "wishes": []})
        for week in ("A", "B"):
            c["courses"].append({"id": teacher+week, "subject": subject, "groups": [gid], "teachers": [teacher],
                "hours": 25, "block": 1, "week": week, "equipment": [gid], "resources": []})
call(path + "/catalog", {"revision": 0, "catalog": c}, "PUT")
start = time.monotonic()
job = call(path + "/solver", {"revision": 1, "seconds": int(os.environ.get("BENCH_SECONDS","30")), "empty": True})
while job["status"] in ("WARTET", "LÄUFT"):
    time.sleep(.3)
    job = call(root + "/jobs/" + job["id"])
elapsed = time.monotonic() - start
assert job["status"] == "FERTIG", job
times = []
for _ in range(20):
    start = time.monotonic()
    result = call(path)
    times.append(1000*(time.monotonic()-start))
metrics = result["metrics"]
print(json.dumps({"classes":40,"teachers":80,"units":metrics["total"],"placed":metrics["placed"],
    "score":metrics["score"],"solver_elapsed_s":round(elapsed,2),"read_p95_ms":round(sorted(times)[18],1),
    "full":not metrics["outstanding"]},indent=2))
