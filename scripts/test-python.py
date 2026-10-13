#!/usr/bin/env python3
"""Run optional navigation fixtures with an installed Python Community plugin."""
import argparse
import json
import pathlib
import re
import subprocess

repo = pathlib.Path(__file__).resolve().parent.parent
version = re.search(r'val intellijVersion = "([^"]+)"', (repo / 'build.sbt').read_text()).group(1)
parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--sdk', type=pathlib.Path, default=pathlib.Path.home() / '.intellij-dbtPluginIC' / 'sdk' / version)
args = parser.parse_args()
roots = [args.sdk / p for p in ('custom-plugins/python-ce', 'plugins/toml', 'plugins/jcef-plugin', 'plugins/platform-testRunner-plugin')]
for root in roots:
    if not root.is_dir():
        parser.error(f'{root} is missing. Install Python Community Edition in the development IDE, or supply --sdk.')
plugin_paths = ':'.join(str(p) for p in [repo / 'target/plugin/intellij-dbt', *roots])
plugin_ids = 'com.github.vbmacher.intellij-dbt,org.jetbrains.plugins.yaml,com.intellij.modules.json,intellij.libraries.misc.plugin,intellij.structureView.plugin,PythonCore,org.toml.lang,com.intellij.modules.jcef,com.intellij.platform.testRunner'
commands = [
    'set Test / testOptions := Seq.empty',
    'set Test / unmanagedJars ++= Seq(' + ','.join(json.dumps(str(p)) for p in roots) + ').flatMap(p => (file(p) / "lib" ** "*.jar").get).map(Attributed.blank)',
    'set Test / javaOptions ++= Seq(' + ','.join(json.dumps(p) for p in ['-Didea.load.plugins.id=' + plugin_ids, '-Dplugin.path=' + plugin_paths]) + ')',
    'testOnly *DbtPythonNavigationTest',
]
raise SystemExit(subprocess.run(['sbt', '--batch', *commands], cwd=repo).returncode)
