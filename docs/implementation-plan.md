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

Five facts, checked before the staging below was chosen. All five make a stage cheaper than expected; the
fourth was recorded the other way round in the first version of this plan, and correcting it is what moved P0b
from "deferred" to "with P0a".

| Fact | Consequence |
|---|---|
| `core` already has `Function` (name, `Type.FunctionType`, `Region` body), `Expr.Call(Function, args)` and `Expr.Param(index, type)` | **R2 needs no new IR.** Function abstraction in the lowering is a `SurfaceCompiler` change against a vocabulary that already exists and that `CoreToSpirv` already emits — S1 uses it for `float sdf(vec3)`, and D12 used it to cut 22 MB. P1 is a compiler stage, not an IR stage. |
| `core` has `Buffer` (storage buffer, descriptor set 0, `BufferLoad(buffer, index)`) but **no uniform-buffer read** | The general parameter block is a storage buffer, and it is reachable with existing IR. |
| A module may declare **one** `PushConstants` block (the SPIR-V rule, stated in its javadoc); `SdfComposer` spends 24 bytes of it on the camera; Vulkan's guaranteed floor is 128 bytes. **Nothing in `vexelray` ever queries `maxPushConstantsSize`** — the 128 is read off the spec, not off the device | ~26 floats of parameters fit in push constants with no Vulkan work at all, which is enough to prove R1 end to end (P0a). But the real ceiling is per-device and unmeasured, so it is the wrong axis to scale a *document* on: see P0b. |
| **`StorageBuffer` already exists** in `vexelray-vulkan/present` — host-visible, persistently mapped floats, owning its own descriptor pool and set, exposing `descriptorSetLayout()`, `descriptorSet()` and `update(float[], int)`. `SampledColorTarget.pipelineFor` already takes `long[] descriptorSetLayouts`, and its javadoc says outright: *"Pass the layout from whatever owns the descriptor (see `StorageBuffer`), and pass that same object's set to `renderInto`."* `renderInto` binds it at `firstSet = 0` | **P0b needs no new FFM.** The plumbing it was staged behind is built, documented and pointed at this exact use — `StorageBuffer`'s own javadoc names "a distance field whose geometry is data rather than code" as its motivating case. What remains is `SdfComposer` emitting `BufferLoad` instead of `PushConstantRead`, and `Viewport` owning the buffer and threading its layout and set through two calls it already makes. |
| `renderInto` **waits on its own fence before returning** (`SampledColorTarget`, the comment explaining the change from `vkDeviceWaitIdle`), and `StorageBuffer`'s javadoc states the consequence: a caller that updates between calls to `renderInto` is already safe | P0b needs **no ring buffer and no frames-in-flight regions**. It also inherits an obligation — see P0b's note on what happens the day that wait is removed. |

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
5. **`ParamBlock`**: an ordered walk of the tree collecting distinct `ParamId`s → slot assignment. Published by
   `SdfComposer` alongside the SPIR-V, so the app can write values by identity (which R4 then needs across a
   pipeline swap).
   - **It publishes `write(ParamId, double)`, not `offsetOf(ParamId)`.** A byte offset is the encoding leaking
     through the boundary, and it commits the app to one storage class and one representation. Three changes
     that are otherwise invisible would each break it: P0b's move from push constants to a buffer, any
     quantised packing of two ranged parameters into one 32-bit slot, and N-buffering the block if R4 ever
     removes the fence wait. A writer keeps all three behind `SdfComposer`, and — since R5's format must carry
     parameter declarations — keeps a byte offset from ever reaching disk, where changing it would mean a
     format version. This is the same class of decision as §14's B0 three: cheap now, expensive after
     publication.
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

That number is smaller than it looks, and it is why P0b is no longer deferred behind it. A `Sphere` is four
numerics and a `Translate` is three, so a tree of twenty primitives and fifteen transforms wants north of a
hundred — 25 is spent somewhere around the sixth shape, well before anyone would call the tool exercised.
And the 128 is the spec floor rather than a measurement: `maxPushConstantsSize` is queried nowhere in
`vexelray`. Query it, and publish it through `SurfaceLimits` alongside R3's estimates so the app can report
headroom instead of discovering it — but do not scale a *document* on it. It varies per device, so a design
authored where the driver reports 256 bytes fails to open where it reports 128, and it fails at load time.
Measure the ceiling to be honest about it; use P0b to stop standing on it.

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

