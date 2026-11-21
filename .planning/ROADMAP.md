# Roadmap: YiCamAlt

## Milestones

- ✅ **v1.0 MVP** — Phases 1-7 (shipped 2026-11-21) — 75 plans, all verified on Xiaomi Mi 9

## Phases

<details>
<summary>✅ v1.0 MVP (Phases 1-7) — SHIPPED 2026-11-21</summary>

- [x] Phase 1: Секреты и реальный список камер — 13/13 plans, complete
- [x] Phase 2: Нативный P2P и первый кадр — 13/13 plans, complete
- [x] Phase 3: Полноценный live-view — 14/14 plans, complete
- [x] Phase 4: Архив SD-карты — 14/14 plans, complete
- [x] Phase 5: Лента событий — 8/8 plans, complete
- [x] Phase 6: Релизная сборка и проверка на телефоне — 9/9 plans, complete
- [x] Phase 7: Устранение гэпов регрессии — 4/4 plans, complete

**Shipped:**YiCamAlt — альтернативный Android-клиент камер Yi Home / Yi Dome без подписки.
Полный путь: логин и список камер → P2P-соединение → live-view → архив SD → лента событий →
подписанный релиз. Верификация на физическом Xiaomi Mi 9: UAT фаз 2–6 закрыты, security audit
всех фаз `threats_open: 0`, unit suite 1111/0.

**Archive:** `.planning/milestones/v1.0-ROADMAP.md`, `.planning/milestones/v1.0-REQUIREMENTS.md`,
`.planning/milestones/v1.0-phases/`, `.planning/milestones/v1.0-MILESTONE-AUDIT.md`.
Summary: `.planning/MILESTONES.md`.

</details>

## Backlog

### Phase 999.1: Follow-up — Phase 2 incomplete plans (BACKLOG)

**Goal:** Close the execution record for Phase 2 plans that ran without a SUMMARY.md
**Source phase:** 2 (`.planning/phases/02-p2p`)
**Deferred at:** 2026-11-18 during `/gsd-progress --next` advancement to Phase 07
**Decision:** user chose [C] Continue and defer to backlog
**Plans:**

- [x] 02-12: device verification of native P2P live — resolved 2026-11-19 by `02-12-SUMMARY.md` (UAT 7/7 pass on Mi 9 with a real TNP camera)

**Outstanding:** none. Resolved on device 2026-11-19; `02-HUMAN-UAT.md` and
`02-VERIFICATION.md` were canonicalized and Phase 2 closed.

### Phase 999.2: Follow-up — Phase 7 incomplete plans (BACKLOG)

**Goal:** Close the execution record for Phase 7 plans that ran without a SUMMARY.md
**Source phase:** 7 (`.planning/phases/07-regression-fixes`)
**Deferred at:** 2026-11-18 during `/gsd-progress --next` advancement
**Decision:** user chose [C] Continue and defer to backlog
**Plans:**

- [x] 07-04: version 1.0.1 bump, clean-clone release build, device regression re-check — resolved 2026-11-18 by `07-04-SUMMARY.md` (status: halted at human gate), later closed by Phase 7 UAT on 2026-11-18

**Outstanding:** none. The 5 WIP files (frame queue 48→128, hold 96→256, H-R10) were
committed at the user's explicit request as `9ac2521`.
