import json, re, glob, os

RAR = ['common','unusual','rare','epic','legendary','mythic','ultra','super','omega','unique']
data = json.load(open('mobs_raw.json'))

def norm(s):
    return re.sub(r'[^a-z0-9]', '', s.lower())

# 由 花瓣<-怪物 的关系反转成 怪物<-花瓣
mob_index = {}
for m in data:
    key = norm(m['name'])
    mob_index[key] = {
        "name": m['name'],
        "biomes": m['biomes'],
        "base_stats": m['base_stats'],
        "attributes": m['attributes'],
        "drops": {},
    }

for m in data:
    key = norm(m['name'])
    for d in m['drops']:
        petal = d['petal']
        if not petal: continue
        pkey = norm(petal)
        for row in d['rows']:
            mr = row['mob_rarity']
            for i, v in enumerate(row['drops']):
                if not v: continue
                if i >= len(d['rarity_columns']): continue
                pr = d['rarity_columns'][i]
                mob_index[key]["drops"].setdefault(pkey, {
                    "petal": petal, "by_mob_rarity": {}})["by_mob_rarity"][mr] = {
                        "petal_rarity": pr, "chance": v['chance'], "qty": v['qty']}

# 反向索引：花瓣 -> 掉落它的怪物
petal_index = {}
for mk, mob in mob_index.items():
    for pk, d in mob['drops'].items():
        petal_index.setdefault(pk, {"petal": d['petal'], "dropped_by": {}})
        for mr, info in d['by_mob_rarity'].items():
            petal_index[pk]["dropped_by"].setdefault(mk, {"mob": mob['name'], "rates": {}})
            petal_index[pk]["dropped_by"][mk]["rates"][info['petal_rarity']] = {
                "mob_rarity": mr, "chance": info['chance'], "qty": info['qty']}

meta = {
    "source": "https://zorrpro.fandom.com",
    "generated": "2026-10-28",
    "notes": "掉落概率为游戏内图鉴显示的数值，同格不同类型不可相加；— 表示快照未显示概率，并非确认的 0%。",
    "rarity_order": RAR,
    "counts": {
        "mobs": len(mob_index),
        "petals": len(petal_index),
        "drop_pair": sum(len(m['drops']) for m in mob_index.values()),
    },
}

out = {"meta": meta, "mobs": mob_index, "petals": petal_index}
json.dump(out, open('zorr-wiki-drops.json','w'), ensure_ascii=False, indent=1)
print(json.dumps(meta['counts'], ensure_ascii=False))
bee = mob_index['bee']
print('Bee drops:', json.dumps({k: v['petal'] for k, v in bee['drops'].items()}, ensure_ascii=False))
print('stinger dropped_by:', len(petal_index['stinger']['dropped_by']))
