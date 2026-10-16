# Patient Execution Plan — thin-book exits & interleaved monthly flips (LONG_MONTHLY)

> **BUILD STATUS 2026-08-08: Parts A and B BUILT in the working tree, UNCOMMITTED (owner commits).**
> Suite: 151/151 green (110 pre-existing + 41 new/updated); `-Pproduction` compile verified.
> New: util/ExecMode, service/MonthlyFlipService, mock fill modes (`mock.fill-mode` =
> INSTANT | NEVER | AFTER_MODIFIES:n + qty-aware history/trades + real cancelOrder).
> Changed: PositionUtil (patient config + placePatientLimit + 6/7-arg overloads +
> cancelAndTopUp extraction), PositionCloseService (mode routing + applyCloseResult/
> finalizeClose extraction), PositionOpenService (prepareMonthlyOpen/savePreparedOpen +
> recordOpenResult/materializeAndSave extraction), SignalService (flipMonthly branch),
> application.properties (patient.enabled=false), pom (production testExclude).
> Implementation deltas from the spec below: (1) §4.4/§9(e) open-slice failure now STOPS
> OPENS but FINISHES the remaining close slices (legacy-equivalent exit) instead of stopping
> the whole loop — closing out the old position is the flip's primary obligation; (2) the
> flip's open aggregate counts "requested" as the qty actually attempted (paired with
> confirmed closes), so a deliberately-half flip persists LIVE, not orphan-PARTIAL.
> Both flags remain OFF; §7.3 E2E click-through and §7.0 promote protocol still pending.

