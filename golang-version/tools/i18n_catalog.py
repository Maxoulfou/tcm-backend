#!/usr/bin/env python3
"""Extract Java response/error literals and generate a checked Go i18n catalogue."""
import json
import re
from collections import Counter
from pathlib import Path

from openai import OpenAI

ROOT = Path(__file__).resolve().parents[2]
GO_ROOT = Path(__file__).resolve().parents[1]
SOURCES = [ROOT / path for path in (
    "ruoyi-hospital/src/main/java", "ruoyi-admin/src/main/java",
    "ruoyi-framework/src/main/java", "ruoyi-system/src/main/java",
)]
CALLS = ("ServiceException(", "AjaxResult.error(", "R.fail(", "MessageUtils.message(", "setMessage(")
LITERAL = re.compile(r'"((?:\\.|[^"\\])*)"')
CJK = re.compile(r"[\u3400-\u9fff]")
PLACEHOLDER = re.compile(r"%\d*\$?[sdif]|\{\d+\}|\$\{[^}]+\}|\\[nrt]")
MODEL = "gpt-5-mini"
BATCH_SIZE = 20
SCHEMA = {"type":"object","properties":{"translations":{"type":"array","items":{"type":"object","properties":{"source":{"type":"string"},"english":{"type":"string"}},"required":["source","english"],"additionalProperties":False}}},"required":["translations"],"additionalProperties":False}


def unescape_java(value):
    try:
        return json.loads('"' + value + '"')
    except json.JSONDecodeError:
        return value


def extract_sources():
    found = set()
    for root in SOURCES:
        if not root.exists():
            continue
        for path in root.rglob("*.java"):
            for line in path.read_text(errors="ignore").splitlines():
                if any(call in line for call in CALLS):
                    found.update(unescape_java(m.group(1)) for m in LITERAL.finditer(line) if CJK.search(m.group(1)))
    return sorted(found)


def translate_batch(client, items):
    prompt = "Translate each Chinese user-facing backend error/result message into concise natural English. If a message already contains English and Chinese, return one clean English message without redundant bilingual text. Preserve every variable placeholder, escape sequence and meaningful value exactly. Return one translation per input, in the same order."
    schema = {"type":"json_schema","json_schema":{"name":"message_translations","strict":True,"schema":SCHEMA}}
    response = client.chat.completions.create(model=MODEL, messages=[{"role":"system","content":prompt},{"role":"user","content":json.dumps(items,ensure_ascii=False)}], response_format=schema, max_completion_tokens=5000)
    result = json.loads(response.choices[0].message.content)["translations"]
    validate_batch(items,result)
    return result


def validate_batch(items, translations):
    if len(items) != len(translations):
        raise ValueError(f"Expected {len(items)} translations, got {len(translations)}")
    for original, translated in zip(items, translations):
        if translated["source"] != original or not translated["english"].strip():
            raise ValueError(f"Translation mismatch for {original!r}")
        if Counter(PLACEHOLDER.findall(original)) != Counter(PLACEHOLDER.findall(translated["english"])):
            raise ValueError(f"Placeholder changed for {original!r}")


def quote_go(value):
    return json.dumps(value, ensure_ascii=False)


def write_catalog(entries):
    lines = ["package i18n", "", "// Code generated from Java response/error strings; DO NOT EDIT.", "var sourceEnglish = map[string]string{"]
    lines.extend(f"\t{quote_go(item['source'])}: {quote_go(item['english'])}," for item in entries)
    lines.extend(["}", ""])
    (GO_ROOT / "ruoyi-common/i18n/source_messages.go").write_text("\n".join(lines), encoding="utf-8")


def main():
    sources = extract_sources()
    client = OpenAI()
    entries = []
    for start in range(0, len(sources), BATCH_SIZE):
        batch = sources[start:start + BATCH_SIZE]
        entries.extend(translate_batch(client, batch))
        print(f"Translated {len(entries)}/{len(sources)}", flush=True)
    write_catalog(entries)
    print(f"Wrote {len(entries)} source messages")


if __name__ == "__main__":
    main()
