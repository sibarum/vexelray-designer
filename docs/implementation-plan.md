# Implementation plan — discharging the framework requirements

> The staged plan that satisfies [`framework-requirements.md`](framework-requirements.md). Stages are named
> **P0…P8** to avoid colliding with `vexelray/docs/surface-compiler.md`'s **S0…S5**, which they interleave
> with; each stage says which `R` it discharges and which `S` it overlaps.
>
> Ordering follows the requirements' §14: the three **B0** decisions first, because each is a decision about
> how a `Surface` lowers or how a node is named, and each is invalidated by arriving after the code built on
> top of it.

---

## 0. What the plan rests on, verified in the tree

Four facts, checked before the staging below was chosen. Two of them make a stage much cheaper than expected;
one makes a stage more expensive.

| Fact | Consequence |
|---|---|
| `core` already has `Function` (name, `Type.FunctionType`, `Region` body), `Expr.Call(Function, args)` and `Expr.Param(index, type)` | **R2 needs no new IR.** Function abstraction in the lowering is a `SurfaceCompiler` change against a vocabulary that already exists and that `CoreToSpirv` already emits — S1 uses it for `float sdf(vec3)`, and D12 used it to cut 22 MB. P1 is a compiler stage, not an IR stage. |
| `core` has `Buffer` (storage buffer, descriptor set 0, `BufferLoad(buffer, index)`) but **no uniform-buffer read** | The general parameter block is a storage buffer, and it is reachable with existing IR. |
| A module may declare **one** `PushConstants` block (the SPIR-V rule, stated in its javadoc); `SdfComposer` spends 24 bytes of it on the camera; Vulkan's guaranteed floor is 128 bytes | ~26 floats of parameters fit in push constants with no Vulkan work at all. That is enough to prove R1 end to end, so it is P0a; the buffer is P0b. |
| `SampledColorTarget`'s descriptor set is a **combined image sampler pointing at itself**, for later pipelines to sample it. The pipeline rendering *into* the target has no input descriptor set | P0b is not just "declare a `Buffer`" — it needs new FFM plumbing in `SampledColorTarget`/`pipelineFor` to create, own and bind an input descriptor set. This is the one genuinely Vulkan-side piece, and the reason it is staged behind a version that works without it. |

---

## P0 — Parameters *(R1, R1.1, R1.2, R8; enables R11)*

**Where**: `vexelray-surface` (the type), `vexelray-technique-sdf` (the lowering and the block layout).

### P0a — `Scalar`, and parameters via push constants

1. **`sealed interface Scalar { Lit; Param }`** in `vexelray-surface`.
   - `record Lit(double value)`.
   - `record Param(ParamId id, double min, double max, double initial)` — the range is R1.1, and it is not
     optional. `ParamId` is a value type, not a `String`, so R13's rename and R5's round-trip do not collide on
     display names.
2. **Every `double` field of `Surface` becomes `Scalar`.** Each record keeps a secondary constructor taking
   `double`s and delegating with `Lit` — records permit this, so the entire existing call surface and the 57
   S0 tests compile unchanged. That property is the acceptance test for the migration itself.
3. **Validation lifts to the range**: `requirePositive(Scalar)` means positive across `[min, max]`.
4. **Folding nodes are triaged by hand**, and the triage is documented rather than discovered:
   - `Rotate.angle` — **accepts a parameter**, and lowers to shader-side trigonometry instead of nine baked
     numbers. This is the one people will animate, so it earns its cost.
   - `Rotate` axis and `Plane` normal — **reject a parameter** in P0, by name (R12). They are directions, the
     normalisation is nonlinear, and nothing in the design tool wants to sweep them yet. Recorded as a known
     limit, not as an oversight.
   - `Scale.factor`, all primitive extents, `Repeat` cell sizes, `Twist`/`Bend` rates — accept.
5. **`ParamBlock`**: an ordered walk of the tree collecting distinct `ParamId`s → slot assignment →
   `offsetOf(ParamId)`. Published by `SdfComposer` alongside the SPIR-V, so the app can write values by
   identity (which R4 then needs across a pipeline swap).