**Landed** — `vexelray` `A slider is a write, and only a shape is a compile`. All eight steps, with three limits
recorded by name and two things measured that were not expected:

- **`Stroke`'s vertices join the rejections**, alongside `Rotate`'s axis and `Plane`'s normal, and for a
  sharper reason than either: the guarantee that every vertex lies on the rendered centre line is kept by
  solving each corner in Java against its neighbours' positions and radii (`Spine`), and driving them would
  move that solve into the shader, per cone per march step.
- **An `Implicit`'s expression cannot carry a parameter.** Step 8 said a parameter inside an `Implicit` is
  spelled as a `Param` like everywhere else — it cannot be, because `Implicit` holds raw `core` IR and raw IR
  has no way to spell one. The screen is built and refuses a foreign block by name; a driven implicit is driven
  from outside, by parametric transforms around it. Giving it one of its own means a marker the compiler
  substitutes, which is a decision about the IR rather than about this stage.
- **The rejections are types, not runtime checks.** A `double` field cannot be handed a `Param`, so R12's "fail
  by name" has nothing to fail: the call does not compile. Cheaper than a message.
- **`PushStruct` now reads 7, 8, 10 and 32** for 0, 1, 3 and 25 parameters, against the flat `6` it reported for
  every scene before. Both bugs it was written to find are closed, and it stays as their regression test.
- **A driven domain transform pays its cost once per copy of the transformed point** — a parametric rotation
  about `+Y` emits 24 trigonometric calls where it should emit 2, because a `Box` reads its point four times
  and nothing downstream does CSE. A literal rotation duplicated nine constants, which is why this was never
  visible. It is precisely what P1 step 1 removes, and it now has a test that will say so. **This is an
  argument for P1 landing next rather than P0b.**

Beyond the eight steps: `Bounds` does interval arithmetic over declared ranges, because a box computed from
today's slider is a containment claim that expires when the slider moves — and a driven rotation reports the
ball its corners sweep rather than a box that turns. `Viewport` carries values across a recompile by identity
and pushes the whole block; what it does not yet have is any UI to move them, which is the next app-side piece.

### P0b — the parameter buffer *(with P0a, not deferred behind it)*

Swap the block's backing from `PushConstants` to `Buffer` at descriptor set 0, `BufferLoad(block, slot)`. The
`Surface` side and `ParamBlock` do not change — which is the point of publishing the block by identity in P0a.

The first version of this plan deferred P0b because it read as the one genuinely Vulkan-side piece: "an input
descriptor set layout, pool and set owned by the render target; a host-visible buffer; `pipelineFor` accepting
the layout; `renderInto` binding the set." **All four of those exist** (§0). `StorageBuffer` is the first two,
`pipelineFor`'s `long[] descriptorSetLayouts` overload is the third and names `StorageBuffer` in its own
javadoc, and `renderInto` already binds a set at `firstSet = 0`. What is left:

1. **`SdfComposer` emits `BufferLoad(block, slot)`** where P0a emitted `PushConstantRead`. The push block keeps
   the camera and `focalLength`; only the parameters move.
2. **`Viewport` owns a `StorageBuffer`**, passes `descriptorSetLayout()` to `pipelineFor` and `descriptorSet()`
   to `renderInto`, and calls `update` when a slider moves. Capacity is fixed at construction, so growing past
   it means recreating the buffer and the pipeline — size it generously, and let R3 report against it.
3. **Hoist the loads.** This is the one new risk the push-constant version did not have. A push constant lands
   in a register; a buffer load is memory, and the march reads parameters inside a ~100-iteration loop. Drivers
   hoist loop-invariant loads, but P1 makes the field a `Function`, and hoisting across a call is exactly where
   that gives up. Decorate the block `NonWritable` + `Restrict`, or load it once into locals at the top of
   `main` and have the field read those. Measure it — discovering this as "the buffer version is mysteriously
   slower" would be an avoidable afternoon.

**Two constraints inherited rather than chosen**

- **`renderInto` binds exactly one descriptor set.** Fine here, because the march samples nothing — the
  parameter buffer can have set 0 to itself. R6's pick pass is the first thing that wants a second, and that
  *is* a small `renderInto` change, to be paid there rather than here.
