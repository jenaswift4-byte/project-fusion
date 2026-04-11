import urllib.request, json

TOKEN = "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"
WORKFLOW_ID = "259400779"

data = json.dumps({"ref": "main"}).encode()
req = urllib.request.Request(
    f"https://api.github.com/repos/jenaswift4-byte/project-fusion/actions/workflows/{WORKFLOW_ID}/dispatches",
    data=data, method="POST"
)
req.add_header("Authorization", f"token {TOKEN}")
req.add_header("Content-Type", "application/json")
req.add_header("User-Agent", "ProjectFusion")
try:
    urllib.request.urlopen(req)
    print("Build triggered!")
except urllib.error.HTTPError as e:
    print(f"HTTP {e.code}: {e.read().decode()[:200]}")
except Exception as e:
    print(f"Error: {e}")
