# What the framework must provide before the designer can progress

> **Living requirements doc.** What `vexelray` (and in two places `vexelray-gui`) has to grow before
> `vexelray-designer` — a parametric SDF design tool — can be built on it. Every claim below was read off the
> live tree or measured against it on 2026-08-30; the measurements are reproducible from
> [`tools/bench`](../tools/bench). Nothing here is a wishlist item: each requirement is something the app
> cannot work around from outside the framework.
>
> Requirements are stated as **what must become true**, not as designs. Where the codebase already names the
> fix, it is cited rather than reinvented.

**Severity is graded by what it blocks**, because "blocking" applied to most of a list stops carrying
information:

| Grade | Meaning |
|---|---|
| **B0 — first commit** | A decision about how a `Surface` lowers, or how a node is named. Retrofitting it invalidates the work built on top, so it comes before that work exists. |
| **B1 — first usable build** | The edit loop does not function without it. Can be built after B0 without rework. |
| **B2 — first release** | Shippable software needs it; a demo does not. |
| **I — important** | A real gap with real cost to the product, but the tool is usable while it is open. |

---

## 0. What already works, so it is not asked for again

This is the honest baseline, and it is a large one. The **whole pixels path already exists and runs**:

```
Surface (sealed record tree)  →  SdfScene  →  SdfComposer.fragmentSpirv
  →  SampledColorTarget.pipelineFor  →  renderInto(camera push constants)
  →  Node.image(target)        // a GUI box that samples
```

