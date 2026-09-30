#!/usr/bin/env bash
set -euo pipefail
cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.."
pandoc MANUAL_REPLICACION_DESDE_CERO.md \
  --from=markdown+gfm_auto_identifiers --pdf-engine=xelatex \
  -V mainfont='DejaVu Sans' -V monofont='DejaVu Sans Mono' \
  -V geometry:margin=20mm -V fontsize=10pt \
  --include-in-header=docs/pdf_header.tex \
  -o app_dji/MANUAL_REPLICACION_DESDE_CERO.pdf
