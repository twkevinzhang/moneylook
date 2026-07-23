# Design QA — 全域明細與分析

## Evidence handling

- Reference images were supplied out of band and are intentionally not linked from this repository.
- Local implementation captures are intentionally not recorded in Git.

## Capture state

- Viewport: phone portrait; exact device and local environment identifiers are intentionally omitted.
- Theme: light Material 3 with dynamic color disabled for a stable comparison.
- Data: fictional TWD transactions rendered through the production Compose components. The capture test does not query the private Room database.
- States: category tab, detail tab, analysis summary/category, and scrolled six-month trend.

## Comparison

- PASS — The primary hierarchy matches the references: period controls, income/expense totals, category/detail/analysis tabs, summary card, income/expense category switch, donut with total and legend, and six-month trend.
- PASS — The final bottom navigation matches the user's correction: `首頁｜明細｜設定`; analysis is the third tab inside 明細.
- PASS — Narrow-screen layout has no visible clipping or horizontal overflow. Category options, amount labels, and bottom navigation remain legible.
- PASS — Empty/uncategorized meaning is represented explicitly and transfer-category rows are absent from reporting visuals.
- Intentional difference — Search, currency, custom date, and advanced-filter controls appear above the report because they are required in this version.
- Intentional difference — The trend shows income and expense as two simultaneous lines, as requested, while the reference uses a single selected metric.
- Intentional difference — Colors, typography, and corner radii follow Moneylook's existing Material 3 theme instead of cloning the reference app's brand.

## Iteration history

1. Initial device capture used the system dark theme and scrolled only to the trend heading.
2. The QA state was fixed to a stable light theme and the chart received a semantic test tag.
3. The second capture scrolls the actual chart into view; category and detail states were added to cover the complete three-tab experience.

## Final result

`PASSED` — no severe visual mismatch or broken core interaction remains in the inspected phone viewport.
