import json, re

RAR = ['common','unusual','rare','epic','legendary','mythic','ultra','super','omega','unique']
mobs = json.load(open('zorr-wiki-drops.json'))
petals = json.load(open('petals_parsed.json'))

def norm(s): return re.sub(r'[^a-z0-9]', '', s.lower())

for pk, p in petals.items():
    if not p.get('stats'): continue
    common = p['stats'].get('common') or {}
    p['dmg_common'] = common.get('damage')
    p['hp_common'] = common.get('health')
    p['reload_common'] = common.get('primary reload (s)')
    p['count_common'] = common.get('count')

merged = {
    "meta": {
        "source": "https://zorrpro.fandom.com",
        "generated": "2026-10-28",
        "notes": "概率为游戏图鉴显示值；同一格不同类型不可相加；— 表示快照未显示，不等于 0%。",
        "rarity_order": RAR,
        "counts": {"mobs": len(mobs['mobs']), "petals": len(petals)},
    },
    "mobs": mobs['mobs'],
    "petals": petals,
}
json.dump(merged, open('zorr-wiki-drops.json', 'w'), ensure_ascii=False, indent=1)

# 校验
m = merged['mobs']; p = merged['petals']
print("怪物:", len(m), " 花瓣:", len(p))
print("有掉落:" , sum(1 for x in m.values() if x['drops']))
print("有属性:", sum(1 for x in m.values() if x['base_stats']))
print("花瓣有数值:", sum(1 for x in p.values() if x.get('dmg_common') is not None))
bee = m['bee']
print("\nBee 掉落细节:")
for petal, d in bee['drops'].items():
    c = d['by_mob_rarity'].get('common')
    print(f"  {d['petal']:<14} common怪掉落 {c['chance'] if c else '-'}%  稀有度={c['petal_rarity'] if c else '-'}")
