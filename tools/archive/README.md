# tools/archive — frozen one-off scripts

These scripts are kept for provenance, not for use. Every file here either

* patched the working tree in place once (round 4–9 `patch_*.js`, `fix_*.js`,
  `install_old_gate.js`, `add_terminal_lang.js`), or
* generated one round's art/QA artefacts and was superseded by the committed
  resources (`build_gate_r9_v*.js`, `gen_r8_textures.ps1`, `gen_r9_*.ps1`,
  `gen_dualgate_*`, `r9_build_stamp_print.js`, `qa_r9_*`,
  `_check_spec_vs_bbmodel.js`).

They are **not** part of the build or the resource pipeline, and their internal
paths are relative to `tools/`, so a script here that calls another (for example
`_check_spec_vs_bbmodel.js` → `tools/r9_build_stamp_print.js`) no longer resolves
from this folder. Restore it to `tools/` first if you really need to re-run it.

## Active tooling (stays in `tools/`)

| script | purpose |
| --- | --- |
| `generate.ps1` | idempotent generator driver (`-Only devices,items,recipes`, `-Root`) |
| `generate_assets.ps1` | device/entity textures, block models, blockstates, item models, loot, tags, icons, logo (`-NoDocuments` for the split) |
| `generate_item_textures.ps1` | document card textures + ink audit (`-AuditOnly`) |
| `generate_recipes.ps1` | `data/veriborder/recipes` |
| `check_generators.ps1` | scratch-tree rerun gate: idempotency + drift against committed resources |
| `check_lang.js` | `en_us` / `zh_cn` key, value and placeholder parity |
| `run_server.ps1` | dedicated-server launcher and `-Smoke` acceptance run |
| `preview_models.ps1`, `preview_models.js` | model preview sheets |
| `bbmodel_to_mc.js`, `build_r9_issuers.js`, `validate_bbmodels.js` | round-9 bespoke device models from the `.bbmodel` sources in `assets/` |
| `lib_device_textures.ps1`, `lib_device_models.ps1` | drawing/model library used by `generate_assets.ps1` |
| `make_label.ps1`, `deflicker.js`, `scan_coplanar.js`, `check_front_ratio.js`, `embed_textures.js` | reusable diagnostics/utilities |

## Why `check_generators.ps1` warns about the device assets

The committed device models and textures were replaced in round 9 by the bespoke
`.bbmodel`/painterly pipeline (`build_r9_issuers.js` and the archived
`gen_r9_style.ps1` / `gen_r9_accents.ps1` / `gen_dualgate_textures.ps1`), while
`generate_assets.ps1` still renders the earlier procedural set. Re-running the
procedural generator therefore reproduces the *current* outputs for recipes and
document cards (hard-failed on drift) but deliberately differs from the round-9
device art. The gate reports that difference as a warning; `-Strict` makes it
fatal, which is the setting to use once the round-9 pipeline is folded back into
the generator.