- **The fence wait is load-bearing.** `StorageBuffer.update` is safe between `renderInto` calls only because
  `renderInto` waits on its own fence before returning. `SampledColorTarget`'s own comment contemplates
  removing that wait, and R4 and R11 are the requirements that would want it removed. On that day a single
  mapped block becomes a write-while-reading hazard. N-buffering it behind `ParamBlock` costs nothing today
  and is unpleasant to retrofit through a published API — which is the second argument for P0a step 5's
  writer.

**Not doing: quantised packing.** A ranged parameter (R1.1) does not need 32 bits — `unpackUnorm2x16` plus a
`mix(min, max, t)` whose bounds are already compile-time constants (they are in the shader key, P0a step 7)
would fit two per slot and take the push-constant cap to 50. It is safe: §1.3's `ParamProbe` shows
`Field.lipschitz` is `1.000` regardless of any numeric, so quantising a value cannot punch holes, and
`mix(min, max, t)` stays inside the declared range by construction. It is recorded here and *not* done, because
it buys 25 parameters where P0b buys thousands for comparable effort. It becomes interesting again only as a
bandwidth measure, and P0a step 5's writer is what keeps that option open.

**Tests**
- The P0a sweep, unchanged and passing against the buffer backing: 200 values, one
  `vkCreateGraphicsPipelines`, 200 writes. That it needs no edit is the acceptance test for step 5's writer.
- A scene with 200 parameters composes, renders, and reports its slots — the case P0a throws on.
- `spirv-val` on a scene whose field reads `BufferLoad`, and a differential against the push-constant lowering
  of the same `Surface` at the same values, to f32 tolerance. Both lower from one `Surface`, so this is cheap
  and it is the only thing that makes the swap trustworthy.
- **March-step cost with and without the hoist**, on the `Ladder` scenes, so step 3 is a number rather than an
  intention.

**Designer can now**: drive every numeric with a slider at frame rate. This is the single biggest unlock in
the plan, and it lands first.

**Landed** — `vexelray` `Ask the device which road, never which designs are allowed`. All three steps, plus a
correction to this document's own reasoning.

**The device is queried after all, and the plan was wrong to rule it out.** The sentence above — *"do not
scale a document on it; it varies per device, so a design authored where the driver reports 256 bytes fails to
open where it reports 128"* — is right about the **cap** and wrong about the **query**, and P0b is precisely
what separates them. Once both roads exist, the reported limit decides *which road the values travel*, not
whether the design exists. A design opens everywhere; on a smaller machine its values simply go by buffer.
So the rule is sharper than "do not query":

> **Query the device to choose the road. Never to decide what a design may contain.**

This machine reports **256 bytes — 57 parameters, against the floor's 25**, which the app had been leaving on
the table. `ParamBacking` is that choice as a value: `AUTO` by size against the reported limit, `PUSH_CONSTANTS`
or `BUFFER` forced for a test, set once when the device is known.

- **`AUTO` earns its keep** rather than always taking the buffer, and this *is* step 3's hoisting risk
  answered: a push constant lands in a register and a buffer load is memory, read inside a march loop that
  runs a hundred iterations per pixel. A design that fits keeps the register path; only one that does not
  pays. Not taking the buffer until it is needed beats hoisting loads out of it.
- **The backing is in the shader key**, because it is in the SPIR-V — keyed on the *road taken*, so two
  devices reporting 128 and 256 share one cache entry for a three-parameter scene and split for a forty.
- **P0a's writer is what made it free.** `ParamBlock` publishes `write(ParamId, double)` and no offset, and
  the third reason its javadoc gave for that was exactly this move; the surface, the slots and the host API
  are untouched, and `inBuffer` is thirty lines beside `inPushConstants`.
- **`maxPushConstantsSize` costs no new FFM**, as §0 predicted of the rest: the
  `VkPhysicalDeviceProperties` layout this repo already had stopped at the device name, and every field
  between it and the limit is now *named* rather than skipped, so the offset is derived by the layout API
  instead of counted by hand. A miscount there does not crash — it reads a neighbouring field and returns a
  plausible number — so anything below the guaranteed 128 is refused as a layout bug rather than believed.

Three checks, in increasing order of what they would have caught: `spirv-val` accepts the buffer-backed
module; the two lowerings of one surface agree point by point on the CPU (which needed `Eval` to learn both
roads, and makes parametric fields evaluable at all); and `BufferRoadSmokeTest` **renders 64 spheres past the
cap on a real device and counts pixels**, with the control being the same pipeline drawing small radii against
large ones — a shader ignoring the buffer would draw the same picture twice.

