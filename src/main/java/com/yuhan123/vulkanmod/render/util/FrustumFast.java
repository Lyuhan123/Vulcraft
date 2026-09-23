package com.yuhan123.vulkanmod.render.util;

import net.minecraft.util.math.AxisAlignedBB;

/**
 * The positive-vertex AABB-vs-frustum test, attached to
 * {@code net.minecraft.client.renderer.culling.Frustum} by
 * {@code FrustumMixin}.
 *
 * <h2>What 1.12.2 does, and what it costs</h2>
 *
 * {@code Frustum} <b>does not extend</b> {@code ClippingHelper} - it wraps one
 * in a private field and holds a camera offset of its own, so the test a chunk
 * actually pays for is four calls deep before any arithmetic happens:
 *
 * <pre>
 *   RenderGlobal.setupTerrain
 *     -> ICamera.isBoundingBoxInFrustum(box)      interface dispatch
 *        -> Frustum.isBoundingBoxInFrustum(box)   virtual
 *           -> Frustum.isBoxInFrustum(6 doubles)  virtual, subtracts x/y/z
 *              -> ClippingHelper.isBoxInFrustum(6 doubles)   virtual
 *                 -> up to 8 dot() calls per plane, 6 planes
 * </pre>
 *
 * <p>That last step is the expensive one. {@code ClippingHelper.isBoxInFrustum}
 * has <b>48</b> {@code dot()} call sites - eight corners for each of six planes -
 * and short-circuits on the first dot product that is positive. {@code dot} is a
 * private four-term method with four array reads, three multiplies and three
 * adds; with 48 sites in one method it is not a good inline candidate, so the
 * loop spends most of its time in real calls. This was worth measuring before
 * rewriting, which is what {@code [VKPROF] bfsbody}'s {@code frNs} column does.
 *
 * <h2>The replacement, and why it is exactly equivalent</h2>
 *
 * For a plane {@code (n, d)} the eight corner dot products are maximised at one
 * specific corner - the one that takes {@code max} on every axis whose normal
 * component is positive, {@code min} elsewhere. That maximum is ≤ 0 <b>iff</b>
 * none of the eight corners is positive, which is exactly the condition under
 * which the vanilla loop returns {@code false} for that plane. So the whole test
 * is one dot product per plane, six in total, with no calls at all:
 *
 * <pre>{@code
 *   x = n.x > 0 ? maxX : minX    (likewise y, z)
 *   reject the box if  n.x*x + n.y*y + n.z*z + d <= 0
 * }</pre>
 *
 * <p>It is equivalent in exact arithmetic <b>and</b> in floating point. Each
 * corner's dot product is evaluated left-to-right in the same order and at the
 * same precision as {@code ClippingHelper.dot(float[], double, double, double)} -
 * the {@code float} plane entries promote to {@code double} exactly as they do
 * there - so the maximum computed here is bit-identical to the maximum the
 * eight-term loop would compute. The two cannot disagree.
 *
 * <p>{@code VULKANMOD_BFS_FRUSTUM_VERIFY=1} checks that claim on every call
 * rather than taking it on trust; "algebraically identical" is precisely the
 * class of claim this project has been wrong about before.
 *
 * <h2>Why the offset is subtracted here and not by the caller</h2>
 *
 * {@code Frustum.isBoxInFrustum} translates the box into camera-relative space
 * before handing it to the clipping helper. Doing that inside the mixin keeps
 * the fast path a drop-in replacement for
 * {@code isBoundingBoxInFrustum(box)} - one call, same argument, same answer.
 */
public interface FrustumFast {

    /**
     * {@code Frustum.isBoundingBoxInFrustum(box)}, without the call chain, the
     * eight-corner loop or the {@code dot()} calls.
     */
    boolean vulkanmod$fastBoxInFrustum(AxisAlignedBB box);
}