> Status: DESIGN COMPLETE — §9 defaults chosen by the owner's go-ahead 2026-08-08, build done per above.
> Scope: **LONG_MONTHLY book only.** SYNTH_WEEKLY keeps today's aggressive pipeline untouched.
> This document is self-contained and written to be handed to a coding agent (same convention
> as CAPITAL_EFFICIENCY_PLAN.md — read that doc's §6 for BOOK/CALENDAR terminology).
> All file paths are under `src/main/java/path/to/_40c/nqCore/`; line numbers are as of
> commit `b5537d5` on `feature/nQSynthAndMonthly`. Verify line drift before editing.
> Deploy protocol: CAPITAL_EFFICIENCY_PLAN.md §7.0 applies verbatim (mock,test → owner
> click-through → explicit "promote" → signals.db backup → deploy). All new behavior ships
> behind config flags that default OFF.

---

## 0. TL;DR

Monthly option books go thin at times (sparse prints, wide-but-honest spreads) while the
underlying price is intact. The current execution pipeline guarantees a fill within ~2.5 s
at any cost — exactly wrong for exiting a high-premium deep-ITM monthly. Two additions:

- **Part A — PATIENT execution policy** (per-leg): rest a LIMIT at a fair-anchored price,
  re-anchor and concede slowly over minutes with a hard concession cap, never fall back to
  MARKET on a wide spread. Unfilled at deadline → leave resting, hand to the existing
  PENDING_CLOSE reconciler. Attacks **spread cost**.
- **Part B — Interleaved monthly flip** (per-book): flip in slices — patiently close one
  slice of the held option, and only after its fill is CONFIRMED, aggressively open the same
  qty of the new option. Invariant: |closed − opened| ≤ 1 slice. Attacks **drift cost**
  (price movement during a slow flip largely cancels between the unclosed old and the
  already-opened new position).

The two compose: patience makes slow affordable on spread; interleaving makes slow
affordable on drift. The 15–30 min minimum gap between signals (99% of cases) is the time
budget; the 1% back-to-back case is already covered by `resolveBeforeSignal` (§2.7).

---

## 1. Problem

### 1.1 Mechanics

LONG_MONTHLY holds a single bought monthly option (BUY ATM CE on LONG / BUY ATM PE on
SHORT, 2-lot increments). By exit time the held option is often deep ITM with premium
₹400–800 and a thin book — e.g. NIFTY AUG 23850 CE trading ₹464 with minutes-long gaps
between prints and visible spread occasionally >3%. Monthly NIFTY options are 4–10× less
liquid than weeklies (CAPITAL_EFFICIENCY_PLAN.md §1.3). On a ₹460 premium, crossing a 3%
spread costs ~₹14/share ≈ ₹900/lot; MARKET into a thin ladder can cost multiples of that.
Owner's stated tolerance: waiting 5–10 minutes for a fair exit price is fine.

### 1.2 The three burn points in current code

1. **Wide spread → instant MARKET.** `midHalfSpread` (util/PositionUtil.java:969-977)
   returns null when spread > 5% of price (:975); `placeGraduatedLimit` treats null as
   "no usable quote" and calls `placeMarketCore` directly (:605-608). A thin monthly book
   triggers the MARKET fallback precisely when MARKET is most expensive.
2. **The walk gives up after 2.5 s.** `WALK_DELAYS_MS = {500,1500,2500}` (:564),
   `WALK_AGGRESSION = {0.25,0.50,1.00}` (:567) — final step crosses whatever the spread is,
   then a MARKET top-up for any remainder (:699-728).
3. **Large qty → broker-side MARKET auto-slice.** qty ≥ 3510 (NSE freeze limit since 2026-10-05, was 1755) routes to `autoSliceFallback`
   (:538-541, :1011-1036), MARKET per slice. Not binding at today's 2-lot monthly size, but
   becomes binding at scale.

### 1.3 What each part protects (do not conflate)

- **Spread cost** is paid per leg regardless of sequencing; only *not crossing* (resting at
  fair, waiting for the book to come to you) reduces it → Part A.
- **Drift cost** (market moves during slow execution) is what interleaving cancels: at
  half-flip, what the unclosed old position loses on a move, the already-opened new
  position gains → Part B. Interleaving does NOT reduce spread; patience does NOT reduce
  drift. Both are needed.

---

## 2. Current-code contract (facts the implementation must respect)

### 2.1 Execution chokepoint

`PositionUtil.placeAggressiveOrder(Quote q, String ins, String txn, int qty, String
contextLabel)` (util/PositionUtil.java:532) is the **single** order-placement entry for the
whole app. Routing (:537-547): qty ≥ `MAX_SIZE_PER_ORDER` (3510 since 2026-10-05; was 1755) → `autoSliceFallback`;
`useLimitWalk` (`order.execution.use-limit-walk`, application.properties:34, prod=true) →
`placeGraduatedLimit` (:598-729); else `placeMarketCore` (:765-789).
Returns `record ExecResult(String aggregateOrderIds /*comma-separated*/, int totalFilled,
int totalRequested, double weightedAvgFillPrice, boolean fullyFilled, String
terminalStatus)` (:1096-1097).

All six call sites (context labels):

| Site | Label | Notes |
|---|---|---|
| service/PositionOpenService.java:120 | ENTRY | signal/flip open, parallel per leg on `LEG_EXEC` |
| service/PositionCloseService.java:132 | EXIT | signal/flip close AND orphan flatten |
| service/PositionRolloverService.java:133 / :187 | EXIT / ENTRY | weekly+monthly roll (book-fenced callers) |
| service/ProfitRecenterService.java:117 / :176 | EXIT / ENTRY | SYNTH_WEEKLY only (fence at :75) |

Existing primitives to reuse, not duplicate: `confirmTerminalFill` (:748) — post-cancel
true-fill read (the 2026-06-19 over-fill discipline); `confirmMarketFill` (:817);
`readCloseOrderState` (:882); `cancelCloseOrders` (:901); `confirmCloseOrderSettled`
(:917); `closeOrderMayBeLive` (:861); `refreshQuote` (:980); `roundToTick` (:1006);
`effectiveSpreadPaid` (:947 — deliberately has NO wide-book gate, per its Javadoc);
`alertIfMonthlySpreadExcessive` (:960, threshold `MONTHLY_SPREAD_ALERT_PTS = 2.0`,
Constants.java:52); `ExecTrace` (:1120) via `EXEC_TRACE` scoped value; WS short-circuit
`orderStream.awaitTerminal(orderId)` + re-arm after modify (:635-676).

### 2.2 Close path & statuses

`PositionCloseService.closeTrade` (:68-84): calls `pendingCloseReconciler.resolveBeforeSignal()`
AND `pendingOpenReconciler.resolveBeforeSignal()` first (:69-70), then book-scoped lookup
(`PositionUtil.findLiveTradesWithLiveOrderBooks(book)` :1184, PARTIAL fallback :73).
`doClose` (:104-178): stamps `exitSpot`, one `getQuote` for all legs (:109), parallel per-leg
close on `LEG_EXEC` with `oppositeTransaction` (:123) and the EXIT call (:132), intended
price = quote `lastPrice` (:125-130). Per-leg status (:138-150): `fullyFilled` → CLOSED;
`closeOrderMayBeLive(er)` → **PENDING_CLOSE**; else FAILED. Position status (:163-174):
all CLOSED → CLOSED; any PENDING_CLOSE → PENDING_CLOSE (closedAt deferred); else FAILED.

**Key consequence:** any `ExecResult` that carries an orderId with a non-terminal status
automatically produces PENDING_CLOSE and gets reconciler ownership. Part A's deadline
semantics need **zero new status machinery**.

### 2.3 Open path

`PositionOpenService.placeAndSave` (:100-202): parallel per-leg ENTRY (:115-140), leg
qty = `lots × LOT_SIZE` (:118), statuses per leg (:152-169): fully filled → LIVE; open
order possibly live → PENDING_OPEN; partial → LIVE **with qty trimmed to filled**
(:162-163); else FAILED. Position: LIVE / PENDING_OPEN / PARTIAL / FAILED (:180-198).
The trim-to-filled behavior is exactly what Part B's invariant needs on the open side.

### 2.4 Flip orchestration

`SignalService.handleFlip` (:85-105): books strictly sequential, error-isolated via
`runIsolated` (:277-284), SYNTH_WEEKLY first. `flipMonthly` (:230-244):
`monthlySymbolService.syncTradedContract()` → `closeMonthlyTrade(…, false)` → if
PENDING_CLOSE, settle via `closeMonthlyOrphanIfAny` → `openMonthlyTrade`. Close→open is
strictly serialized. Part B replaces the body of `flipMonthly` when its flag is on.

### 2.5 Reconcilers (the safety net Part A leans on)

`PendingCloseReconciler`: passive tick every 30 s (`@Scheduled(fixedDelay=30_000)` :71) —
known-working orders are LEFT WORKING (`trackStillWorking` :127-130; only UNKNOWN status
counts toward orphaning, 10 ticks ≈ 5 min :54). Aggressive mode `resolveBeforeSignal()`
(:80-82), called before every signal-driven close (§2.2): `cancelCloseOrders` →
`confirmCloseOrderSettled` → full → leg CLOSED; partial → `markOrphan` trims leg to the
unfilled remainder and sets it back LIVE (:180-194) so orphan-flatten trades exactly what
is held. `finalizePosition` (:203-223) runs `postTradeService.afterClose` on completion.
Open-side twin `PendingOpenReconciler` mirrors this (:63-74, :166-215).

### 2.6 Slice telemetry comes free

`WeeklyLeg.openOrderId/closeOrderId` are comma-separated order-id strings; `PostTradeService`
`afterOpen`/`afterClose` (@Async) call `PositionUtil.captureSliceFills` (:336-421) which
splits the ids, fetches per-slice fills, and upserts `LEG_FILL` rows (entity/LegFill.java,
unique (weekly_leg_id, SLICE_INDEX)) via cascade from `positionRepository.save`. **Any
multi-order execution that returns its ids comma-joined in one aggregate ExecResult gets
per-slice fill rows recorded with no new code.** Per-leg spread-paid already persists as
`OPEN_SPREAD_PAID`/`CLOSE_SPREAD_PAID` (entity/BaseLegEntity.java:80,:84).

### 2.7 Signal cadence & the back-to-back guard

Signals arrive on 15-min bars; next signal ≥15–30 min away in ~99% of cases — that is the
patience budget. The 1% case needs no new code: any new signal's close path runs
`resolveBeforeSignal()` FIRST (§2.2), which cancels a resting patient exit, confirms its
true settled fill, and flattens the remainder — patience dies automatically the moment a
new signal arrives.

### 2.8 Quotes: rate limit and the anchor constraint

Kite quote REST ≈ 1 req/s (QUOTE_FEED_TODO.md) — irrelevant at patient cadence (steps are
tens of seconds apart). **nqCore has NO index-spot source**: spot exists only as the
signal's `currentPrice` request parameter; `getLTP("NSE:NIFTY 50")` is used once as a
health-check ping (KiteConnectGateway:264). Therefore v1's fair anchor must come from the
option's own book + LTP (§3.2); intrinsic/parity anchors are explicitly deferred (§10).

### 2.9 Test/mock infrastructure facts

- JUnit suite: **no Spring context anywhere** — plain JUnit 5 + Mockito + AssertJ, SUTs
  built with `new`, `@Value` fields set via `ReflectionTestUtils` (e.g.
  PositionUtilOverfillTest.java:58-82). Fill sequences are scripted by consecutive-return
  stubbing of `gw.getOrderHistory(...)` and `placeOrder` `thenAnswer` keyed on
  `p.orderType` (:76-101). Walk sleeps collapse to ~0 by stubbing
  `stream.isHealthy()→true` + `awaitTerminal→completedFuture` — the same trick keeps
  patient-loop tests fast.
- Mock profile (runtime): `MockKiteGateway` always fills via `MockKiteOrderStream`
  50 ms synthetic fill; `getOrderHistory` **hardcodes filledQuantity="65"** regardless of
  requested qty (MockKiteGateway.java:106-119); `getOrderTrades` similarly one 65-qty
  trade with null fillTimestamp (:294-303); `cancelOrder` is a no-op returning true
  (:172-176); `modifyOrder` overwrites the stored exec price (:165-170); LIMIT and MARKET
  are indistinguishable. Only knobs: `setHealthy`, `setSyntheticFillDelayMs`.
  **These hardcodes break any multi-lot or partial-fill click-through** — §7.1 mock
  upgrades are a prerequisite for the E2E pass, not optional polish.

---

## 3. Design — Part A: PATIENT execution policy

### 3.1 Policy selection

New enum `ExecMode { AGGRESSIVE, PATIENT }` (util). New overload
`placeAggressiveOrder(q, ins, txn, qty, contextLabel, ExecMode mode)`; the existing 5-arg
signature delegates with AGGRESSIVE — **zero behavior change for every current call site.**

PATIENT is requested only by `PositionCloseService.doClose`, and only when ALL hold:

| Condition | Source |
|---|---|
| `order.execution.patient.enabled` = true | config, default false |
| `trade.getBook()` = LONG_MONTHLY | Position.book |
| the close is signal-driven (closeTrade path), NOT orphan flatten | `closeTrade` passes patientEligible=true; `closeOrphanIfAny` passes false |
| current IST time < `order.execution.patient.cutoff` | default 15:10 (§3.6) |

Everything else in the app — entries, weekly closes, rollover, recenter, orphan flatten,
reconciler flatten — stays AGGRESSIVE. `doClose` gains a `boolean patientEligible`
parameter threaded from its two callers (`closeTrade` :69→true, `closeOrphanIfAny`
:92→false). Rationale for orphan-flatten staying aggressive: it runs immediately before a
new entry; speed beats spread there.

### 3.2 Fair anchor (v1 — book + LTP only, per §2.8)

```
fairAnchor(Quote q):
  bid/ask = top of depth, validated bid>0, ask>0, ask>=bid   // NO 5% wide-spread gate
  if depth unusable → return q.lastPrice > 0 ? q.lastPrice : null
  mid = (bid+ask)/2 ; spreadPct = (ask-bid)/mid*100
  if spreadPct <= patient.sane-spread-pct (default 2.0) → fair = mid
  else → fair = clamp(q.lastPrice, bid, ask)   // wide book: LTP, clamped into the book
                                               // (stale-LTP guard: never price outside bid..ask)
  return fair
```

Concession cap per attempt, computed once at anchor time and **never widened mid-walk**:
`cap = max(patient.max-concession-pts, fair × patient.max-concession-pct / 100)`
(defaults 2.0 pts — aligned with `MONTHLY_SPREAD_ALERT_PTS` — and 0.5%; at a ₹460 premium
the pct term governs: ₹2.30).

### 3.3 Patient walk loop (new `placePatientLimit` in PositionUtil)

Mirrors `placeGraduatedLimit`'s structure (WS-await, re-anchor per step, modify-in-place,
ExecTrace) with these differences: minutes-scale schedule, concession capped vs fair
(never "cross whatever the spread is"), and no MARKET anywhere.

