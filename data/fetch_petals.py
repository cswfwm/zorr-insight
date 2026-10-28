import json, urllib.request, urllib.parse, time, os
API="https://zorrpro.fandom.com/api.php"; UA={"User-Agent":"Mozilla/5.0 (research)"}
def api(p):
    q=urllib.parse.urlencode(p)
    for a in range(4):
        try:
            with urllib.request.urlopen(urllib.request.Request(API+"?"+q,headers=UA),timeout=30) as r:
                return json.loads(r.read().decode())
        except Exception:
            if a==3: raise
            time.sleep(2+a*2)
members=[]; cont={}
while True:
    p={"action":"query","list":"categorymembers","cmtitle":"Category:Petals","cmlimit":"500","format":"json"}
    p.update(cont); d=api(p)
    members+= [x['title'] for x in d['query']['categorymembers']]
    if 'continue' in d: cont=d['continue']
    else: break
json.dump(members,open('petal_titles.json','w'),ensure_ascii=False,indent=1)
print("petal pages:",len(members))
os.makedirs('petal_wikitext',exist_ok=True)
for i in range(0,len(members),20):
    ch=members[i:i+20]
    d=api({"action":"query","prop":"revisions","rvprop":"content","rvslots":"main","titles":"|".join(ch),"format":"json"})
    for pid,pg in d['query']['pages'].items():
        try: txt=pg['revisions'][0]['slots']['main']['*']
        except Exception: continue
        open('petal_wikitext/'+pg['title'].replace('/','_')+'.txt','w').write(txt)
    print("page",i+len(ch),flush=True); time.sleep(0.4)