**Still open**: the engine's own `SdfRaymarchTechnique` binds no descriptor set, so a design past the cap
takes the buffer road in the designer's viewport but not yet through the engine's technique path. The
composer, the device query and the buffer are all in place for it; what is missing is the technique creating
a `StorageBuffer` and binding it, which is a handful of lines in a file the engine work is actively editing.

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

**Landed** — `vexelray` `A stack of operators is a list, not a product`. The ladder, before and after, with the
two budgets P1 step 4 asked for:

| rung | before | after | nodes | evals | fns |
|---|---|---|---|---|---|
| `smoothUnion(8)` | 15,172 B | 15,172 B | 826 | 826 | 0 |
| `+ Repeat 2 axes` | 191,032 B | 12,596 B | 443 | 4,049 | 9 |
| `+ Repeat 3 axes` | 496,744 B | 14,472 B | 566 | 8,116 | 9 |
| `+ Twist` | 1,295,500 B | 15,468 B | 606 | 8,156 | 9 |
| `+ Twist + Bend` | 4,091,072 B | 16,192 B | 646 | 8,196 | 9 |
| `+ PolarRepeat 6` | **refused** | 17,312 B | 735 | 16,485 | 12 |
| `+ Mirror` | **refused** | 17,664 B | 745 | 16,495 | 12 |
| `+ Repeat 2 again` | **refused** | 19,304 B | 852 | 66,093 | 14 |

**Size is flat and work is not**, which is the stage stated in two columns: 826 nodes at one operator and 852
at eight, against 826 evaluations and 66,093. The honest note above is now a number rather than a caveat, and
it is what P3 will predict against.

Four things the plan did not have right:

- **`Mirror` was never 2ⁿ.** It folds with `abs` and lowers its child <em>once</em> — step 2 named it by
  mistake. What does multiply is `Repeat` (2ⁿ) and `PolarRepeat` (2).
- **A soft blend writes each child twice**, and nobody had noticed. `log-sum-exp` is stated relative to the
  hard extremum, so every child appears in the `min` chain and again in its own exponential. Invisible in the
  tree, two copies in the module — and the reason the second rung is *smaller* than the first.
- **A leaf written twice is not worth a function**, so sharing has a threshold: anything with a child of its
  own shares at two lowerings, a bare primitive at four. The second rung being smaller than the first is the
  visible cost of that threshold, and lowering it is a tuning question left open rather than settled.
- **A `LocalVar` is an identity, not a value.** Naming a point means a lowered `Field` is no longer
  structurally equal across two compiles of one surface — a variable is a place, and two compiles name two
  places. What matters survives: the `Surface` is still a record all the way down, so the shader cache still
  keys on it, and an equal scene still composes to byte-identical SPIR-V. Asserted, because it is the property
  the cache rests on.

Also: `Field` is now a program rather than an expression — it carries the declarations its distance reads and
the functions it calls, and whoever assembles the module must add them (`SdfComposer` does, and publishes
`helperFunctions`). `Field.at` still hands back the one-expression form for callers that need one, by
substituting the declarations back and expanding the calls; for a nest of repeats that form does not fit in
memory, which is the measurement rather than a caveat.

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

**Step 1 landed** — `vexelray` `Two spheres in one place are one shape and two objects`. Every node carries a
`NodeId`, minted at construction, preserved by every edit that does not replace it, supplied rather than minted
when a document is read back.

The blast radius was where the plan said it would be, and it was all in one direction: **identity breaks
structural equality, and three things were leaning on it.**

- The **shader cache** collapsed identical scenes by the description's own `equals`. `SdfComposer` now
  overrides `keyFor` and fingerprints `surface.shaderKey()` — the seam `ShaderKey`'s own javadoc had been
  pointing at all along.
- **`shaderKey()`** erases node identity as well as parameter identity, replacing each id with the node's
  position in the walk. One rule in both cases: erase what the lowering does not read.
- **`Shared`** — P1's decision about what to emit once — counted by structural equality, so identity would
  have turned every shared subtree back into a copy. It now counts on the tree with identities flattened, and
  hands the compiler two answers rather than one: which *instances* to share, and which *shape* to key the
  memo on. **That second half was a real bug**, and the test written for the interaction caught it: two
  separately authored copies of one arm compiled to two functions. Nothing would have failed — it is a size
  regression with no wrong answer in it.

