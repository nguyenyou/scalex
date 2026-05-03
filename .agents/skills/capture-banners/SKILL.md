---
name: capture-banners
description: Re-render Scalex banner and OG image PNGs from their HTML source files using Chrome DevTools MCP. Use this skill whenever banner HTML files are edited (slogan changes, color tweaks, layout changes) and the corresponding PNGs need to be regenerated. Triggers on "capture banners", "update banner images", "re-render banners", "regenerate PNGs", or after any edit to files in site/*-banner*.html or site/og-banner*.html.
---

# Capture Banners

The Scalex project has 4 banner images that are rendered from HTML source files. When the HTML is edited, the PNGs must be re-captured to stay in sync.

## Banner inventory

| HTML source | PNG output | Purpose |
|---|---|---|
| `site/readme-banner-dark.html` | `site/readme-banner-dark.png` | GitHub README banner (dark mode) |
| `site/readme-banner-light.html` | `site/readme-banner-light.png` | GitHub README banner (light mode) |
| `site/og-banner.html` | `site/og-banner.png` | OpenGraph social preview (dark) |
| `site/og-banner-light.html` | `site/og-banner-light.png` | OpenGraph social preview (light) |

## Required viewport size

All banners are designed at **1200 x 630** pixels. The HTML `<body>` is hardcoded to this size. The Chrome DevTools viewport must match exactly, otherwise the screenshot will crop or include extra whitespace.

## Capture procedure

Use Chrome DevTools MCP tools. For each banner:

1. **Open or navigate** to the HTML file using a `file://` URL:
   ```
   file:///absolute/path/to/scalex/site/readme-banner-dark.html
   ```

2. **Resize the viewport** to 1200x630:
   ```
   mcp__chrome-devtools__resize_page(width=1200, height=630)
   ```

3. **Take a screenshot** and save directly to the PNG path:
   ```
   mcp__chrome-devtools__take_screenshot(
     filePath="site/readme-banner-dark.png",
     format="png"
   )
   ```

4. **Repeat** for the remaining 3 banners. You can reuse the same tab by navigating to the next HTML file.

## Notes

- The Kestrel mascot image (`Kestrel.png`) is referenced by relative path in the HTML, so the file:// URL must point to the `site/` directory for it to render.
- After capturing, the PNGs are ready to commit — no post-processing needed.
- If the images look wrong (missing mascot, broken layout), check that the HTML references are correct relative to the `site/` directory.
