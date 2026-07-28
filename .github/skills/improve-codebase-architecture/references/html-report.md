# HTML report format

Render architecture review as one standalone HTML file in OS temporary storage.
Tailwind and Mermaid may load from CDN. Use Mermaid for graph-shaped dependency
and call-flow diagrams; use hand-built `<div>`/inline SVG for editorial visuals
such as mass diagrams and cross-sections. Do not make every diagram look alike.

```html
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <title>Architecture review — {{repository-name}}</title>
    <script src="https://cdn.tailwindcss.com"></script>
    <script type="module">
      import mermaid from "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.esm.min.mjs";
      mermaid.initialize({ startOnLoad: true, theme: "neutral", securityLevel: "loose" });
    </script>
    <style>
      .seam { stroke-dasharray: 4 4; }
      .leak { stroke: #dc2626; }
      .deep { background: linear-gradient(135deg, #0f172a, #1e293b); }
    </style>
  </head>
  <body>
    <header><!-- repository name, date, and legend --></header>
    <main>
      <!-- one candidate <article> per deepening opportunity -->
    </main>
  </body>
</html>
```

The header contains repository name, date, and a compact legend: solid box is a
module, dashed line a seam, red arrow a leak, thick dark box a deep module.

Each candidate is one `<article>` with title, recommendation badge (`Strong`,
`Worth exploring`, `Speculative`), dependency category, monospaced file list,
before/after diagram, one-sentence problem/solution, benefits of six words or
fewer, and optional ADR callout. Finish with one top recommendation. Avoid
introductory prose: diagrams carry the explanation.

Use Mermaid flowcharts/sequence diagrams for dependency/call clutter, hand-built
boxes/arrows when layout needs a visibly deep after-module, horizontal bands for
layered shallowness, two rectangles for interface-vs-implementation mass, and
collapsed call graphs for hidden internal calls.

Keep an editorial rather than dashboard style: generous whitespace, sparse
emerald/indigo accent, red only for leaks, yellow for warnings, diagrams around
320px high, and no application code or interactivity beyond Mermaid rendering.
Use the exact architecture vocabulary: **module, interface, implementation,
depth, deep, shallow, seam, adapter, leverage, locality**. Do not substitute
component/service/unit, API/signature, boundary, or layer/wrapper when those
mean the defined terms. State specific benefits such as locality or leverage,
not vague claims like "cleaner code".