```
placePatientLimit(q, ins, txn, qty, label):
  ref  = refreshQuote(ins) else q
  fair = fairAnchor(ref);  if fair == null → placeMarketCore   // no price at all (auth/network
                                                               // degenerate case; close path already
                                                               // aborts on empty quote map before this)
  cap  = concession cap (§3.2)
  px   = roundToTick(fair)                                     // SELL: fair; BUY: fair
  place LIMIT DAY px; on null response → retry once → return PLACE_FAILED (no MARKET)
  for step in patient.step-delays-ms (default 20s,60s,120s,240s,420s,600s from t0):
     await fill until step deadline (awaitTerminal future when stream healthy, else
       sleep in ≤5s chunks so cutoff can interrupt)
     peek state; fully filled terminal → ExecResult COMPLETE (record slippage-vs-fair in trace)
     if IST now ≥ patient.cutoff → break                       // §3.6
     refresh quote → new fair (keep last anchor on refresh failure)
     px = roundToTick( SELL ? max(fair - cap·f[step], anchor0 - cap)
                            : min(fair + cap·f[step], anchor0 + cap) )
          where f = patient.concession-fractions (default 0.0,0.2,0.4,0.6,0.8,1.0)
          // cap is enforced against BOTH the live fair and the original anchor: a
          // collapsing fair must never walk the price unboundedly away from anchor0 —
          // if fair itself moved (underlying moved), following it IS correct; the
          // anchor0 clamp only guards against garbage quotes. See §9(d).
     modifyOrder(px); re-arm awaiter
  // deadline reached, not fully filled:
  per patient.deadline-action (default REST):
    REST   → peek final state; return ExecResult(orderId, filled, qty, avg,
             filled>=qty, lastStatus)          // non-terminal status + orderId ⇒
                                               // closeOrderMayBeLive=true ⇒ leg PENDING_CLOSE
                                               // ⇒ reconciler owns it (§2.5); next signal or
                                               // cutoff sweep ends it
    MARKET → cancel → confirmTerminalFill → placeMarketCore(remaining)   // the walk-exhausted
             discipline verbatim (:685-728), incl. OVERFILL guard
```