6. **`focalLength` joins the block** — R8 falls out for free, and proves the mechanism reaches scene-level
   values, not just `Surface` numerics.
7. **`Surface.shaderKey()`** — the parameter-erasing normal form of R1.2: each `Param` contributes its slot and
   range, never its value. `ShaderKey` is built from this instead of from raw structural equality.
8. **`Implicit` is screened for foreign push-constant reads**, and this one is not optional (R1.3). Steps 1–7
   parameterise `Surface`'s *numeric fields*, but `Implicit(Expr f)` admits arbitrary IR — including an
   `Expr.PushConstantRead` against a block the composer never issued. Measured today, that case **compiles
   without complaint**: the foreign block's members are never emitted, the block stays the camera's six, and
   only the member *index* survives, so a read of member 0 silently becomes a read of `camX`. The existing
   bounds check does not catch it, because the index is validated against the foreign block rather than the
   emitted one. So the lowering must walk an `Implicit`'s expression and reject any `PushConstantRead` whose
   block is not the one `ParamBlock` issued — by name (R12). A parameter inside an `Implicit` is spelled as a
   `Param` like everywhere else.

**Cap**: `(128 − 28) / 4 = 25` parameters. Exceeding it throws by name (R12) and points at P0b.

**Tests**
- `shaderKey` equality across a value sweep — pure CPU, no GPU, runs in the S0 suite.
- 200-value sweep issues one `vkCreateGraphicsPipelines` and 200 push-constant writes (R1 acceptance).
- Range validation: `Sphere` with a radius range spanning zero fails at construction.
- `spirv-val` on a parametric scene, as S1 already does for constant ones.
- **Block-member count**: a scene with `n` parameters emits a push block with `n + 7` members (six camera plus
  `focalLength`). `tools/bench/PushStruct.java` already parses this out of the composed module and is the
  regression test for step 8 — it currently reports `6` for every scene, which is the bug it was written to
  find.
- **A foreign block is refused**: `Implicit` reading from a `PushConstants` the composer did not issue fails by
  name rather than composing. `tools/bench/ParamImplicit.java` is the reproduction.

### P0b — the parameter buffer *(deferred until a design exceeds 25)*

Swap the block's backing from `PushConstants` to `Buffer` at descriptor set 0, `BufferLoad(block, slot)`.
Requires: an input descriptor set layout, pool and set owned by the render target; a host-visible buffer;
`pipelineFor` accepting the layout; `renderInto` binding the set. The `Surface` side and `ParamBlock` do not
change — which is the point of publishing the block by identity in P0a.

**Designer can now**: drive every numeric with a slider at frame rate. This is the single biggest unlock in
the plan, and it lands first.

---

## P1 — Function-abstracted lowering *(R2, R2.1; overlaps S0.5)*

**Where**: `SurfaceCompiler`, entirely. No `core` change (see §0).

1. **Let-bind the transformed point.** Each domain transform emits its point expression into a `LocalVar` once;
   the child reads it. This is S0.5's remedy applied outside the gradient, and it is the cheap half.
2. **Emit a repeated child as a `Function`.** `Repeat`'s 2ⁿ cells, `PolarRepeat`'s two, and `Mirror`'s two stop
   being 2ⁿ inlined copies and become 2ⁿ `Expr.Call`s against one body taking the folded point as
   `Expr.Param(0)`. This is the half that actually makes size additive, and it is the same move D12 made.
3. **Memoise by subtree.** A `Surface` subtree appearing more than once anywhere (structural equality is free —
   they are records) is emitted once and called from each site. This subsumes (2) and generalises it to sharing
   the user creates by hand.
4. **Instrument the two budgets separately**: emitted node count, and worst-case field evaluations per query.
   P3 predicts these; P1 is where the ground truth to check the prediction against comes from.

