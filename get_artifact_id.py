import json
runs = json.load(open(r'c:\Users\wang\Desktop\万物互联\runs.json'))['workflow_runs']
for r in runs:
    msg = r['head_commit']['message'][:60].replace('\n', ' ')
    print(f'#{r["run_number"]} {r["conclusion"]} {msg}')