Notes:
- Partial fills: modify keeps the same order (Kite retains filled qty; pass original qty on
  modify, matching the existing walk convention :671).
- The loop runs on the leg's `LEG_EXEC` virtual thread; a 10-min occupancy is free.
- Every step records to `ExecTrace`: anchor, spread, px, filled-so-far — the leg's whole
  story stays one contiguous log block (:527-530).
- Quote cadence ≥20 s apart → no rate-limit interaction (§2.8).
- `alertIfMonthlySpreadExcessive` keeps firing from the close path unchanged; a patient
  fill that still paid >2 pts vs mid is exactly what that alert is for.

### 3.4 What PATIENT never does

- Never calls MARKET because the spread is wide (inverts burn point §1.2-1).
- Never concedes past the cap, no matter what the book shows.
- Never cancels-and-abandons at deadline (REST default): a resting capped LIMIT +
  PENDING_CLOSE is strictly better than either a panic MARKET or a naked cancel.

### 3.5 Post-deadline life of a resting exit

Leg → PENDING_CLOSE (§2.2). Passive reconciler leaves the working order to fill (§2.5) and
finalizes the position when it does, including `afterClose`. It is ended by whichever comes
first: (a) the order fills; (b) a new signal (aggressive resolve, §2.7); (c) the cutoff
sweep (§3.6); (d) EOD — DAY validity lapses, order dies, status goes terminal-unfilled,
reconciler orphans the remainder back to LIVE for the next signal's flatten. Known v1
limitation: the reconciler does not re-price a resting order (no chase). Accepted; noted
as a future enhancement (§10).

