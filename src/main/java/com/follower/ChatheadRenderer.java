package com.follower;

import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import net.runelite.api.Model;
import static java.lang.Math.*;

/**
 * Renders a model to an image, in software.
 *
 * <p>Chathead widgets can only show models the cache already holds - a widget
 * takes a model ID, and there is no way to hand it a composed one - so the
 * follower's own head is unreachable through the game's dialog interface. It is
 * reachable this way: the model exposes its vertices, faces and already-lit face
 * colours, so projecting and filling the triangles here produces a real picture
 * of the actual follower rather than a stand-in.
 *
 * <p>Uses the client's own widget-model camera - perspective, orbited to the
 * dialog's pitch at its zoom - with painter's-algorithm depth sorting, and fills
 * triangles through {@link GouraudRasterizer}, a faithful port of the client's
 * integer scanline rasterizer, so the pixels come out the way the game's own
 * renderer produces them - down to which pixels a two-pixel pupil covers. */
class ChatheadRenderer
{

	/**
	 * The client's own chathead camera, read off live dialog widgets.
	 *
	 * <p>The head's angle arrives as the widget's rotationZ, but it drives THIS
	 * renderer's yaw: RuneLite's rotationZ is the axis that turns a chathead in
	 * the client's widget pipeline. NPC dialogs use 1882 and player dialogs 166 -
	 * exact mirrors (1882 = 2048 - 166), which is why an NPC and your own
	 * character face opposite ways. Applying it as a true Z-roll instead tilts the
	 * head sideways in the image plane, which is visibly wrong.
	 */
	static final int GAME_PITCH = 40,
		GAME_TURN_NPC = 1882,
		GAME_TURN_PLAYER = 166,
		GAME_ZOOM = 796;

	/** The client's projection: screen = model * 512 / depth. */
	static final double FOCAL_LENGTH = 512;

