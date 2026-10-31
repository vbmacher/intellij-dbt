"""Provenance for one explicit dbt Core compilation; never changes rendered text.

Installed only in the configured CLI's child process. Unknown string operations
lose their inner provenance; their enclosing output expression remains mapped.
"""
import itertools
import json
import os
from pathlib import Path


class Traced(str):
    def __new__(cls, text, spans=()):
        value = super().__new__(cls, text)
        value.spans = tuple(spans)
        return value

    def __str__(self):
        return self


def concatenate(values):
    text, spans, offset = [], [], 0
    for value in values:
        part = str(value)
        text.append(part)
        if isinstance(value, Traced):
            spans.extend((start + offset, end + offset, origins)
                         for start, end, origins in value.spans)
        offset += len(part)
    return Traced("".join(text), spans)


def install(request_path):
    request = json.loads(Path(request_path).read_text(encoding="utf-8"))
    report = Path(request["report"])

    def publish(value):
        value["token"] = request["token"]
        temporary = report.with_suffix(".tmp")
        temporary.write_text(json.dumps(value, ensure_ascii=False), encoding="utf-8")
        temporary.replace(report)

    try:
        import dbt_common.clients.jinja as jinja
        from dbt.compilation import Compiler
        from importlib.metadata import version
        from jinja2 import nodes
        from jinja2.visitor import NodeTransformer

        core = version("dbt-core").split(".")
        if int(core[0]) != 1 or int(core[1]) < 8:
            raise RuntimeError("Source mapping requires the Python dbt Core 1.8+ CLI.")

        sources = {(s["package"], s["relative"]): s for s in request["sources"]}
        counter = itertools.count()
        original_environment = jinja.get_environment
        original_parse = jinja.MacroFuzzEnvironment._parse
        original_native = jinja.quoted_native_concat
        original_compile = Compiler.compile_node

        def origin_for(node, source):
            source_file = sources.get((getattr(node, "package_name", ""),
                                       getattr(node, "original_file_path", "")))
            if source_file is None:
                return None
            full = source_file["text"]
            # Macro SQL is a block extracted by dbt, not necessarily the whole file.
            start = full.find(source)
            if start < 0 or full.find(source, start + 1) >= 0:
                return None
            return source_file["path"], full[:start].count("\n")

        def trace(value, path, line, literal, native):
            if not path:
                return value
            if native and (not isinstance(value, str) or isinstance(value, jinja.NativeMarker)):
                return value
            instance = next(counter)
            parent = {"path": path, "line": line, "instance": instance}
            if isinstance(value, Traced):
                spans, offset = [], 0
                for start, end, stack in value.spans:
                    if start > offset:
                        spans.append((offset, start, (parent,)))
                    spans.append((start, end, stack + (parent,)))
                    offset = end
                if offset < len(value):
                    spans.append((offset, len(value), (parent,)))
                return Traced(value, spans)
            text = str(value)
            if not literal:
                return Traced(text, ((0, len(text), (parent,)),))
            spans, offset = [], 0
            lines = text.split("\n")
            for index, part in enumerate(lines):
                if index < len(lines) - 1:
                    part += "\n"
                spans.append((offset, offset + len(part),
                              ({"path": path, "line": line + index, "instance": instance},)))
                offset += len(part)
            return Traced(text, spans)

        class Outputs(NodeTransformer):
            def __init__(self, origin, native):
                self.path, self.line = origin
                self.native = native

            def visit_Output(self, node, *args, **kwargs):
                node.nodes = [nodes.Call(
                    nodes.Name("dbt_ide_trace_output", "load"),
                    [child, nodes.Const(self.path), nodes.Const(self.line + child.lineno - 1),
                     nodes.Const(isinstance(child, nodes.TemplateData)), nodes.Const(self.native)],
                    [], None, None, lineno=child.lineno) for child in node.nodes]
                return node

        def parse(environment, source, name, filename):
            tree = original_parse(environment, source, name, filename)
            origin = origin_for(getattr(environment, "dbt_ide_node", None), source)
            return Outputs(origin, environment.dbt_ide_native).visit(tree) if origin else tree

        def environment(node=None, capture_macros=False, native=False):
            result = original_environment(node, capture_macros, native=native)
            result.dbt_ide_node = node
            result.dbt_ide_native = native
            result.globals["dbt_ide_trace_output"] = trace
            return result

        def native_concatenate(values):
            values = list(values)
            result = original_native(iter(values))
            combined = concatenate(values)
            return combined if isinstance(result, str) and result == combined else result

        def cached_template(cache, node):
            # dbt normally caches by macro text alone. Identical blocks in two files
            # must retain separate identities when carrying source provenance.
            key = (node.package_name, node.original_file_path, node.macro_sql)
            if key not in cache.file_cache:
                cache.file_cache[key] = jinja.get_template(node.macro_sql, {}, node)
            return cache.file_cache[key]

        def compile_node(compiler, node, *args, **kwargs):
            # Macro-launched subprocesses must not publish into this compilation's report.
            os.environ.pop("DBT_IDE_CTE_TRACE", None)
            hook = str(Path(__file__).resolve().parent)
            os.environ["PYTHONPATH"] = os.pathsep.join(
                path for path in os.environ.get("PYTHONPATH", "").split(os.pathsep)
                if str(Path(path).resolve()) != hook)
            result = original_compile(compiler, node, *args, **kwargs)
            source_file = sources.get((result.package_name, result.original_file_path))
            if source_file and source_file["path"] == request["model"]:
                sql = result.compiled_code
                if not isinstance(sql, str) or len(sql) > 1000000:
                    raise RuntimeError("Compiled SQL is unavailable or exceeds 1,000,000 characters.")
                spans = sql.spans if isinstance(sql, Traced) else ()
                if not spans and sql == source_file["text"]:
                    spans = trace(sql, source_file["path"], 0, True, False).spans
                # IntelliJ offsets use UTF-16 code units; Python offsets count Unicode characters.
                offsets, total = [0], 0
                for char in sql:
                    total += 2 if ord(char) > 0xFFFF else 1
                    offsets.append(total)
                if len(spans) > 200000:
                    raise RuntimeError("Compilation exceeded the source mapping limit.")
                publish({"sql": str(sql), "spans": [
                    {"start": offsets[a], "end": offsets[b], "origins": stack}
                    for a, b, stack in spans if b > a]})
            return result

        jinja.get_environment = environment
        jinja.MacroFuzzEnvironment._parse = parse
        jinja.MacroFuzzEnvironment.concat = staticmethod(concatenate)
        jinja.quoted_native_concat = native_concatenate
        jinja.TemplateCache.get_node_template = cached_template
        Compiler.compile_node = compile_node
    except Exception as error:
        publish({"error": "Source mapping is unavailable. Use a Python dbt Core 1.8+ CLI that preserves PYTHONPATH. " + str(error)})