The ladder is byte-identical across the change, rung for rung and evaluation for evaluation, which is the
evidence that all three normalisations are right.

**Step 2 landed as a second lowering mode** — `vexelray` `A field that says how far away, and a second one
that says what`. The payload collided with P1, and the plan predates P1 so it did not say which way to
resolve it: a shared subtree is emitted **once** and called from every site; a payload names **which node**;
two sites of one shared function are two nodes. Three ways out, and the third column is why the second won:

| | what it costs | what it gives up |
|---|---|---|
| **Payload rides the distance** — every function returns `vec2(distance, payload)` | every scene pays the payload's selection arithmetic, whether or not anything reads it | the module's stated invariant, that generality costs nothing when it is not used |
| **A second lowering mode** ✔ — payload on request, and when on, the memo keys by node instead of by shape | two lowerings to keep in step; the payload variant is larger, though a repeat still shares | nothing on the display path: a scene that does not ask pays nothing |
| **Payload without sharing** — build it inline, as colour is | the pre-P1 size, which for a nest of repeats does not fit in memory | the ladder, immediately |

It is also what **P7 already assumed**: the pick pass is *"a second variant composed from the same
`SdfScene`"*, rendered on demand and not in the frame. So `compile` is the field it always was, byte for
byte, and `compileWithPayload` is the one that answers *what*. Two things differ in the second mode, both
real: every combinator **binds its arms' distances**, so the comparison that picks the distance picks the
payload with it; and sharing **narrows from shapes to nodes**, so a repeat's 2ⁿ cells still share one
function — one authored node, and clicking any cell should select it — while two separately authored
lookalikes become two, because they are two answers.

Four things worth keeping:

- **The channel carries a slot, and `PayloadTable` is what a slot means.** A slot is an encoding: assigned by
  walk order, and it moves whenever the tree changes shape. Resolve it the moment it comes back; never store
  it. The same rule `ParamBlock` follows, for the same reason.
- **Only the nodes that own their points get one** — a primitive, a stroke, an implicit. A combinator chooses
  between children rather than owning a point, and asking for a union's slot says so by name. So a click
  resolves to the shape under the cursor, and the tree view is what selects a group.
- **A blend chooses rather than blends**, which the plan asked for and which needed stating twice over: the
  soft forms follow the hard extremum, and a `mix` of two slots at a `step` of exactly 0 or 1 is one of the
  two slots. Half of one name and half of another is not a name.
- **Identity costs one call per pixel, not nine per step.** `identityFragmentSpirv` composes the same march
  over `vec2 hit(vec3)` behind a `float` wrapper, so the march is not rewritten around a channel it never
  reads: eight calls to the wrapper, two to `hit`, asserted. `ShadingPoint` carries the payload so a model can
  key off identity, defaulting to `NO_PAYLOAD` where the renderer carries none.

What makes it trustworthy is that both lowerings describe the same surface — the same distance everywhere, to
f32 tolerance. The payload variant costs bindings and narrower sharing, not a different shape.

**All three B0 stages are now in.** What is left of P2 is the material half of R7, which is waiting on a
material vocabulary rather than on the channel; the channel has room for it and does not change when it
arrives.

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
| ~~P0a~~ **done** | R1, R1.1, R1.2, R8 | B0 | — |
| ~~P0b~~ **done** (the engine technique excepted) | R1 at scale | B1 | P0a (and nothing else — §0's fourth fact) |
| ~~P1~~ **done** | R2, R2.1 | B0 | — (independent of P0a; both touch `SurfaceCompiler`, so land P0a first to avoid a merge) |
| ~~P2~~ **done** (R7's material vocabulary excepted) | R6.1, R7 payload | B0 | P1 (payload rides the lowering P1 rewrites) |
| P3 | R3, R10, R12 | B1 | P1 (needs its ground truth), P2 (needs `NodeId` for attribution) |
| P4 | R4, R11 | B1 | P0a (parameter identity across swap) |
| P5 | R13 | B1 | P2 |
| P6 | R5 | B2 | P0a, P2, P5 |
| P7 | R6 pick | B1 | P2 |
| P8 | R9 | I | P0a |

**The three B0 stages — P0a, P1, P2 — come first and together.** Everything downstream is additive over them;
nothing downstream is cheap to retrofit under them. P6 in particular must not precede them, because publishing
a format over a `Surface` that lacks parameters, identity and materials converts three ordinary changes into
three format versions.
