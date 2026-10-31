"""Activate tracing only for the explicit Debug compile process."""
import importlib.machinery
import importlib.util
import os
import sys

# Preserve a sitecustomize supplied by the configured Python environment.
directory = os.path.dirname(__file__)
search = [path for path in sys.path if os.path.abspath(path) != os.path.abspath(directory)]
previous = importlib.machinery.PathFinder.find_spec("sitecustomize", search)
if previous and previous.loader:
    module = importlib.util.module_from_spec(previous)
    previous.loader.exec_module(module)

request = os.environ.get("DBT_IDE_CTE_TRACE")
if request:
    from cte_trace import install
    install(request)