**Tests**
- `Ladder.java` runs to its last rung — `+ PolarRepeat 6` compiles instead of being rejected.
- Size at six operators is within a small multiple of one operator, asserted as a ratio so it does not rot.
- Differential: the function-abstracted field and the inlined field agree to f32 tolerance over a sample grid.
  This is the only thing that makes the change trustworthy, and it is cheap because both lower from the same
  `Surface`.
- `spirv-val` on every ladder rung.

**Honest note**: evaluation cost still multiplies for `Repeat`. That is inherent to the neighbour-cell `min`,
not a defect of this stage. P3 reports it; S3's interval pass is what eventually reduces it.

---

## P2 — Identity and payload *(R6.1, R7; feeds R5, R12, R13)*

**Where**: `vexelray-surface` (identity), `SurfaceCompiler` + `vexelray-shader` (payload).

1. **`NodeId` on every `Surface` node.** Stable, opaque, preserved by every edit that does not replace the
   node. Assigned at construction; explicitly *not* derived from structure, because two identical spheres are
   two selectable objects.
   - This is the change with the widest blast radius and the least visible payoff on the day it lands, which is
     exactly why it is B0 rather than "later".
2. **One generic payload channel through the combinators.** The field's return becomes `(distance, payload)`.
   - `Union`'s `min` → select the payload of the smaller.
   - `Intersection`/`Difference` → the arm that determined the result.
   - The smooth forms → **the nearer arm's payload, not a blend**, stated in the javadoc as a deliberate
     choice: blending an id is meaningless and blending a material index is worse than choosing.
   - Domain transforms and `Shell`/`Round` pass it through untouched.
3. **Two payloads over one channel**: `NodeId` (R6) and material (R7). Building the channel once is the whole
   requirement; the material *vocabulary* can then arrive on its own schedule.
4. **`Shading` reads the payload** at the hit point, so `MarchStyle`-style shading can key off identity.

**Tests**
- A `Union` of two spheres: sample points nearer each and assert the payload names the right one.
- Payload survives a `Twist` over a `Repeat` over a `smoothUnion`.
- `NodeId` is preserved across a P5 edit of a sibling.

---

## P3 — The cost and extent walk *(R3, R10, R12)*

**Where**: `vexelray-surface`, one visitor, plus exception plumbing.

1. **One linear walk returning three things per node**: predicted lowered nodes, predicted worst-case
   evaluations, conservative world-space bounds. They share a traversal because they share a recursion; running
   three walks would be the version of this that gets written by accident.
2. **Bounds represent unboundedness per axis** — `Plane`, an unbounded `Repeat`, an `Implicit` with no declared
   bound. Not a large number (R10).
3. **Attribution is per authored node**, and it never throws (R3).
4. **`SurfaceLimits` reaches `SdfComposer`/`SdfScene`** — today `SurfaceCompiler.compile(surface, limits)`
   exists but the composer only calls the default overload.
5. **`SurfaceTooLargeException` carries the `NodeId` and the attribution** (R12), as does every other throw site
   in `SurfaceCompiler` — a rejected parameter (P0a step 4), an unlowerable `Implicit`.

**Tests**: prediction within a stated factor of P1's measured truth across every bench case; attribution sums
to the total; sampled points with negative field all lie inside the reported box; `Plane` reports unbounded;
the six-operator over-budget tree names the operator holding the largest share.

**Designer can now**: warn before an edit, grey out an operator that would blow the budget, show headroom,
zoom-to-fit, and explain a failure by pointing at a node in the tree view.

---

## P4 — Off-thread compilation, atomic swap, and resolution *(R4, R11)*

**Where**: `vexelray-vulkan` (the contract and the swap), `vexelray-gui` (`GuiApp.viewport`).

1. **Write the threading contract down** — which of `pipelineFor` / `GraphicsPipeline` construction may run off
   the frame thread and what must be externally synchronised. It is currently unstated, which is the actual
   blocker; the answer may well be "most of it already can".
2. **Build on a worker, swap in `beforeFrame`.** Previous pipeline stays live and rendering until the swap.
3. **Dispose after a frame that no longer names the superseded pipeline** — a small retirement queue keyed on
   frame index, not a `vkDeviceWaitIdle`.
