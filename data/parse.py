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
    s = str(s).replace(',', '').replace('%', '').strip()
    try: return float(s)
    except: return None

def tables(w):
    return re.findall(r'\{\| class="wikitable".*?\n\|\}', w, re.S)

def hdr(tab):
    m = re.search(r'^! (.+)$', tab, re.M)
    return m.group(1) if m else ''

def title(tab):
    m = re.search(r'^\|\s*\+\s*(.+)$', tab, re.M)
    return clean(m.group(1)) if m else None

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

def rar_of(cell):
    m = re.search(r'\{\{Rarity\|(\w+)\}\}', cell, re.I)
    return m.group(1).lower() if m else None

def parse(w):
    base, drops, attrs = [], [], {}
    for tab in tables(w):
        h = hdr(tab)
        if h.startswith('Mob rarity !! Health'):
            cols = [clean(c).lower() for c in h.split('!!')[1:]]
            for cells in rows_of(tab):
                r = rar_of(cells[0])
                if not r: continue
                base.append(dict(zip(cols, [num(clean(c)) for c in cells[1:]]), rarity=r))
        elif h.startswith('Mob rarity !!') and 'drop' in h:
            cols = [c.lower() for c in re.findall(r'\{\{Rarity\|(\w+)\}\}[^\n!]*?drop', h)]
            petal = title(tab)
            rows = []
            for cells in rows_of(tab):
                r = rar_of(cells[0])
                if not r: continue
                vals = []
                for c in cells[1:]:
                    c2 = clean(c)
                    if c2 in ('—', '-', '', 'N/A'): vals.append(None); continue
                    mm = re.match(r'([\d.]+)\s*%\s*(?:×\s*(\d+))?', c2)
                    vals.append({"chance": float(mm.group(1)),
                                 "qty": int(mm.group(2)) if mm.group(2) else 1} if mm else None)
                rows.append({"mob_rarity": r, "drops": vals})
            drops.append({"petal": petal, "rarity_columns": cols, "rows": rows})
        elif h.startswith('Attribute !!'):
            cols = [c.lower() for c in re.findall(r'\{\{Rarity\|(\w+)\}\}', h)]
            for cells in rows_of(tab):
                key = clean(cells[0]).lower()
                attrs[key] = dict(zip(cols, [clean(c) for c in cells[1:]]))
    return base, drops, attrs

mobs = []
for fn in sorted(glob.glob('wikitext/*.txt')):
    w = open(fn).read()
    base, drops, attrs = parse(w)
    mobs.append({
        "name": os.path.basename(fn)[:-4],
        "biomes": sorted(set(clean(b) for b in re.findall(r'\[\[([A-Z][A-Za-z ]+?) \(Biome\)\|', w))),
        "base_stats": {b['rarity']: {k: v for k, v in b.items() if k != 'rarity'} for b in base},
        "attributes": attrs,
        "drops": drops,
    })

pairs = sum(len(m['drops']) for m in mobs)
cells = sum(1 for m in mobs for d in m['drops'] for r in d['rows'] for v in r['drops'] if v)
print("mobs", len(mobs), "| drop tables", pairs, "| prob cells", cells)
json.dump(mobs, open('mobs_raw.json','w'), ensure_ascii=False, indent=1)
bee = next(m for m in mobs if m['name']=='Bee')
print(json.dumps({"name":bee['name'],"biomes":bee['biomes'],
                  "common":bee['base_stats'].get('common'),
                  "drops":[d['petal'] for d in bee['drops']]}, ensure_ascii=False))
print(json.dumps(bee['drops'][0], ensure_ascii=False)[:300])
