# Bundled lineage renderer

These assets are shipped locally. The IDE never loads graph scripts from a CDN.
Exact npm versions and integrity identifiers are recorded in VERSIONS.txt.

| Asset | Package | License |
| --- | --- | --- |
| cytoscape.min.js | cytoscape 3.34.3 | MIT, cytoscape-LICENSE.txt |
| dagre.min.js | @dagrejs/dagre 3.1.1 | MIT, dagre-LICENSE.txt |
| cytoscape-dagre.min.js | cytoscape-dagre 4.0.1 | MIT, cytoscape-dagre-LICENSE.txt |

Dagre's browser build includes @dagrejs/graphlib 4.0.5 under the same Chris Pettitt
MIT copyright/license notice. Upstream repositories:

- https://github.com/cytoscape/cytoscape.js
- https://github.com/dagrejs/dagre
- https://github.com/dagrejs/graphlib
- https://github.com/cytoscape/cytoscape.js-dagre

Assets were extracted from the pinned packages' dist directories without modification.
Package headers may report a different internal build version; VERSIONS.txt records
the published npm package version.