	/**
	 * @param model        the (possibly posed) model to draw
	 * @param boundsFrom   model to compute framing from, or null to use
	 *                     {@code model}. An animated head is framed by its UNPOSED
	 *                     base so the picture doesn't rescale as the jaw moves.
	 * @param yaw          model rotation about the vertical axis, in JAU - the
	 *                     widget's rotationY
	 * @param cameraPitch  the widget's rotationX. NOT a model tilt: the client
	 *                     orbits the CAMERA to this pitch at {@code zoom} distance
	 * @param zoom         camera distance, the widget's modelZoom
	 * @param headFraction share of the model's height, from the top, to include;
	 *                     1.0 draws the whole model (a real chathead model)
	 * @param anchorX      pixel where the model's ORIGIN projects, or -1 for centre.
	 *                     The client anchors widget models at the widget's centre and
	 *                     lets geometry overflow, clipped by the interface surface -
	 *                     which is how tall hair gets cut at the dialog's top edge.
	 * @return the rendered image, or null if the model cannot be drawn
	 */
	static BufferedImage render(Model model, Model boundsFrom, int width, int height,
		int yaw, int cameraPitch, int zoom, double headFraction, int anchorX, int anchorY)
	{
		if (model == null || width <= 0 || height <= 0)
			return null;
		var frame = boundsFrom == null ? model : boundsFrom;

		float[] vx = model.getVerticesX(),
			vy = model.getVerticesY(),
			vz = model.getVerticesZ(),
			fx = frame.getVerticesX(),
			fy = frame.getVerticesY();
		int[] fa = model.getFaceIndices1(),
			fb = model.getFaceIndices2(),
			fc = model.getFaceIndices3(),
			colors = model.getFaceColors1();

		if (vx == null || vy == null || vz == null || fx == null || fy == null
			|| fa == null || fb == null || fc == null || colors == null)
			return null;

		int verts = min(model.getVerticesCount(), vx.length);
		if (min(frame.getVerticesCount(), fx.length) != verts)
			// A frame reference must be the same geometry (the unposed original).
			return null;

		// The client's exact widget-model camera, from its drawInterface:
		//     eyeY = sin(rotationX) * zoom;  eyeZ = cos(rotationX) * zoom
		//     objRender(0, rotationY, 0, rotationX, 0, eyeY, eyeZ)
		// So the model is yawed, pushed out to `zoom`, and viewed by a camera
		// ORBITED to pitch rotationX - with a perspective divide. Approximating
		// that as a model-space tilt with an orthographic projection is what made
		// the framing need a fudged yaw to look right.
		double yawAngle = yaw / 2048.0 * 2.0 * PI,
			yawSin = sin(yawAngle),
			yawCos = cos(yawAngle),
			pitchAngle = cameraPitch / 2048.0 * 2.0 * PI,
			pitchSin = sin(pitchAngle),
			pitchCos = cos(pitchAngle),
			eyeY = pitchSin * zoom,
			eyeZ = pitchCos * zoom;

		// The client's own projection scale, not a fit-to-box. Widget models are
		// drawn at a fixed focal length about the model's ORIGIN, and they
		// overflow their widget rectangle freely - the real chathead widget is
		// 32x32 while the head it draws is far bigger. Fitting to a box instead
		// made size a guess; this makes it a consequence of zoom, exactly as in
		// game.
		int offsetX = anchorX < 0 ? width / 2 : anchorX,
			offsetY = anchorY < 0 ? height / 2 : anchorY;
		// vertexScreenZ, the client's convention: camera depth relative to the
		// model origin's depth (midZ), an INTEGER per vertex.
		double midZ = eyeY * pitchSin + eyeZ * pitchCos;

		float[] rz = new float[verts];
		int[] sx = new int[verts],
			sy = new int[verts],
			sz = new int[verts];
		float minY = Float.MAX_VALUE,
			maxY = -Float.MAX_VALUE;
		// The client's calculateBoundsCylinder, for the depth-bucket range.
		double boundMaxY = 0,
			boundMinY = 0,
			radiusSqr = 0;
		for (int i = 0; i < verts; i++)
		{
			// One vertex through the client's widget-model transform: yaw the
			// model, push it to the camera distance, orbit the camera to its
			// pitch, then divide by depth.
			double x = vz[i] * yawSin + vx[i] * yawCos,
				z = vz[i] * yawCos - vx[i] * yawSin + eyeZ,
				y = vy[i] + eyeY,
				cy = y * pitchCos - z * pitchSin,
				cz = y * pitchSin + z * pitchCos;
			// Behind or level with the camera: park it far away rather than
			// divide by zero; such faces are dropped by the depth guard below.
			boolean behind = cz < 1;
			rz[i] = behind ? -1 : (float) cz;
			// Screen coordinates the client's way: vertexScreenX = centerX +
			// (x << 9) / z, truncated to an INTEGER per vertex before any face
			// is drawn. The rasterizer walks scanlines between these ints;
			// feeding it float pixel centres instead dropped the sub-pixel eye
			// faces.
			sx[i] = offsetX + (int) ((behind ? 0 : (float) (x / cz)) * FOCAL_LENGTH);
			sy[i] = offsetY + (int) ((behind ? 0 : (float) (cy / cz)) * FOCAL_LENGTH);
			sz[i] = (int) (rz[i] - midZ);

			minY = min(minY, fy[i]);
			maxY = max(maxY, fy[i]);
			boundMaxY = max(boundMaxY, -vy[i]);
			boundMinY = max(boundMinY, vy[i]);
			radiusSqr = max(radiusSqr, vx[i] * vx[i] + vz[i] * vz[i]);
		}
		float cutoff = minY + (maxY - minY) * (float) headFraction;
		int radius = (int) (sqrt(radiusSqr) + 0.99),
			minDepth = (int) (sqrt(radius * radius + boundMaxY * boundMaxY) + 0.99),
			maxDepth = minDepth + (int) (sqrt(radius * radius + boundMinY * boundMinY) + 0.99);

		// Gouraud shading, the way the game itself draws models: each face carries
		// three per-corner lit colours (faceColors1/2/3), blended across the
		// triangle. Flat-filling with colours1 alone is what made every triangle
		// edge visible and the lighting angular. No antialiasing either - the
		// game's rasterizer has none, and crisp edges are part of the look.
		int[] colors2 = model.getFaceColors2(),
			colors3 = model.getFaceColors3();

		// Faces wholly inside the head region OF THE FRAME model, skipping ones
		// the client hides. Using the frame keeps face selection stable across
		// animation frames.
		//
		// The client's draw2 ordering, not a painter's sort. Faces get INTEGER
		// average depths ((zA+zB+zC)/3 + minDepth) and draw far to near, in
		// face-index order within one depth. Model authors rely on that: a pupil
		// sits coplanar ON the eye-white face and is authored LATER in face order
		// so the same depth draws it on top. Sorting by exact float depth instead
		// let the eye-white face land on top of the pupil on some animation
		// frames - the stray white pixels in the eyes.
		//
		// Backface culling happens before that, with the client's own test on the
		// same INTEGER screen coordinates it rasterises with. Without it the BACK
		// of the head - lit from behind, darker - bled through along cheeks and
		// brow as triangular patches.
		boolean anyInHead = false;
		int[] depth = new int[fa.length];
		List<Integer> faces = new ArrayList<>();
		for (int i = 0; i < fa.length; i++)
		{
			int a = fa[i],
				b = fb[i],
				c = fc[i];
			if ((colors3 != null && i < colors3.length && colors3[i] == -2)
				|| a >= verts || b >= verts || c >= verts || a < 0 || b < 0 || c < 0
				|| !(fy[a] <= cutoff && fy[b] <= cutoff && fy[c] <= cutoff))
				continue;
			anyInHead = true;
			if (rz[a] < 0 || rz[b] < 0 || rz[c] < 0
				|| (sx[a] - sx[b]) * (sy[c] - sy[b]) - (sy[a] - sy[b]) * (sx[c] - sx[b]) <= 0)
				continue;
			depth[i] = max(0, min(maxDepth, (sz[a] + sz[b] + sz[c]) / 3 + minDepth));
			faces.add(i);
		}
		if (!anyInHead)
			return null;
		// Stable, so faces at one depth keep their face-index order.
		faces.sort((p, q) -> depth[q] - depth[p]);

		int[] pixels = new int[width * height];
		var raster = new GouraudRasterizer(pixels, width, height);

		for (int face : resolveDrawOrder(model.getFaceRenderPriorities(), faces, depth))
		{
			int a = fa[face],
				b = fb[face],
				c = fc[face];

			// colours3 == -1 flags a flat-shaded face: all corners take colours1.
			// Corners stay PACKED - the client interpolates the palette index
			// across the triangle and looks the table up per pixel, and matching
			// its colours means doing the same. Corner colours pair with vertices
			// A/B/C directly, exactly as the client's drawFace passes them.
			boolean flat = colors3 == null || colors3[face] == -1;
			int colourA = colors[face] & 0xFFFF,
				colourB = flat || colors2 == null ? colourA : colors2[face] & 0xFFFF,
				colourC = flat ? colourA : colors3[face] & 0xFFFF;

			raster.gouraudTriangle(sx[a], sx[b], sx[c], sy[a], sy[b], sy[c],
				colourA, colourB, colourC);
		}

		var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
		image.setRGB(0, 0, width, height, pixels, 0, width);
		return image;
	}