`calculator-vexel-demo`'s `SdfViewport` is a working reference for all of it, including the two things that are
easy to get wrong: the **two kinds of dirty** (a scene change is new SPIR-V and a new pipeline; a frame change
is six floats of push constant) and the **GPU threading discipline** (`renderInto` allocates a pool, submits and
waits; `VkQueue` is not thread-safe, so all GPU work happens in the frame loop's `beforeFrame` pump).

Specifically **not** requested below, because it is already there:

| | |
|---|---|
| Modelling vocabulary | `Sphere`, `Box`, `Plane`, `Capsule`, `Torus`; `Translate`, `Scale`, `Rotate`, `Mirror`, `Repeat`, `PolarRepeat`, `Twist`, `Bend`; `Union`/`Intersection`/`Difference` and all three smooth forms; `Shell`, `Round`, `Implicit` |
| Repetition correctness | `Repeat`/`PolarRepeat` fold into the nearest cell *and* the nearer neighbour and take the `min`, so cell walls do not punch holes |
| Arbitrary implicits | `Gradient` (forward-mode symbolic AD), `Normalize`, `Substitute`, `Fold`, and both declared (`Implicit.bounded`) and derived Lipschitz bounds |
| Spatially varying colour | a `Shading` wrapper replaces `point.albedo()` with any `Expr` — `MarchStyle` proves it |
| Shader caching | `ShaderCache` keyed on structural equality of the whole `SdfScene` |
| Viewport compositing | `GuiApp.viewport(w, h)` → `SampledColorTarget` → `Node.image`, rounding, clipping and laying out like any node |
| Off-thread composition | `fragmentSpirv` is pure CPU and already called from a worker in the calculator |
| Emitting a field as a called function | S1 already emits the whole field as one `float sdf(vec3)`, and D12 fixed its 22 MB by emitting once and calling — so `OpFunction` in the lowering is proven machinery rather than new ground (this matters to R2) |

---

## 1. R1 — A parameter must be changeable without recompiling the shader

**Severity: B0. This is the requirement the word "parametric" rests on.**

Every numeric in a `Surface` is a baked `double`. `Sphere(0, 0, 0, 1.0)` and `Sphere(0, 0, 0, 1.01)` are
different records, so they are a different `SdfScene`, so they are a different `ShaderKey`, so they are a
different shader. Dragging a radius slider therefore lowers the whole field to SPIR-V and calls
`vkCreateGraphicsPipelines` **once per pointer event**.

That is the central interaction of the entire application, and today it is the most expensive thing the
framework can be asked to do.

The IR already has the mechanism: `Expr.PushConstantRead` and `PushConstants` exist in `vastir.core`, and
`SdfComposer` already uses them for the camera (`CAMERA_BYTES = 24`). What is missing is any way for a
`Surface` to say *"this radius is a parameter, not a constant."*

**Must become true**

- A `Surface` numeric may be either a literal or a named parameter.
- A parameter lowers to a push-constant (or uniform) read, not a baked constant.
- `SdfComposer` publishes the parameter block's layout, so the app can write values into it.
- Two scenes differing **only** in parameter *values* are the same `ShaderKey` and share one compiled pipeline.

That last point is the test that the change is real, and it also makes `ShaderCache` start earning its keep:
today every distinct radius is a distinct cache entry, which is a cache that only ever misses.

### 1.1 A parameter must carry a range, not only a name

Every numeric field in `Surface` sits behind a compact constructor that **validates and normalises**:
`requirePositive(radius)`, `Plane` normalising its normal, `Rotate` normalising its axis and evaluating
Rodrigues in Java to emit nine constants. A bare named parameter can be none of those — so R1, stated as
"literal or name", silently deletes three invariants the compiler currently relies on.

A parameter that declares a **closed range** restores all three, and pays for itself four more times:

- `requirePositive` becomes *positive across the whole range*, and stays a construction-time check.
- Constant folding degrades honestly: an angle with a range is emitted as shader-side trigonometry instead of
  nine baked numbers, and the compiler can *say* it did, rather than quietly mis-lowering.
- R3 has something finite to estimate a parametric tree against.
- R9 has a domain over which to check a declared Lipschitz bound.
- The slider gets its bounds from the model instead of the UI inventing them, which is where they belong.

**Must also become true**: a parameter declares `[min, max]`; construction-time validity is checked over the
whole range; and a node whose Lipschitz story depends on a folded constant either accepts the parameter and
lowers differently, or rejects it by name (R12).

### 1.2 Cache identity is a canonicalisation, not an emergent property

"Two scenes differing only in values are the same `ShaderKey`" does not fall out of the change — it has to be
written. The deliverable is a **parameter-erasing normal form**: a function from `Surface` to a key that
replaces each parameter with its identity-and-range and drops its current value. Naming it makes it testable on
its own, ahead of any GPU.

**Acceptance**: sweeping a parameter across 200 values compiles exactly one pipeline and issues 200
push-constant writes; and `key(withValue(s, v1)).equals(key(withValue(s, v2)))` for every `v1`, `v2` in range.

### 1.3 What a pressure test found: three risks cleared, one hard constraint

R1 was probed against the live compiler before being committed to, because it looked like the kind of change
that could quietly invalidate the Lipschitz tracking everything else rests on. **It does not.** Three results,
all reproducible from [`tools/bench`](../tools/bench):

**The bound does not depend on any parameter value.** `ParamProbe` lowers all thirteen numeric-carrying node
kinds at values `0.5, 1, 2, 3` and reads `Field.lipschitz` back: it is `1.000` in every cell of the table. That
is structural rather than lucky — every bound in `SurfaceCompiler` is a constant (`Field.exact`), a passthrough
(`inner.lipschitz()`), or a `max` over children. Where a numeric could have entered, the arithmetic absorbs it
instead: `Scale` multiplies the distance by its factor and keeps the child's bound, and `deform` divides by
`twistStretch`/`bendStretch` and keeps the child's bound. **No numeric is ever an input to the bound**, so
parameterising them threatens nothing.

**Domain transforms and the gradient already cope.** A parameter leaf is not `Ir.POINT`, so `Substitute` passes
it through untouched — `Twist(Translate(parameterised sphere))` lowers to a marchable field with bound `1.0`.
And `Gradient` already carries the case: `Expr.PushConstantRead → Ir.zero(...)`, correct because a parameter is
constant with respect to the sample point. An `Implicit` containing a parameter differentiates correctly today,
with no change at all.

**`Fold` is the whole cost, and §1.1 is right about where it lands.** A parameter cannot be constant-folded, so
a node whose literal form folds away pays full price once parameterised (`FoldProbe`, over a plain box at
5,888 B):

| | folded | generic |
|---|---|---|
| `Translate` | identity is free | +224 B |
| `Scale` | identity is free | +212 B |
| `Rotate` | axis-aligned 6,816 B | generic axis + angle 7,952 B |

That last row is §1.1's Rodrigues point with a number on it: a parameterised angle costs about 1.1 KB over an
axis-aligned constant one. In practice this is close to free — a user dragging a slider is already at some
arbitrary value, so the literal form was already unfolded. Only nodes sitting *exactly* at an identity lose
anything, and those are nodes the user could have deleted. Against R2's multiplicative blowup it is noise.

#### The hard constraint: the composer owns the push-constant block

**A `Surface` must not declare its own `PushConstants` block.** This is the trap, and it fails silently.

Vulkan permits one push-constant block per stage, and `SdfComposer` has already spent it on the camera's six
floats (`camX, camY, camZ, yaw, pitch, aspect`). Composing a scene whose `Implicit` reads from an
*independently declared* block succeeds — no error, no warning — and `PushStruct` shows why that is worse than
failing: the emitted struct still has exactly **6 members**, the camera's. The foreign block's members are never
emitted at all. Only the member *index* survives, and it re-indexes into the composer's block.

So a parameter declared as member 0 compiles into a read of **`camX`**. The module is structurally valid, emits
one push-constant variable as it should, renders — and the radius changes when the camera orbits.

The bounds check makes this worse rather than better: the index is validated against the *foreign* block's
member list, so member 5 of a 1-member block is rejected while member 0 sails through into the wrong block.
The check sits at the wrong level to catch the actual error.

**Therefore**: parameters must be **collected by the composer** from the `Surface` tree and appended to the
block it already owns, with the resulting layout published back to the application. They cannot be declared
bottom-up by the nodes that use them — which also means §1.2's parameter-erasing normal form needs a companion
*collection* pass, walking the tree in a defined order so that two structurally equal scenes assign the same
member index to the same parameter.

**Acceptance for this half**: a scene declaring `n` parameters emits a push block with `n` members more than
the reserved camera prefix — `6 + n` as the block stands today, `7 + n` once R8 folds `focalLength` into it;
and composing a `Surface` that reads from a block the composer did not issue is **rejected by name**, not
silently aliased. `PushStruct` reports `6` for every scene today, which is the measurement that found this.

---

## 2. R2 — Stacking operators must not multiply the shader

**Severity: B0.**

Measured, over a `smoothUnion` of 8 spheres, adding one operator at a time
([`tools/bench/Ladder.java`](../tools/bench/Ladder.java)):

| stack | SPIR-V | compose |
|---|---|---|
| `smoothUnion(8)` | 15 KB | ~1 ms |
| `+ Repeat` 2 axes | 191 KB | 22 ms |
| `+ Repeat` 3 axes | 497 KB | 27 ms |
| `+ Twist` | 1.3 MB | 35 ms |
| `+ Bend` | 4.1 MB | 76 ms |
| `+ PolarRepeat 6` | — | **rejected**: exceeds 1,000,000 nodes |

There is no `Implicit` anywhere in that ladder — these are plain primitives. The growth is structural:
`Repeat` costs 2ⁿ copies of its child *by design* (the neighbour-cell `min` that makes it correct), `Twist` and
`Bend` substitute a new point expression into each of those copies, and — as `surface-compiler.md` §4.1 states
outright — **nothing downstream does common-subexpression elimination**, so every copy is re-emitted in full.

So a designer gets roughly **six stacked operators** before the compiler refuses the scene. "Dense fractal
designs and patterns that never repeat" is a stack far deeper than six.

Raising `SurfaceLimits.maxCompiledNodes` is not the fix and should not be mistaken for one: 4 MB of SPIR-V at
five operators is already unusable, and the cap is the guard rather than the problem. (D12 records a field that
reached 22 MB this way.)

The codebase already names the remedy — let-binding, scoped as **S0.5** for gradients. It applies identically
to domain transforms and combinators, and `core` has the statements for it.

### 2.1 Two budgets, and let-binding only fixes one

`Repeat`'s 2ⁿ is **not** re-emission of a shared expression. It is 2ⁿ *distinct evaluations of the child, at 2ⁿ
different points*, min'd together. Let-binding the transformed point removes the duplicated point arithmetic;
it does not remove the child evaluations. Size will drop substantially and still multiply.

Additive size requires emitting the child **once as a function** and calling it 2ⁿ times — procedure
abstraction in the lowering, a strictly larger change than S0.5's `LocalVar` bindings. It is also exactly what
D12 did for the field and what S1 already does for `float sdf(vec3)`, so the machinery exists; what is missing
is applying it below the top level.

That forces a distinction the original acceptance criterion elided. **Emitted SPIR-V size** and **per-pixel
evaluation cost** are different budgets, and a change can fix one while leaving the other untouched. A 20 KB
shader that evaluates 4,096 spheres per march step passes a size test and is unusable in the viewport.

**Must become true**

- A domain transform binds its transformed point once and its child reads the binding, rather than the child
  being re-emitted per copy.
- A repeated child is emitted **once as a callable function** and invoked per copy.
- SPIR-V size grows **additively** with stacked operators, not multiplicatively.
- Where evaluation cost still multiplies, that fact is *reported* (R3), not hidden behind a small binary.

**Acceptance**: the ladder above compiles to its last rung; the six-operator case lands within a small multiple
of the one-operator case **in bytes** rather than 300×; and its predicted evaluation cost is reported
separately and agrees with a counted trace.

---

## 3. R3 — The cost of a tree must be knowable before it is compiled

**Severity: B1.**

Today the only way to discover that a tree is too big is to pay for lowering it and catch
`SurfaceTooLargeException`. In a design tool that means the app cannot warn, cannot disable an operator that
would blow the budget, and cannot show the user how much headroom is left — it can only fail after the
expensive part.

The `Surface` tree is data, and every multiplier is structural and known statically (`Repeat` is 2ⁿ,
`PolarRepeat` is ×2, a combinator is the sum of its children). So this is a cheap tree walk, not an analysis.

**Must become true**

- A predicted **lowered-node count** *and* a predicted **worst-case field-evaluations per query** are available
  from a `Surface` **without lowering it**, in time linear in the authored tree. After R2 these two diverge,
  and the second is the one that kills the frame.
- The estimate is **attributed per authored node**, so the app can say *which* operator is spending the budget
  rather than only that the budget is spent. That is the same walk; not returning it wastes the pass.
- It never throws — an over-budget tree returns a number, which is the entire point.
- `SurfaceLimits` is reachable through `SdfComposer`/`SdfScene`, which it currently is not:
  `SurfaceCompiler.compile(surface, limits)` exists, but the composer only ever calls the default overload.

**Acceptance**: both estimates are within a stated factor of the true lowered count and the true evaluation
count across the bench cases, cost microseconds, and the per-node attribution sums to the tree total.

---

## 4. R4 — Compiling must never block the frame

**Severity: B1.**

`SdfViewport.ensurePipeline` calls `pipelineFor` inline in the frame pump. At 15 KB that is invisible; at the
4.1 MB the ladder reaches it is a stall in the middle of presentation. Composition is already safely off-thread
(`fragmentSpirv` is pure CPU); pipeline creation is not, and the framework does not currently say whether it may
be.

**Must become true**

- A documented threading contract for `pipelineFor` / `GraphicsPipeline` construction: which calls may be made
  off the frame thread, and what must be externally synchronised.
- A supported way to build a pipeline on a worker and swap it in atomically, with the previous pipeline
  remaining live and rendering until the swap.
- Safe disposal of the superseded pipeline — after a frame that no longer names it.
- **Parameter values survive the swap.** R1 makes the push-constant block part of the pipeline's contract, and
  a structural recompile can change its layout. The swap must therefore carry values across by parameter
  *identity*, not by byte offset, and a parameter that no longer exists must drop rather than corrupt its
  neighbour.

`surface-compiler.md`'s **S4** (interpreted mode, edit-time) is the more ambitious answer to the same problem
and would subsume this. This requirement is the smaller one that unblocks the app without it. Note also that
R1 is the cheap partial substitute for S4: it removes recompilation from the *numeric* edits, which are most of
them, leaving S4 to cover the structural ones.

**Acceptance**: recompiling happens entirely off the frame thread; the swap is observable within a bounded
number of frames; no frame is dropped during it; and a parameter held away from its default keeps its value
across a structural recompile. Test with a deliberately oversized scene — once R2 lands, "a 4 MB scene" no
longer exists, and an acceptance test phrased around one would pass vacuously.

---

## 5. R5 — A design must be able to be saved and reopened

**Severity: B2. A design tool that cannot save is not a tool.**

`surface-compiler.md` §3 lists "it serialises" as one of three properties bought by `Surface` being records —
but nothing in `vexelray-surface` writes or reads one. There is no writer, no reader, no round-trip. (SupirVast's
`supir` module lexes, parses and prints `core` IR as text, but that is the lowered IR, not the `Surface` tree,
and lowering is not reversible.)

**Must become true**

- `Surface` round-trips through a text (or otherwise stable) form.
- The round-trip preserves **structural equality**, so a reopened document hits the shader cache rather than
  recompiling.
- Reading is version-tolerant enough that an older document opens, or fails by name rather than silently
  producing a different shape.
- The form covers `Implicit`, which means the `core` `Expr` inside it round-trips too.
- The form carries **parameter declarations** (R1), **node identity** (R6.1) and **material** (R7). All three
  are format concerns, and all three are far cheaper to include before publication than after.

**Acceptance**: `read(write(s)).equals(s)` over the full node vocabulary, including a nested `Implicit`, a
ranged parameter, an identified node and a material.

---

## 6. R6 — Clicking a shape in the viewport must select it

**Severity: B1 for the pick; B0 for the identity half.**

There is no picking path. `SampledColorTarget` has no readback of any kind: it exposes `descriptorSet()`,
`renderPass()`, `width()`, `height()` and nothing that gets a value back to the CPU. `OffscreenDraw.toRgba`
does read pixels back, but it needs a `VulkanDevice` — which `GuiApp` deliberately does not expose — and its
render pass ends in `TRANSFER_SRC_OPTIMAL` where a sampled target ends in `SHADER_READ_ONLY`.

Nothing in `SdfComposer`'s fragment output carries identity either: it writes colour and nothing else.

Without this the designer is a viewer with a tree next to it. Selecting, dragging a handle, and snapping all
need the same answer: *what is under this pixel, and where in the world is it?*

**Must become true**

- Given a viewport and a pixel, the app can obtain the **world position** of the hit (the march already knows
  `t` exactly — this is depth it is throwing away) and **which authored `Surface` node** was hit.
- The query does not stall the frame loop.

Node identity is the harder half and interacts with R7: a `min` over a combinator loses which arm won unless
something is carried through it.

### 6.1 Identity is a document concern, not only a picking concern

Node identity is the half that is B0. A stable id per authored node is what selection-that-survives-reopen,
undo (R13), animation, and any reference to a node from outside the tree are all written against — and R5
publishes a format, after which adding identity means a format version. §14's warning about decisions that are
expensive to get wrong permanently applies to identity exactly as it applies to R1 and R2, and identity was
missing from that list in the first version of this document.

### 6.2 The cheap shape of the answer: a second pass, not a readback

Adding readback to the *display* target is the expensive route — it drags `VulkanDevice` into `GuiApp`'s API
and fights the `SHADER_READ_ONLY` / `TRANSFER_SRC_OPTIMAL` layout mismatch described above. The cheaper route
is a **pick pass**: a second pipeline variant composed from the same `SdfScene`, writing node id and `t`
instead of colour into a transfer-friendly target, rendered **on demand at pick time** rather than every frame.

That satisfies "does not stall the frame loop" trivially — it is not in the frame — yields the world position
for free from `t`, needs no change to the display path, and reuses the payload plumbing R7 needs anyway.

**Acceptance**: clicking a shape in a `Union` of several reports that shape, and the reported world point lies
on its surface within the march's hit epsilon; and the display path's per-frame cost is unchanged.

---

## 7. R7 — Colour must be able to key off *which* primitive, not only *where*

**Severity: B0 for the payload mechanism; I for the material vocabulary.**

I was wrong to call this monochrome — `MarchStyle` shows albedo can be any `Expr`, so it varies freely over
space. The real limit is narrower: `Surface` carries no material field, so colour is **by position**, never
**by identity**. "This sphere is red and that box is blue" has no representation.

`SdfScene.albedo`'s own javadoc says per-primitive materials arrive with the material matrix of
`vexel-world.md` §2. This requirement is the designer's claim on that work, and it shares its mechanism with
R6's identity half.

**Must become true**

- A `Surface` node can carry a material, and it survives the combinators.
- `Shading` can read the winning material at the hit point.
- **The payload is carried once, generically.** Id (R6.1) and material (R7) are two payloads through the same
  plumbing: a combinator's `min` becomes a select on the smaller, and a smooth combinator takes the nearer
  arm's payload — or blends it, stated explicitly either way. Building that plumbing twice, or building it for
  material only and then discovering picking needs it too, is the avoidable outcome.

A design tool can ship a first version without the material vocabulary. It cannot ship a *second* one — and it
cannot add the payload plumbing cheaply once R5 has published a format.

---

## 8. R8 — The lens must be a runtime value

**Severity: I, small.**

`SdfScene.focalLength` is documented as "the camera's only compile-time property". So changing the field of
view — an ordinary viewport gesture — recompiles the shader, for one float. Position, orientation and aspect
are already push constants; this is the one that was left behind.

**Must become true**: focal length reaches the shader as a push constant, alongside the camera block.

Subsumed by R1 if R1's parameter mechanism is general enough to cover scene-level values as well as `Surface`
numerics — which is an argument for designing R1 that way.

---

## 9. R9 — A hole must be diagnosable

**Severity: I.**

`Normalize` is explicit that `f / |grad f|` is a **local correction and not a proof**, and
`surface-compiler.md` §7 lists this as a known limitation of S0. In practice a user will type an expression,
get a surface full of holes, and have nothing to act on — the render is wrong and the tool is silent.

The eventual answer is **S3**, the interval/affine pass, which is the only thing that actually proves
hole-freeness — and which `vexel-world.md` wants anyway, since a box bound over an octree node *is* a
prefiltered vexel. That is a large piece of work and this document does not pretend otherwise.

**Must become true — at minimum, short of S3**

- The app can ask which subtrees carry a *derived* rather than a *declared* bound, so it can mark them in the
  UI as the places a hole can come from.
- Where a bound is declared via `Implicit.bounded`, a checked mode can sample the field and report a violation
  rather than rendering a lie. R1.1's parameter ranges give that sampler its domain when the bound depends on a
  parameter.

*(Checked separately, in case it looked promising: Cardinal's `BoundAnalysis` does not help here. Its
`SymExpr` vocabulary is `Add`, `Mul`, `Pow` and comparisons over `long`/`BigDecimal`, with products as opaque
atoms — no `sin`, `cos`, `exp`, `sqrt`, `abs`, `min`, `max`, and no f32 domain. SDF math is exactly the case it
cannot reason about.)*

---

## 10. R10 — The extent of a design must be knowable without rendering it

**Severity: I, small — and free if taken with R3.**

Zoom-to-fit, framing a newly added node, placing the default camera so the first primitive is on screen, and
sizing a grid are all the same question, and the app has no way to ask it. Guessing is the alternative, and a
tool that opens a saved document with the camera pointing at empty space has failed in its first second.

Every primitive knows its own bounds, every isometry maps them, `Scale` scales them, the combinators union or
intersect them, and the genuinely unbounded cases (`Plane`, an unbounded `Repeat`, an `Implicit` with no
declared bound) must *say so* rather than guess. That is the same linear tree walk as R3, over the same nodes,
with the same attribution.

**Must become true**

- A conservative world-space bounding box is available from a `Surface` without lowering it.
- Unboundedness is representable and reported per axis, not approximated by a large number.
- It composes with R3's walk rather than duplicating it.

**Acceptance**: the box contains every point where the field is negative, across the bench cases, checked by
sampling; and `Plane` reports unbounded rather than a number.

---

## 11. R11 — The viewport must be able to trade resolution for latency

**Severity: I — and the cheapest slack available on R1 and R4.**

Nothing in the framework lets the app render the viewport at reduced resolution during a drag and at full
resolution at rest. That is the standard escape hatch for exactly the interaction R1 and R4 exist to protect,
and it is worth having *whether or not* they fully succeed: a parametric tool with a heavy field will always
have a gesture that outruns the frame.

`GuiApp.viewport(w, h)` fixes a size at creation, and `Node.image` samples it into a laid-out box — so the
sampling half is already resolution-independent. What is missing is changing `w, h` without tearing down the
target and its descriptor set.

**Must become true**

- A viewport's render resolution can change at runtime, independently of the layout box it is sampled into,
  without recreating the node or invalidating the pipeline.
- The change costs no pipeline recompilation. Resolution is not a compile-time property; if it currently is,
  that is R8's problem wearing a different hat.

**Acceptance**: halving and restoring a viewport's render resolution mid-gesture compiles nothing and drops no
frame.

---

## 12. R12 — A compile failure must name an authored node

**Severity: I.**

R9 covers a field that renders wrongly. Nothing covers a field that does not render at all.
`SurfaceTooLargeException` reports a count against a limit; it does not say which of the user's operators spent
the budget, and neither does an `Implicit` that fails to lower. The user's tree is on screen — the error has
somewhere to point, and points nowhere.

This is R3's attribution applied to the failing case rather than the warning case, so it is nearly free once
R3 returns per-node numbers. But it has to be *carried on the exception*, which is a framework decision the app
cannot make from outside.

**Must become true**

- A lowering failure carries the authored node responsible (by R6.1 identity) and, where a budget was exceeded,
  the per-node attribution that explains it.
- The same holds for a rejected parameter (R1.1) and an unlowerable `Implicit`: fail by name.

**Acceptance**: every throw site in `SurfaceCompiler` names a node; a six-operator over-budget tree reports the
operator holding the largest share.

---

## 13. R13 — A design must be editable in place

**Severity: B1.**

`Surface` is an immutable record tree, which is right and buys structural sharing for free. But there is no
vocabulary for *"replace the node at this position with that one"* — and every edit in the application is one
of those. The app can rebuild the tree by hand at each edit, which is O(depth) of hand-written pattern matches
across every node kind: exactly the code the sealed hierarchy exists so as not to duplicate.

R1 removes the need for this on the numeric path — a slider writes a push constant, not a tree. It does not
touch the structural path: adding a node, deleting one, reparenting, wrapping a subtree in a `Twist`. Undo and
redo are the same operation against an earlier tree, which immutability already makes cheap, *provided* there
is a way to name the site of a change.

**Must become true**

- A stable way to name a position in a `Surface` tree — the same identity as R6.1, not a positional index,
  which a reparent invalidates.
- Replace, insert and remove at a named position, returning a new tree that shares everything unchanged.
- Sharing is real: an edit deep in one arm of a `Union` leaves the other arm reference-identical, so R1's cache
  key and R3's estimate can both be recomputed incrementally rather than from scratch per keystroke.

**Acceptance**: an edit at depth *d* in a tree of *n* nodes allocates O(*d*) nodes, not O(*n*); and the
untouched sibling subtree is `==`-identical before and after.

---

## 14. Ordering

Two of these gate everything else, and they gate it for the same reason — the edit loop is the product. A third
joins them, which the first version of this document missed: **identity**.

1. **B0, together, before anything is built on top.** All three are decisions about how a `Surface` lowers or
   how a node is named, and all three are invalidated by arriving late:
   - **R1** (parameters, with ranges, and a parameter-erasing cache key) — nothing that calls itself parametric
     works without it.
   - **R2** (function-abstracted lowering) — decides whether the modelling vocabulary is six operators deep or
     unbounded.
   - **R6.1 + R7's payload** (node identity carried through the combinators) — picking, materials, undo and the
     save format all resolve to it.
2. **R3, R4, R11, R13** — make the edit loop honest about cost, never stall the frame, degrade gracefully under
   load, and be an edit loop at all.
3. **R5** — before there is any user with a design worth losing, and *after* R1, R6.1 and R7, whose data the
   format has to carry.
4. **R6's pick pass**, **R10**, **R12** — direct manipulation, framing, and errors that point somewhere.
5. **R8** — small, do it with R1; it is the same mechanism at scene scope.
6. **R9** — begins as a diagnostic, ends as S3.

R1, R2 and identity are also the three that are cheapest to get wrong permanently, and all three become much
harder to introduce once an on-disk format (R5) has been published.

---

## 15. Reproducing the measurements

```bash
cd ../calculator-vexel-demo && mvn -q -o dependency:build-classpath -Dmdep.outputFile=cp.txt
javac -cp "$(cat cp.txt)" -d . ../vexelray-designer/tools/bench/Ladder.java
java -cp ".;$(cat cp.txt)" Ladder
```

The R1 pressure test of §1.3 is four more of the same shape, each standalone and GPU-free:

| probe | asks |
|---|---|
| `ParamProbe` | does `Field.lipschitz` vary with a node's parameter value? (it does not) |
| `FoldProbe` | what does `Fold` buy at identity values — i.e. what a parameter gives up |
| `ParamImplicit` | does a parameterised `Implicit` lower, differentiate, and survive a domain transform? |
| `PushStruct` | how many members does the emitted push-constant block actually have? |

`PushStruct` is the one worth running first: it parses the composed SPIR-V and counts the push block's members,
which is how the silent aliasing in §1.3 was found rather than reasoned about.

`ComposeBench.java` beside it covers the individual node kinds, including the gyroid
`sin x cos y + sin y cos z + sin z cos x` with a declared bound (5.8 KB) against the same gyroid with a derived
one (9.4 KB) — the cheapest available illustration of what a declared bound is worth.

The declared bound there is `2√3`: each partial is `cos a cos b − sin c sin a`, so each is bounded by 2 and the
gradient by `2√3 ≈ 3.46`. Worth stating because getting it wrong is silent and unsafe in one direction only —
a bound that is too *large* costs extra march steps, while one that is too *small* divides the field by too
little, overshoots, and punches holes. Nothing in the framework currently checks a declared bound, which is
what R9 asks for.

---

## 16. Implementation

The staged plan that discharges these requirements is [`implementation-plan.md`](implementation-plan.md).
