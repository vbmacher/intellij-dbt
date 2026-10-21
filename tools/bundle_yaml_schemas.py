#!/usr/bin/env python3
"""Bundle offline YAML schema profiles without cross-file references (stdlib only)."""
import argparse
import copy
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "tools/schema-sources"
OUTPUT = ROOT / "src/main/resources/schemas"


def bundle(source):
    documents = {path.name: json.loads(path.read_text()) for path in SOURCES.glob("*.json")}
    documents["dbt-common.json"] = json.loads((OUTPUT / "dbt-common.json").read_text())

    def resolve(value, document, chain=()):
        if isinstance(value, list):
            return [resolve(item, document, chain) for item in value]
        if not isinstance(value, dict):
            return value
        reference = value.get("$ref")
        if reference:
            filename, _, pointer = reference.partition("#")
            filename = filename or document
            if filename == "dbt-common.json":
                return {"$ref": "#" + pointer}
            identity = filename + "#" + pointer
            if identity in chain:
                raise ValueError("Cyclic profile reference: " + identity)
            target = documents[filename]
            for part in pointer.lstrip("/").split("/") if pointer else ():
                target = target[part.replace("~1", "/").replace("~0", "~")]
            # Draft-07 ignores siblings of $ref; follow that same rule.
            return resolve(copy.deepcopy(target), filename, chain + (identity,))
        return {key: resolve(item, document, chain) for key, item in value.items()}

    result = resolve(documents[source.name], source.name)
    result["title"] = documents[source.name]["title"]
    result["definitions"] = copy.deepcopy(documents["dbt-common.json"]["definitions"])
    # Remove unsupported keys from native completion while keeping authored keys
    # invalid. allOf overlays validate them but mask other nested completions.
    before_units = source.stem in {"dbt-properties-precontracts", "dbt-properties-legacy",
                                   "dbt-properties-semantic", "dbt-properties-queries"}
    unsupported = {"data_tests"} if before_units else set()
    if source.stem == "dbt-properties-precontracts":
        unsupported |= {"contract", "constraints"}

    def gate(value):
        if isinstance(value, list):
            for item in value:
                gate(item)
        elif isinstance(value, dict):
            properties = value.get("properties", {})
            for key in sorted(unsupported & properties.keys()):
                del properties[key]
                value.setdefault("patternProperties", {})["^" + key + "$"] = False
            for item in value.values():
                gate(item)

    gate(result)
    return json.dumps(result, indent=2) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Fail when generated profiles need updating")
    args = parser.parse_args()
    stale = []
    for source in sorted(SOURCES.glob("dbt-properties*.json")):
        target = OUTPUT / source.name
        content = bundle(source)
        if args.check:
            if not target.exists() or target.read_text() != content:
                stale.append(target.relative_to(ROOT).as_posix())
        else:
            target.write_text(content)
    if stale:
        raise SystemExit("Regenerate YAML schemas: python3 tools/bundle_yaml_schemas.py\n" + "\n".join(stale))


if __name__ == "__main__":
    main()