	/**
	 * The client's draw2 emission order. Without per-face render priorities:
	 * far to near, as sorted. With priorities: the client's 12-class algorithm
	 * - faces collect into priority classes in depth order, classes 0..9 emit
	 * in class order, and the special classes 10 and 11 (all of 10, then all of
	 * 11) cut into the stream whenever their next face is deeper than the
	 * running depth averages of classes 1+2 (checked before class 0), 3+4
	 * (before class 3), and 6+8 (before class 5), with the leftovers emitted at
	 * the end. Player and NPC models rely on this to layer coplanar detail
	 * correctly.
	 */
	static List<Integer> resolveDrawOrder(byte[] priorities, List<Integer> faces, int[] depth)
	{
		if (priorities == null)
			return faces;

		int[][] classes = new int[12][faces.size()];
		int[] counts = new int[12],
			depthSums = new int[12];
		for (int face : faces)
		{
			int priorityClass = max(0, min(11, priorities[face]));
			classes[priorityClass][counts[priorityClass]++] = face;
			depthSums[priorityClass] += depth[face];
		}
		int[] special = Arrays.copyOf(classes[10], counts[10] + counts[11]);
		System.arraycopy(classes[11], 0, special, counts[10], counts[11]);

		List<Integer> order = new ArrayList<>(faces.size());
		int next = 0;
		for (int priority = 0; priority <= 10; priority++)
		{
			int deeperThan = priority == 0 ? average(counts, depthSums, 1, 2)
				: priority == 3 ? average(counts, depthSums, 3, 4)
				: priority == 5 ? average(counts, depthSums, 6, 8)
				: priority == 10 ? -1 : Integer.MAX_VALUE;
			while (next < special.length && depth[special[next]] > deeperThan)
				order.add(special[next++]);
			for (int i = 0; priority < 10 && i < counts[priority]; i++)
				order.add(classes[priority][i]);
		}
		return order;
	}

	/** The running depth average of two priority classes, 0 when both are empty. */
	static int average(int[] counts, int[] depthSums, int first, int second)
	{
		int count = counts[first] + counts[second];
		return count > 0 ? (depthSums[first] + depthSums[second]) / count : 0;
	}

}
