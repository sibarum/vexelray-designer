package dev.vexelray.designer;

import dev.vexelray.surface.ParamBlock;
import dev.vexelray.technique.sdf.ParamBacking;
import dev.vexelray.technique.sdf.SdfScene;

/**
 * One compose, whole: everything the frame pump needs about the design on screen, written once.
 *
 * <p><b>What this replaces.</b> Six {@code volatile} fields on {@link Viewport} — the scene, its parameter
 * block, the push-constant size, both SPIR-V modules and which parameter road they were composed for — each
 * published separately by whichever compose finished last. Nothing made them a set, so a reader could hold a
 * fragment from one compose and a module from another, and a pipeline built from that pair is not a picture
 * of any design that was ever asked for.
 *
 * <p><b>The discipline that makes it work</b> is the same one every immutable snapshot needs, and it is worth
 * stating because it is easy to undo by accident: <b>read the reference once and pass it down</b>. A
 * {@code pump()} that read the field again in each helper it calls would have moved the tear rather than
 * removed it — the helpers would simply disagree with each other instead of the fields disagreeing among
 * themselves.
 *
 * @param scene        the scene the shaders were generated from
 * @param params       the values its parameters hold; mutable by design, and the one part of this that a
 *                     slider writes to without a recompile
 * @param pushBytes    the size of the push-constant block this scene's pipeline was built for
 * @param vertexSpirv  the vertex module
 * @param fragmentSpirv the fragment module
 * @param backing      which parameter road these modules took — the push block, or the buffer
 * @param report       what the status line says about this compose
 */
record Composed(SdfScene scene, ParamBlock params, int pushBytes, byte[] vertexSpirv, byte[] fragmentSpirv,
                ParamBacking backing, String report) {
}