### 3.6 End-of-day cutoff

`patient.cutoff` (default **15:10 IST**): after it, `doClose` selects AGGRESSIVE outright,
and an in-flight patient loop breaks out of its schedule at the next step boundary and
applies `patient.deadline-action`. Purpose: a thin-book exit must not still be resting into
the 15:30 close with the signal against the position overnight. (LONG_MONTHLY carries no
margin-explosion risk overnight — it is a bought option — but the signal said exit.)

---

## 4. Design — Part B: interleaved monthly flip

### 4.1 The invariant

> **|closedQty − openedQty| ≤ 1 slice at every instant, and within each slice the close
> fill is CONFIRMED before the open order is placed.**

Close-first keeps peak exposure ≤ intended and frees premium capital that funds the open
slice. Confirmation-first is the 2026-06-19 lesson: the open slice is sized from
`ExecResult.totalFilled` of the close slice, never from a pre-cancel snapshot.

### 4.2 Slice loop (new `service/MonthlyFlipService.java`)

Replaces the body of `SignalService.flipMonthly` (:230-244) when
`order.execution.monthly-flip-interleave.enabled` = true AND a LIVE LONG_MONTHLY position
exists AND patient mode itself is enabled. Otherwise the existing close→open path runs.

```
flip(signalPrice, type, signal):
  syncTradedContract(); resolveBeforeSignal (both reconcilers)   // same prelude as today
  held  = findLiveTradesWithLiveOrderBooks(LONG_MONTHLY); if null → plain openMonthlyTrade; return
  heldLeg (single leg, side BUY → close txn SELL); heldQty = leg.quantity
  target = ComputeUtil.buildMonthlyInstrument(signalPrice, newTrade)   // new direction's leg
  slice  = order.execution.monthly-flip-slice-lots × LOT_SIZE (default 1 lot = 65)
  window = order.execution.monthly-flip-window-ms (default 600_000); slices = ceil(heldQty/slice)
  closeResults = []; openResults = []
  for k in 0..slices-1:
     thisQty     = min(slice, heldQty - closedSoFar)
     perSliceCap = max(60s, remainingWindow / remainingSlices)
     closeEr = placePatientLimit(qHeld, heldIns, SELL, thisQty, "EXIT[k]", deadline=perSliceCap,
                                  deadline-action=REST)
     closeResults += closeEr
     if closeEr.fullyFilled:
        openEr = placeAggressiveOrder(qNew, targetIns, BUY, thisQty, "ENTRY[k]", AGGRESSIVE)
        openResults += openEr                       // new ATM monthly: liquid; existing pipeline
     else:
        break    // stall: STOP OPENING (invariant). The unfilled close remainder is resting
                 // with an orderId → PENDING_CLOSE machinery owns it. Opened qty stays
                 // exactly = closed qty (± nothing).
  closeAgg = aggregate(closeResults)   // ids comma-joined, qty-weighted avg, min-terminal status
  openAgg  = aggregate(openResults)
  apply closeAgg to held leg/position via the extracted close-applier (§6.3)
  create new Position via the extracted open-applier with openAgg (qty trims to filled, §2.3)
  [PERFORMANCE] log: slices, per-slice ms, spread-paid per slice, closed/opened totals
```

