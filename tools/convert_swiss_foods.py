#!/usr/bin/env python3
"""Converts the Swiss Food Composition Database (Excel, naehrwertdaten.ch) into the app's generic food list.

Usage:
    python3 tools/convert_swiss_foods.py en=Swiss_food_composition_database.xlsx [de=Schweizer_Naehrwertdatenbank.xlsx ...] \
        > app/src/main/assets/generic_foods.json

Each language edition has the same layout and IDs; the first file given supplies the nutrients, every file
supplies the names (and synonyms) in its language. Only the "Generic foods" sheet (the first one) is used.
Requires openpyxl.
"""
import json
import sys

import openpyxl

HEADER_ROW = 3  # 1-based; rows 1-2 hold the title
# Columns (0-based) in every language edition.
ID, NAME, SYNONYMS, CATEGORY, DENSITY = 0, 3, 4, 5, 6
# Value columns of the nutrients the app logs, located by the English header of the first file.
NUTRIENTS = {
    "kcal": "Energy, kilocalories (kcal)",
    "fat": "Fat, total (g)",
    "saturatedFat": "Fatty acids, saturated (g)",
    "carbs": "Carbohydrates, available (g)",
    "sugar": "Sugars (g)",
    "fiber": "Dietary fibres (g)",
    "protein": "Protein (g)",
    "salt": "Salt (NaCl) (g)",
}


def rows(path):
    sheet = openpyxl.load_workbook(path, read_only=True).worksheets[0]
    all_rows = list(sheet.iter_rows(values_only=True))
    title = all_rows[0][0]
    header = all_rows[HEADER_ROW - 1]
    data = [r for r in all_rows[HEADER_ROW:] if isinstance(r[ID], (int, float))]
    return title, header, data


def number(value):
    """Numbers stay; "n.d." (not determined) and blanks become None."""
    return round(float(value), 3) if isinstance(value, (int, float)) else None


def text(value):
    return value.strip() if isinstance(value, str) and value.strip() else None


def main(args):
    editions = [a.split("=", 1) for a in args]
    if not editions or any(len(e) != 2 for e in editions):
        sys.exit(__doc__)
    first_lang, first_path = editions[0]
    title, header, data = rows(first_path)
    columns = {key: header.index(name) for key, name in NUTRIENTS.items()}
    foods = {}
    for r in data:
        food = {"id": int(r[ID]), "names": {first_lang: text(r[NAME])}}
        if text(r[SYNONYMS]):
            food["synonyms"] = {first_lang: text(r[SYNONYMS])}
        food["category"] = text(r[CATEGORY])
        if number(r[DENSITY]):
            food["density"] = number(r[DENSITY])
        food["per100g"] = {key: number(r[col]) for key, col in columns.items() if number(r[col]) is not None}
        foods[food["id"]] = food
    for lang, path in editions[1:]:
        for r in rows(path)[2]:
            food = foods.get(int(r[ID]))
            if food is None:
                continue
            if text(r[NAME]):
                food["names"][lang] = text(r[NAME])
            if text(r[SYNONYMS]):
                food.setdefault("synonyms", {})[lang] = text(r[SYNONYMS])
    out = {"source": title, "foods": sorted(foods.values(), key=lambda f: f["id"])}
    json.dump(out, sys.stdout, ensure_ascii=False, separators=(",", ":"))
    sys.stdout.write("\n")


if __name__ == "__main__":
    main(sys.argv[1:])
