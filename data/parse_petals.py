import json, os, re, glob

RAR = ['common','unusual','rare','epic','legendary','mythic','ultra','super','omega','unique']

def clean(s):
    s = s.strip()
    s = re.sub(r'\[\[([^\]|]*)\|([^\]]*)\]\]', r'\2', s)
    s = re.sub(r'\[\[([^\]]*)\]\]', r'\1', s)
    s = re.sub(r'\{\{Rarity\|([^}|]*)[^}]*\}\}', r'\1', s)
    s = re.sub(r"'{2,}", '', s)
    s = re.sub(r'<[^>]+>', '', s)
    return s.strip()

def num(s):
    if s is None: return None
    s = str(s).replace(',', '').strip()
    try: return float(s)
    except: return None

def rows_of(tab):
    out = []
    for line in tab.split('\n'):
        s = line.strip()
        if not s.startswith('|') or s.startswith('|+') or s.startswith('|}'):
            continue
        cells = [c.strip() for c in s[1:].split('||')]
        if len(cells) < 2: continue
        out.append(cells)
    return out

out = {}
for fn in sorted(glob.glob('petal_wikitext/*.txt')):
    w = open(fn).read()
    name = os.path.basename(fn)[:-4]
    rec = {"name": name, "stats": {}}
    for tab in re.findall(r'\{\| class="wikitable.*?\n\|\}', w, re.S):
        m = re.search(r'^! (.+)$', tab, re.M)
        if not m: continue
        h = m.group(1)
        if h.startswith('Rarity !! Health !! Damage !! Primary reload'):
            cols = [clean(c).lower() for c in h.split('!!')]
            for cells in rows_of(tab):
                rr = re.search(r'\{\{Rarity\|(\w+)\}\}', cells[0], re.I)
                if not rr: continue
                d = dict(zip(cols[1:], [num(clean(c)) for c in cells[1:]]))
                rec["stats"][rr.group(1).lower()] = d
        elif 'Listed source mobs' in h:
            for cells in rows_of(tab):
                if clean(cells[0]) == 'Common':
                    rec["source_mobs"] = [clean(x) for x in re.split(r'<br\s*/?>|,', cells[1]) if clean(x)]
    out[re.sub(r'[^a-z0-9]','',name.lower())] = rec

json.dump(out, open('petals_parsed.json','w'), ensure_ascii=False, indent=1)
withstats = sum(1 for v in out.values() if v['stats'])
print("petals", len(out), "| with stats", withstats)
s = out.get('stinger')
print(json.dumps({k:v for k,v in s.items() if k!='stats'}, ensure_ascii=False)[:300])
print(json.dumps(s['stats'].get('common'), ensure_ascii=False) if s and s['stats'] else 'none')