Tempo property: the thin deep-ITM close leg sets the pace; the liquid ATM open leg follows
each confirmed close slice near-instantly. No coordination machinery needed beyond the loop.

### 4.3 Persistence: aggregate ExecResult, everything downstream unchanged

Both sides hand ONE aggregate `ExecResult` per leg to the same persistence code paths used
today (§6.3 extraction). Because ids are comma-joined, `captureSliceFills` records one
`LEG_FILL` row per slice automatically (§2.6) — per-slice fill-quality telemetry is free.
`CLOSE_SPREAD_PAID` computes from the entry-time quote as today; per-slice spread detail
lives in LEG_FILL + the ExecTrace log block.

### 4.4 Stall / failure matrix

| Event | Outcome |
|---|---|
| Close slice k unfilled at per-slice deadline | Loop breaks; opened == closed (invariant holds); leg → PENDING_CLOSE with resting remainder; new Position holds k slices, LIVE (trimmed qty). Next signal or reconciler settles the tail. |
| Close slice k partial | Same as above — `fullyFilled=false` breaks the loop; remainder rests. |
| Open slice k fails/partial | Bounded to one slice. Position saved with trimmed/partial qty; PENDING_OPEN machinery applies if the order may be live (§2.3). Loop continues closing? **No — v1 stops the loop** (simplest safe rule; see §9(e)). |
| App dies mid-flip | Old leg: PENDING_CLOSE or LIVE-remainder via reconciler orphaning. New position: whatever slices were persisted... see §6.4 — the new Position row is saved once at the END in v1, so a crash loses only the not-yet-persisted open ids; the tradebook backfill path (PendingOpenReconciler pattern) recovers. Slice count is small (2 at today's size); accepted v1 risk, flagged §9(f). |
| Same strike/instrument | Impossible for LONG_MONTHLY flips: close leg is a CE while open is a PE (or vice versa) — always different instruments. Broker netting concerns don't arise. |

### 4.5 Plain exits (longExit/shortExit, no new position)

No interleave — the whole qty goes through ONE patient close (`doClose` with PATIENT,
§3.1). Slicing a plain exit adds nothing: there is no opposite side gaining what the
remainder loses; the concession cap alone bounds the price. (Revisit only if live LEG_FILL
data shows large single orders scaring the thin book — §9(g).)

---

## 5. Config (all new keys; application.properties)

| Key | Default | Meaning |
|---|---|---|
| `order.execution.patient.enabled` | `false` | master switch for Part A |
| `order.execution.patient.step-delays-ms` | `20000,60000,120000,240000,420000,600000` | deadlines from t0 |
| `order.execution.patient.concession-fractions` | `0.0,0.2,0.4,0.6,0.8,1.0` | × cap per step (same length as delays) |
| `order.execution.patient.max-concession-pts` | `2.0` | cap floor, points |
| `order.execution.patient.max-concession-pct` | `0.5` | cap as % of fair anchor |
| `order.execution.patient.sane-spread-pct` | `2.0` | spread ≤ this ⇒ trust mid as fair |
| `order.execution.patient.cutoff` | `15:10` | IST; after this, AGGRESSIVE only + in-flight loops bail |
| `order.execution.patient.deadline-action` | `REST` | `REST` \| `MARKET` (§3.3, §9(c)) |
| `order.execution.monthly-flip-interleave.enabled` | `false` | master switch for Part B |
| `order.execution.monthly-flip-slice-lots` | `1` | lots per slice |
| `order.execution.monthly-flip-window-ms` | `600000` | overall flip budget |

Binding style: `@Value` fields on the owning bean (repo convention — no
`@ConfigurationProperties` anywhere; arrays as comma-separated strings parsed once in
`@PostConstruct`). Validate lengths match and fractions are non-decreasing ending ≤1.0;
log-and-disable on invalid config, never throw at startup.

## 6. Code changes by file

1. **util/ExecMode.java** (new): `enum ExecMode { AGGRESSIVE, PATIENT }`.
2. **util/PositionUtil.java**: 6-arg `placeAggressiveOrder` overload routing PATIENT →
   `placePatientLimit` (new, §3.3) — but AGGRESSIVE routing and the `qty ≥
   MAX_SIZE_PER_ORDER` auto-slice check keep priority (a patient request above max size
   still auto-slices in v1 — irrelevant at current scale, revisit at 27+ lots);
   `fairAnchor` helper (§3.2 — reuses depth-validation shape of `midHalfSpread` minus the
   5% gate); patient config fields + parsing. `placeGraduatedLimit`, `placeMarketCore`,
   `midHalfSpread` untouched.
3. **service/PositionCloseService.java**: `doClose` gains `boolean patientEligible`;
   mode = resolve(§3.1 matrix) at :132's call. Extract the per-leg result-application block
   (:138-158, intended-price stamping + status + spread-paid) into a package-visible
   `applyCloseResult(leg, er, quote)` so MonthlyFlipService reuses it. Behavior of existing
   paths unchanged (extraction refactor only).
4. **service/PositionOpenService.java**: extract the per-leg materialization/status block
   from `placeAndSave` (:141-198) into `applyOpenResult(...)` + a variant of
   `placeAndSave` that accepts pre-made ExecResults (for the flip's open side). The normal
   entry path keeps its exact current flow. **v1 persistence timing:** MonthlyFlipService
   saves the closed position and the new position once, after the loop (crash window
   accepted, §4.4/§9(f)).
5. **service/MonthlyFlipService.java** (new): §4.2 loop + aggregation
   (`aggregate(List<ExecResult>)`: comma-join ids, weighted avg, sum fills; terminal
   status = COMPLETE only if all COMPLETE, else the least-settled — mirror
   `readCloseOrderState`'s least-settled rule :876-896).
6. **service/SignalService.java**: `flipMonthly` (:230-244) branches to MonthlyFlipService
   when enabled (flag read via BookConfig-style check + config).
7. **application.properties**: §5 table, all defaults OFF/inert.
8. **Mock upgrades** (§7.1) — gateway + order stream.
9. **CAPITAL_EFFICIENCY_PLAN.md**: add a one-line cross-reference in §3.3 (liquidity guard)
   pointing here.

## 7. Test plan

### 7.1 Mock-profile upgrades (prerequisite for the E2E click-through)

`MockKiteGateway` / `MockKiteOrderStream`:
- `getOrderHistory`/`getOrderTrades` become **qty-aware**: echo the requested qty
  (track per orderId from `placeOrder`), not the hardcoded 65 (breaks 2-lot monthly today).
- `cancelOrder` marks the order CANCELLED (subsequent history reads return CANCELLED,
  filled = whatever had filled) and suppresses a pending synthetic fill.
- Scriptable patience scenario: `setFillMode(INSTANT | DELAYED(ms) | AFTER_N_MODIFIES(n) |
  PARTIAL(qty))` — minimal knob set to click through: thin-book exit that fills on step 3,
  one that never fills (deadline REST → PENDING_CLOSE → reconciler), and an interleaved
  flip where slice 2's close stalls.
- Keep default behavior INSTANT so every existing flow is unchanged.

### 7.2 JUnit (pattern A — real PositionUtil, scripted getOrderHistory; walk time collapsed
via stubbed `awaitTerminal`; patient sleeps likewise collapse because every step awaits the
stubbed future)

- `PositionUtilPatientTest`: anchor = mid when spread sane; anchor = clamped LTP when wide;
  cap floors/pct math; concession never exceeds cap even when fair collapses; wide spread
  does NOT route to MARKET; fills mid-schedule return COMPLETE with correct avg; deadline
  REST returns non-terminal ExecResult with orderId (⇒ `closeOrderMayBeLive` true);
  deadline MARKET follows the cancel→confirm→top-up discipline; cutoff bail; PLACE_FAILED
  after placement retry; partial-fill modify keeps original qty.
- `PositionCloseServicePatientRoutingTest`: LONG_MONTHLY signal close → PATIENT;
  SYNTH_WEEKLY close → AGGRESSIVE; orphan flatten → AGGRESSIVE; after-cutoff → AGGRESSIVE;
  flag off → AGGRESSIVE (pure regression).
- `MonthlyFlipServiceTest`: interleave ordering (close k confirmed before open k — verify
  via InOrder/ArgumentCaptor); open sized from close's `totalFilled`; stall stops opens
  (invariant); aggregate ids comma-joined and avg weighted; partial tail → held leg
  PENDING_CLOSE + new position trimmed LIVE; no LIVE position → plain open; flag off →
  legacy flipMonthly path.
- `SignalServiceFanOutTest` additions: monthly flip branch selection; weekly untouched.
- Full existing suite (110 tests) green untouched — the 5-arg overload guarantees it.

### 7.3 E2E click-through (mock,test profile, per §7.0 protocol)

Scripted signal sequence via HTTP as usual: open monthly → plain exit patient-fill →
flip with interleave (watch slice logs + LEG_FILL rows in signals_test.db) → flip where
close stalls → verify PENDING_CLOSE → reconciler settles → next signal flattens. Owner
clicks through trade log / equity curve; verify OVERALL performance log lines.

## 8. Rollout & evaluation

1. Build + tests green → mock,test click-through → owner "promote" → signals.db backup →
   deploy with **both flags still false** (dead code in prod first).
2. Enable `patient.enabled` alone ≥3 trading days. Watch: CLOSE_SPREAD_PAID distribution
   vs the weekly book, MONTHLY LIQUIDITY ALERT count (expect ↓), PENDING_CLOSE frequency
   and time-to-fill, `[PERFORMANCE]` close durations.
3. Then enable `monthly-flip-interleave.enabled`. Watch: flip wall-clock, per-slice
   LEG_FILL prices vs the flip's signal price (drift capture), invariant breaches (must be
   zero), OVERFILL count (must be zero).
4. Success = monthly exits stop paying the alert-threshold spread without missed exits
   (no exit resting past cutoff unfilled more than rarely).

## 9. Open decisions (owner) — defaults chosen, confirm or override

- (a) **Patience window**: default schedule tops out at 10 min (owner said 5–10). Confirm.
- (b) **Concession cap**: 2.0 pts / 0.5% of premium — at ₹460 premium that is ₹2.30/share
  (₹150/lot). Confirm or widen.
- (c) **`deadline-action`**: REST (recommended — reconciler + next signal + cutoff bound
  the tail) vs MARKET (guaranteed done at deadline, pays whatever the thin book demands).
- (d) **Anchor-follow rule**: patient price follows a MOVING fair anchor (underlying moves
  are chased; cap re-centers on live fair each step, with the original anchor as sanity
  clamp). Alternative: cap strictly vs the original anchor (never chase). Default: follow.
- (e) **Open-slice failure**: v1 stops the whole loop (safest, bounded to one slice).
  Alternative: keep closing, retry opens behind. Default: stop.
- (f) **Flip persistence timing**: single save at loop end (crash loses un-persisted open
  ids; tradebook recovery exists). Alternative: save per slice (SQLite write per slice
  during execution). Default: end-save.
- (g) **Plain-exit slicing**: none in v1 (§4.5). Confirm.

## 10. Explicit non-goals (v1)

- No intrinsic/parity fair-value anchor (needs an index/spot feed nqCore does not have —
  QUOTE_FEED_TODO.md options A/B are the enabler; revisit after that lands).
- No reconciler re-pricing/chasing of resting patient orders (§3.5 known limitation).
- No iceberg orders; no change to `MAX_SIZE_PER_ORDER` auto-slice routing.
- No SYNTH_WEEKLY patience or interleave — weekly ATM books don't need it; the weekly walk,
  rollover, and recenter paths are untouched.
- No change to signal generation, dedup, sequencing, or book fan-out.