4. **Carry parameter values across the swap by `ParamId`**, never by byte offset (R4). A parameter that no
   longer exists drops; one that moved slot follows its identity. This is why P0a publishes the block by
   identity.
5. **Runtime viewport resolution** (R11): `w, h` change without recreating the node, the descriptor set, or the
   pipeline. Half-resolution during a gesture, full at rest.

**Tests**: a deliberately oversized scene recompiles with no dropped frame and a bounded swap latency — *not*
"a 4 MB scene", which P1 has abolished; a parameter held off-default keeps its value across a structural
recompile; a mid-gesture resolution halve compiles nothing.

---

## P5 — The edit vocabulary *(R13)*

**Where**: `vexelray-surface`.

Replace / insert / remove at a `NodeId`, returning a new tree that shares every untouched subtree by reference.
Undo and redo are then just holding earlier roots, which immutability already makes cheap. Incremental
recomputation of `shaderKey` (P0a) and the cost walk (P3) hangs off the same sharing.

**Tests**: an edit at depth *d* in *n* nodes allocates O(*d*); the untouched sibling arm is `==`-identical
before and after.

**Designer can now**: be an editor. P0 + P3 + P4 + P5 is the first build a person could actually use.

---

## P6 — Serialisation *(R5)*

**Where**: `vexelray-surface`, and the `core` `Expr` reader/writer for `Implicit`.

Deliberately after P0, P2 and P5, because the format must carry parameter declarations with ranges, `NodeId`s,
and materials — all three of which are cheap to include now and a format version to add later. Version-tolerant
reading: an older document opens, or fails **by name**.

**Tests**: `read(write(s)).equals(s)` over the full vocabulary including a nested `Implicit`, a ranged
parameter, an identified node and a material; and a reopened document *hits* the shader cache, which is the
test that identity survived the round trip.

---

## P7 — The pick pass *(R6)*

**Where**: `vexelray-technique-sdf` (a second pipeline variant), `vexelray-vulkan` (a readback target).

A second variant composed from the same `SdfScene`, writing `NodeId` and `t` instead of colour into a
transfer-friendly target, rendered **on demand at pick time**. Not in the frame, so R6's "must not stall the
frame loop" is satisfied structurally rather than by tuning; world position comes free from `t`; the display
path is untouched.

**Tests**: clicking a shape in a `Union` reports that shape; the world point lies on its surface within the
march's hit epsilon; the display path's per-frame cost is unchanged.

**Designer can now**: direct manipulation — select, drag a handle, snap.

---

## P8 — Bound diagnostics *(R9)*

Report which subtrees carry a *derived* rather than a *declared* Lipschitz bound, so the UI can mark them as
the places a hole can come from. Add a checked mode that samples a declared `Implicit.bounded` over its
parameter ranges (P0a) and reports a violation instead of rendering a lie. Ends, eventually, as **S3**.

---

## Sequencing at a glance

| Stage | Discharges | Grade | Blocked by |
|---|---|---|---|
| P0a | R1, R1.1, R1.2, R8 | B0 | — |
| P1 | R2, R2.1 | B0 | — (independent of P0a; both touch `SurfaceCompiler`, so land P0a first to avoid a merge) |
| P2 | R6.1, R7 payload | B0 | P1 (payload rides the lowering P1 rewrites) |
| P3 | R3, R10, R12 | B1 | P1 (needs its ground truth), P2 (needs `NodeId` for attribution) |
| P4 | R4, R11 | B1 | P0a (parameter identity across swap) |
| P5 | R13 | B1 | P2 |
| P0b | R1 at scale | B1 | P0a |
| P6 | R5 | B2 | P0a, P2, P5 |
| P7 | R6 pick | B1 | P2 |
| P8 | R9 | I | P0a |

**The three B0 stages — P0a, P1, P2 — come first and together.** Everything downstream is additive over them;
nothing downstream is cheap to retrofit under them. P6 in particular must not precede them, because publishing
a format over a `Surface` that lacks parameters, identity and materials converts three ordinary changes into
three format versions.
