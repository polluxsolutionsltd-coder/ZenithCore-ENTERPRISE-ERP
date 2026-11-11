# ZenithCore look and feel for iDempiere

Fragment bundle (`ke.co.pollux.zenithcore.theme`, host `org.adempiere.ui.zk`) that adds the **zenithcore** web UI theme.

| Where | What changes |
| --- | --- |
| Login page | ZenithCore logo; navy-to-cyan background; side panel "ZenithCore Enterprise ERP, by Pollux Solutions Ltd" linking to www.pollux.co.ke |
| Desktop header | ZenithCore logo, white background, cyan rule |
| Buttons, tabs, grids, menus | Brand colours: navy `#00387F`, blue `#0088C0`, cyan `#00B8F0`, tint `#E3F4FB` |
| Browser tab | Title "ZenithCore Enterprise ERP" and the globe icon |

Screenshots: [login](../docs/images/zenithcore-login.png), [desktop](../docs/images/zenithcore-desktop.png).

## Files

- `web/theme/zenithcore/` is derived from iDempiere 13's `iceblue_c` theme (release-13, commit da0730a) with `iceblue_c` renamed to `zenithcore`. It stays under iDempiere's GPLv2 licence. The ZK widget skin in `preference.zul` deliberately stays `iceblue_c`.
- ZenithCore changes are confined to:
  - `images/login-logo.png`, `images/header-logo.png`, `images/icon.png`, `images/icon-180.png`
  - `css/fragment/custom.css.dsp` (brand colours, as CSS variable overrides)
  - `zul/login/vendor-logo.zul` (login side panel)
  - `preference.zul` (comment on the ZK skin)
- `brand/` holds the original logo supplied by Pollux and the cleaned transparent version used to produce the images. The logos are Pollux Solutions Ltd's and are not covered by the GPL.

## Install

1. Build: `mvn -B verify -Didempiere.home=/path/to/idempiere-server`
2. Copy `target/ke.co.pollux.zenithcore.theme-0.1.0-SNAPSHOT.jar` to `plugins/ke.co.pollux.zenithcore.theme_<Bundle-Version>.jar` and add it to `bundles.info` with start level 4 and auto-start `false` (it is a fragment).
3. Clear `configuration/org.eclipse.osgi`, restart.
4. Apply `migration/postgresql/202611092100_ZC-0003_LookAndFeel.sql` (sets `ZK_THEME=zenithcore`, the browser title and a white header). Safe to re-run.

To update the brand later, replace the images and the colours in `custom.css.dsp`; nothing else needs to change.
