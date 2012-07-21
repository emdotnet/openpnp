/*
 	Copyright (C) 2011 Jason von Nieda <jason@vonnieda.org>
 	
 	This file is part of OpenPnP.
 	
	OpenPnP is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    OpenPnP is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with OpenPnP.  If not, see <http://www.gnu.org/licenses/>.
 	
 	For more information about OpenPnP visit http://openpnp.org
 */

package org.openpnp.gui.components;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.ComponentListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionAdapter;
import java.awt.event.MouseMotionListener;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.prefs.Preferences;

import javax.swing.JComponent;
import javax.swing.JPopupMenu;

import org.openpnp.CameraListener;
import org.openpnp.gui.components.reticle.Reticle;
import org.openpnp.model.Outline;
import org.openpnp.model.Point;
import org.openpnp.spi.Camera;
import org.openpnp.util.Utils2D;
import org.openpnp.util.XmlSerialize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@SuppressWarnings("serial")
// TODO: Handle camera rotation.
// TODO: Probably need to give some serious thought to rounding and
// truncation in the selection stuff. Probably doing a lot of off by one.
public class CameraView extends JComponent implements CameraListener {
	private final static Logger logger = LoggerFactory
			.getLogger(CameraView.class);
	private static final String PREF_RETICLE = "CamerView.reticle";

	private final static int HANDLE_DIAMETER = 8;

	private enum HandlePosition {
		NW, N, NE, E, SE, S, SW, W
	}

	private enum SelectionMode {
		Resizing, Moving, Creating
	}

	/**
	 * The Camera we are viewing.
	 */
	private Camera camera;

	/**
	 * The last frame received, reported by the Camera.
	 */
	private BufferedImage lastFrame;

	/**
	 * The maximum frames per second that we'll display.
	 */
	private int maximumFps;

	/**
	 * A Reticle to render over the image. Nothing is rendered if this is null.
	 */
	private Reticle reticle;

	private JPopupMenu popupMenu;

	/**
	 * The last width and height of the component that we painted for. If the
	 * width or height is different from these values at the start of paint
	 * we'll recalculate all the scaling data.
	 */
	private double lastWidth, lastHeight;

	/**
	 * The last width and height of the image that we painted for. If the width
	 * or height is different from these values at the start of paint we'll
	 * recalculate all the scaling data.
	 */
	private double lastSourceWidth, lastSourceHeight;

	/**
	 * The width and height of the image after it has been scaled to fit the
	 * bounds of the component.
	 */
	private int scaledWidth, scaledHeight;

	/**
	 * The ratio of scaled width and height to unscaled width and height.
	 * scaledWidth * scaleRatioX = sourceWidth. scaleRatioX = sourceWidth /
	 * scaledWidth
	 */
	private double scaleRatioX, scaleRatioY;

	/**
	 * The Camera's units per pixel scaled at the same ratio as the image. That
	 * is, each pixel in the scaled image is scaledUnitsPerPixelX wide and
	 * scaledUnitsPerPixelY high.
	 */
	private double scaledUnitsPerPixelX, scaledUnitsPerPixelY;

	/**
	 * The top left position within the component at which the scaled image can
	 * be drawn for it to be centered.
	 */
	private int imageX, imageY;

	private boolean selectionEnabled;
	/**
	 * Rectangle describing the bounds of the selection in image coordinates.
	 */
	private Rectangle selection;
	/**
	 * The scaled version of the selection Rectangle. Rescaled any time the
	 * component's size is changed.
	 */
	private Rectangle selectionScaled;
	private SelectionMode selectionMode;
	private HandlePosition selectionActiveHandle;
	private int selectionStartX, selectionStartY;
	private float selectionFlashOpacity;
	private float selectionDashPhase;
	private static float[] selectionDashProfile = new float[] { 6f, 6f };
	// 11 is the sum of the dash lengths minus 1.
	private static float selectionDashPhaseStart = 11f;

	private CameraViewSelectionTextDelegate selectionTextDelegate;

	private ScheduledExecutorService scheduledExecutor;

	private Preferences prefs = Preferences
			.userNodeForPackage(CameraView.class);

	private Outline outline;

	public CameraView() {
		setBackground(Color.black);
		setOpaque(true);

		String reticlePref = prefs.get(PREF_RETICLE, null);
		try {
			Reticle reticle = (Reticle) XmlSerialize.deserialize(reticlePref);
			setReticle(reticle);
		}
		catch (Exception e) {
			// logger.warn("Warning: Unable to load Reticle preference");
		}

		popupMenu = new CameraViewPopupMenu(this);

		addMouseListener(mouseListener);
		addMouseMotionListener(mouseMotionListener);
		addComponentListener(componentListener);

		scheduledExecutor = Executors.newSingleThreadScheduledExecutor();

		// TODO: Cancel this when it's not being used instead of spinning,
		// or maybe create a real thread and wait().
		scheduledExecutor.scheduleAtFixedRate(new Runnable() {
			public void run() {
				if (selectionEnabled && selection != null) {
					// Adjust the dash phase so the line marches on the next
					// paint
					selectionDashPhase -= 1f;
					if (selectionDashPhase < 0) {
						selectionDashPhase = selectionDashPhaseStart;
					}
					repaint();
				}
			}
		}, 0, 50, TimeUnit.MILLISECONDS);
	}

	public CameraView(int maximumFps) {
		this();
		setMaximumFps(maximumFps);
	}

	public void setOutline(Outline outline) {
		this.outline = outline;
	}

	public void setMaximumFps(int maximumFps) {
		this.maximumFps = maximumFps;
		// turn off capture for the camera we are replacing, if any
		if (this.camera != null) {
			this.camera.stopContinuousCapture(this);
		}
		// turn on capture for the new camera
		if (this.camera != null) {
			this.camera.startContinuousCapture(this, maximumFps);
		}
	}

	public int getMaximumFps() {
		return maximumFps;
	}

	public void setCamera(Camera camera) {
		// turn off capture for the camera we are replacing, if any
		if (this.camera != null) {
			this.camera.stopContinuousCapture(this);
		}
		this.camera = camera;
		// turn on capture for the new camera
		if (this.camera != null) {
			this.camera.startContinuousCapture(this, maximumFps);
		}
	}

	public Camera getCamera() {
		return camera;
	}

	public void setReticle(Reticle reticle) {
		this.reticle = reticle;

		prefs.put(PREF_RETICLE, XmlSerialize.serialize(reticle));
		try {
			prefs.flush();
		}
		catch (Exception e) {

		}
	}

	public CameraViewSelectionTextDelegate getSelectionTextDelegate() {
		return selectionTextDelegate;
	}

	public void setSelectionTextDelegate(
			CameraViewSelectionTextDelegate selectionTextDelegate) {
		this.selectionTextDelegate = selectionTextDelegate;
	}

	public BufferedImage captureSelectionImage() {
		if (selection == null || lastFrame == null) {
			return null;
		}

		selectionFlashOpacity = 1.0f;

		ScheduledFuture future = scheduledExecutor.scheduleAtFixedRate(
				new Runnable() {
					public void run() {
						if (selectionFlashOpacity > 0) {
							selectionFlashOpacity -= 0.07;
							selectionFlashOpacity = Math.max(0,
									selectionFlashOpacity);
							repaint();
						}
						else {
							throw new RuntimeException();
						}
					}
				}, 0, 30, TimeUnit.MILLISECONDS);

		int sx = selection.x;
		int sy = selection.y;
		int sw = selection.width;
		int sh = selection.height;

		BufferedImage image = new BufferedImage(sw, sh,
				BufferedImage.TYPE_INT_ARGB);
		Graphics g = image.getGraphics();
		g.drawImage(lastFrame, 0, 0, sw, sh, sx, sy, sx + sw, sy + sh, null);
		g.dispose();

		while (!future.isDone())
			;

		return image;
	}

	public Rectangle getSelection() {
		return selection;
	}

	public Reticle getReticle() {
		return reticle;
	}

	@Override
	public void frameReceived(BufferedImage img) {
		BufferedImage oldFrame = lastFrame;
		lastFrame = img;
		if (oldFrame == null
				|| (oldFrame.getWidth() != img.getWidth() || oldFrame
						.getHeight() != img.getHeight())) {
			calculateScalingData();
		}
		repaint();
	}

	/**
	 * Calculates a bunch of scaling data that we cache to speed up painting.
	 * This is recalculated when the size of the component or the size of the
	 * source changes. This method is synchronized, along with paintComponent()
	 * so that the updates to the cached data are atomic.
	 */
	private synchronized void calculateScalingData() {
		BufferedImage image = lastFrame;

		if (image == null) {
			return;
		}

		Insets ins = getInsets();
		int width = getWidth() - ins.left - ins.right;
		int height = getHeight() - ins.top - ins.bottom;

		double destWidth = width, destHeight = height;

		lastWidth = width;
		lastHeight = height;

		lastSourceWidth = image.getWidth();
		lastSourceHeight = image.getHeight();

		double heightRatio = lastSourceHeight / destHeight;
		double widthRatio = lastSourceWidth / destWidth;

		if (heightRatio > widthRatio) {
			double aspectRatio = lastSourceWidth / lastSourceHeight;
			scaledHeight = (int) destHeight;
			scaledWidth = (int) (scaledHeight * aspectRatio);
		}
		else {
			double aspectRatio = lastSourceHeight / lastSourceWidth;
			scaledWidth = (int) destWidth;
			scaledHeight = (int) (scaledWidth * aspectRatio);
		}

		imageX = ins.left + (width / 2) - (scaledWidth / 2);
		imageY = ins.top + (height / 2) - (scaledHeight / 2);

		scaleRatioX = lastSourceWidth / (double) scaledWidth;
		scaleRatioY = lastSourceHeight / (double) scaledHeight;

		scaledUnitsPerPixelX = camera.getUnitsPerPixel().getX() * scaleRatioX;
		scaledUnitsPerPixelY = camera.getUnitsPerPixel().getY() * scaleRatioY;

		if (selectionEnabled && selection != null) {
			// setSelection() handles updating the scaled rectangle
			setSelection(selection);
		}
	}

	@Override
	protected synchronized void paintComponent(Graphics g) {
		super.paintComponent(g);
		BufferedImage image = lastFrame;
		Insets ins = getInsets();
		int width = getWidth() - ins.left - ins.right;
		int height = getHeight() - ins.top - ins.bottom;
		Graphics2D g2d = (Graphics2D) g;
		g.setColor(getBackground());
		g2d.fillRect(ins.left, ins.top, width, height);
		if (image != null) {
			// Only render if there is a valid image.
			g2d.drawImage(lastFrame, imageX, imageY, scaledWidth, scaledHeight,
					null);

			if (reticle != null) {
				reticle.draw(g2d, camera.getUnitsPerPixel().getUnits(),
						scaledUnitsPerPixelX, scaledUnitsPerPixelY, ins.left
								+ (width / 2), ins.top + (height / 2),
						scaledWidth, scaledHeight);
			}

			if (selectionEnabled && selection != null) {
				paintSelection(g2d);
			}

			if (outline != null) {
				paintOutline(g2d);
			}

		}
		else {
			g.setColor(Color.red);
			g.drawLine(ins.left, ins.top, ins.right, ins.bottom);
			g.drawLine(ins.right, ins.top, ins.left, ins.bottom);
		}
	}

	private void paintOutline(Graphics2D g2d) {
		g2d.setStroke(new BasicStroke(1f));
		g2d.setColor(Color.red);
		
		double c = camera.getHead().getC();
		
		// Convert the outline to the camera's units per pixel
		Outline outline = this.outline.convertToUnits(camera.getUnitsPerPixel()
				.getUnits());
		
		// Get width and height of the component
		Insets ins = getInsets();
		int width = getWidth() - ins.left - ins.right;
		int height = getHeight() - ins.top - ins.bottom;
		int cx = width / 2;
		int cy = height / 2;

		// Rotate to the head's rotation and scale to fit the window
		outline = Utils2D.rotateTranslateScaleOutline(outline, c, 0, 0, 1.0 / scaledUnitsPerPixelX, 1.0 / scaledUnitsPerPixelY);
		
		// Draw it
		for (int i = 0; i < outline.getPoints().size() - 1; i++) {
			Point p1 = outline.getPoints().get(i);
			Point p2 = outline.getPoints().get(i + 1);

			g2d.drawLine(
					(int) (p1.getX() + cx), 
					(int) (p1.getY() + cy), 
					(int) (p2.getX() + cx),
					(int) (p2.getY() + cy));
		}

		Point p1 = outline.getPoints().get(outline.getPoints().size() - 1);
		Point p2 = outline.getPoints().get(0);

		g2d.drawLine(
				(int) (p1.getX() + cx), 
				(int) (p1.getY() + cy), 
				(int) (p2.getX() + cx),
				(int) (p2.getY() + cy));
		
		// Draw a crosshair with the north line being green and the rest red.
		
		Point p;
		g2d.setColor(Color.red);
		p = new Point(width / 8, 0);
		p = Utils2D.rotatePoint(p, c);
		g2d.drawLine(cx, cy, (int) (cx + p.getX()), (int) (cy + p.getY()));
		g2d.drawLine(cx, cy, (int) (cx - p.getX()), (int) (cy - p.getY()));
		
		p = new Point(0, height / 8);
		p = Utils2D.rotatePoint(p, c);
		g2d.drawLine(cx, cy, (int) (cx + p.getX()), (int) (cy + p.getY()));
		g2d.setColor(Color.green);
		g2d.drawLine(cx, cy, (int) (cx - p.getX()), (int) (cy - p.getY()));
	}

	private void paintSelection(Graphics2D g2d) {
		int rx = selectionScaled.x;
		int ry = selectionScaled.y;
		int rw = selectionScaled.width;
		int rh = selectionScaled.height;
		int rx2 = rx + rw;
		int ry2 = ry + rh;
		int rxc = rx + rw / 2;
		int ryc = ry + rh / 2;

		g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
				RenderingHints.VALUE_ANTIALIAS_ON);
		// Draw the dashed rectangle, black background, white dashes
		g2d.setColor(Color.black);
		g2d.setStroke(new BasicStroke(1f));
		g2d.drawRect(rx, ry, rw, rh);
		g2d.setColor(Color.white);
		g2d.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT,
				BasicStroke.JOIN_BEVEL, 0, selectionDashProfile,
				selectionDashPhase));
		g2d.drawRect(rx, ry, rw, rh);

		if (selectionMode != SelectionMode.Creating) {
			// If we're not drawing a whole new rectangle, draw the
			// handles for the existing one.
			drawHandle(g2d, rx, ry);
			drawHandle(g2d, rx2, ry);
			drawHandle(g2d, rx, ry2);
			drawHandle(g2d, rx2, ry2);

			drawHandle(g2d, rxc, ry);
			drawHandle(g2d, rx2, ryc);
			drawHandle(g2d, rxc, ry2);
			drawHandle(g2d, rx, ryc);
		}

		if (selectionTextDelegate != null) {
			String text = selectionTextDelegate.getSelectionText(this);
			if (text != null) {
				// TODO: Be awesome like Apple and move the overlay inside
				// the rect if it goes past the edge of the window
				drawTextOverlay(g2d, (int) (rx + rw + 6), (int) (ry + rh + 6),
						text);
			}
		}

		if (selectionFlashOpacity > 0) {
			g2d.setColor(new Color(1.0f, 1.0f, 1.0f, selectionFlashOpacity));
			g2d.fillRect(rx, ry, rw, rh);
		}
	}

	/**
	 * Draws a standard handle centered on the given x and y position.
	 * 
	 * @param g2d
	 * @param x
	 * @param y
	 */
	private static void drawHandle(Graphics2D g2d, int x, int y) {
		g2d.setStroke(new BasicStroke(1f));
		g2d.setColor(new Color(153, 153, 187));
		g2d.fillArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2,
				HANDLE_DIAMETER, HANDLE_DIAMETER, 0, 360);
		g2d.setColor(Color.white);
		g2d.drawArc(x - HANDLE_DIAMETER / 2, y - HANDLE_DIAMETER / 2,
				HANDLE_DIAMETER, HANDLE_DIAMETER, 0, 360);
	}

	/**
	 * Gets the HandlePosition, if any, at the given x and y. Returns null if
	 * there is not a handle at that position.
	 * 
	 * @param x
	 * @param y
	 * @return
	 */
	private HandlePosition getSelectionHandleAtPosition(int x, int y) {
		if (selection == null) {
			return null;
		}

		int rx = selectionScaled.x;
		int ry = selectionScaled.y;
		int rw = selectionScaled.width;
		int rh = selectionScaled.height;
		int rx2 = rx + rw;
		int ry2 = ry + rh;
		int rxc = rx + rw / 2;
		int ryc = ry + rh / 2;

		if (isWithinHandle(x, y, rx, ry)) {
			return HandlePosition.NW;
		}
		else if (isWithinHandle(x, y, rx2, ry)) {
			return HandlePosition.NE;
		}
		else if (isWithinHandle(x, y, rx, ry2)) {
			return HandlePosition.SW;
		}
		else if (isWithinHandle(x, y, rx2, ry2)) {
			return HandlePosition.SE;
		}
		else if (isWithinHandle(x, y, rxc, ry)) {
			return HandlePosition.N;
		}
		else if (isWithinHandle(x, y, rx2, ryc)) {
			return HandlePosition.E;
		}
		else if (isWithinHandle(x, y, rxc, ry2)) {
			return HandlePosition.S;
		}
		else if (isWithinHandle(x, y, rx, ryc)) {
			return HandlePosition.W;
		}
		return null;
	}

	/**
	 * A specialization of isWithin() that uses uses the bounding box of a
	 * handle.
	 * 
	 * @param x
	 * @param y
	 * @param handleX
	 * @param handleY
	 * @return
	 */
	private static boolean isWithinHandle(int x, int y, int handleX, int handleY) {
		return isWithin(x, y, handleX - 4, handleY - 4, 8, 8);
	}

	private static boolean isWithin(int pointX, int pointY, int boundsX,
			int boundsY, int boundsWidth, int boundsHeight) {
		return pointX >= boundsX && pointX <= (boundsX + boundsWidth)
				&& pointY >= boundsY && pointY <= (boundsY + boundsHeight);
	}

	private static Rectangle normalizeRectangle(Rectangle r) {
		return normalizeRectangle(r.x, r.y, r.width, r.height);
	}

	/**
	 * Builds a rectangle with the given parameters. If the width or height is
	 * negative the corresponding x or y value is modified and the width or
	 * height is made positive.
	 * 
	 * @param x
	 * @param y
	 * @param width
	 * @param height
	 * @return
	 */
	private static Rectangle normalizeRectangle(int x, int y, int width,
			int height) {
		if (width < 0) {
			width *= -1;
			x -= width;
		}
		if (height < 0) {
			height *= -1;
			y -= height;
		}
		return new Rectangle(x, y, width, height);
	}

	/**
	 * Draws text in a nice bubble at the given position. Newline characters in
	 * the text cause line breaks.
	 * 
	 * @param g2d
	 * @param topLeftX
	 * @param topLeftY
	 * @param text
	 */
	private static void drawTextOverlay(Graphics2D g2d, int topLeftX,
			int topLeftY, String text) {
		g2d.setStroke(new BasicStroke(1.0f));
		g2d.setFont(g2d.getFont().deriveFont(10.0f));
		String[] lines = text.split("\n");
		List<TextLayout> textLayouts = new ArrayList<TextLayout>();
		int textWidth = 0, textHeight = 0;
		for (String line : lines) {
			TextLayout textLayout = new TextLayout(line, g2d.getFont(),
					g2d.getFontRenderContext());
			textWidth = (int) Math.max(textWidth, textLayout.getBounds()
					.getWidth());
			textHeight += (int) textLayout.getBounds().getHeight() + 4;
			textLayouts.add(textLayout);
		}
		g2d.setColor(new Color(0, 0, 0, 0.75f));
		g2d.fillRoundRect(topLeftX, topLeftY, textWidth + 10, textHeight + 10,
				6, 6);
		g2d.setColor(Color.white);
		g2d.drawRoundRect(topLeftX, topLeftY, textWidth + 10, textHeight + 10,
				6, 6);
		int yPen = topLeftY + 5;
		for (TextLayout textLayout : textLayouts) {
			yPen += textLayout.getBounds().getHeight();
			textLayout.draw(g2d, topLeftX + 5, yPen);
			yPen += 4;
		}
	}

	/**
	 * Changes the HandlePosition to it's inverse if the given rectangle has a
	 * negative width, height or both.
	 * 
	 * @param r
	 */
	private static HandlePosition getOpposingHandle(Rectangle r,
			HandlePosition handlePosition) {
		if (r.getWidth() < 0 && r.getHeight() < 0) {
			if (handlePosition == HandlePosition.NW) {
				return HandlePosition.SE;
			}
			else if (handlePosition == HandlePosition.NE) {
				return HandlePosition.SW;
			}
			else if (handlePosition == HandlePosition.SE) {
				return HandlePosition.NW;
			}
			else if (handlePosition == HandlePosition.SW) {
				return HandlePosition.NE;
			}
		}
		else if (r.getWidth() < 0) {
			if (handlePosition == HandlePosition.NW) {
				return HandlePosition.NE;
			}
			else if (handlePosition == HandlePosition.NE) {
				return HandlePosition.NW;
			}
			else if (handlePosition == HandlePosition.SE) {
				return HandlePosition.SW;
			}
			else if (handlePosition == HandlePosition.SW) {
				return HandlePosition.SE;
			}
			else if (handlePosition == HandlePosition.E) {
				return HandlePosition.W;
			}
			else if (handlePosition == HandlePosition.W) {
				return HandlePosition.E;
			}
		}
		else if (r.getHeight() < 0) {
			if (handlePosition == HandlePosition.SW) {
				return HandlePosition.NW;
			}
			else if (handlePosition == HandlePosition.SE) {
				return HandlePosition.NE;
			}
			else if (handlePosition == HandlePosition.NW) {
				return HandlePosition.SW;
			}
			else if (handlePosition == HandlePosition.NE) {
				return HandlePosition.SE;
			}
			else if (handlePosition == HandlePosition.S) {
				return HandlePosition.N;
			}
			else if (handlePosition == HandlePosition.N) {
				return HandlePosition.S;
			}
		}
		return handlePosition;
	}

	/**
	 * Set the selection rectangle in image coordinates.
	 * 
	 * @param x
	 * @param y
	 * @param width
	 * @param height
	 */
	public void setSelection(int x, int y, int width, int height) {
		setSelection(new Rectangle(x, y, width, height));
	}

	/**
	 * Set the selection rectangle in image coordinates.
	 * 
	 * @param r
	 */
	public void setSelection(Rectangle r) {
		if (r == null) {
			selection = null;
			selectionMode = null;
		}
		else {
			selectionActiveHandle = getOpposingHandle(r, selectionActiveHandle);
			selection = normalizeRectangle(r);

			int rx = (int) (imageX + selection.x / scaleRatioX);
			int ry = (int) (imageY + selection.y / scaleRatioY);
			int rw = (int) (selection.width / scaleRatioX);
			int rh = (int) (selection.height / scaleRatioY);
			selectionScaled = new Rectangle(rx, ry, rw, rh);
		}
	}

	/**
	 * Set the selection rectangle in component coordinates. Updates the
	 * selection property with the properly scaled coordinates.
	 * 
	 * @param x
	 * @param y
	 * @param width
	 * @param height
	 */
	private void setScaledSelection(int x, int y, int width, int height) {
		selectionScaled = new Rectangle(x, y, width, height);
		selectionActiveHandle = getOpposingHandle(selectionScaled,
				selectionActiveHandle);
		selectionScaled = normalizeRectangle(selectionScaled);

		int rx = (int) ((x - imageX) * scaleRatioX);
		int ry = (int) ((y - imageY) * scaleRatioY);
		int rw = (int) (width * scaleRatioX);
		int rh = (int) (height * scaleRatioY);

		selection = new Rectangle(rx, ry, rw, rh);
	}

	public boolean isSelectionEnabled() {
		return selectionEnabled;
	}

	public void setSelectionEnabled(boolean selectionEnabled) {
		this.selectionEnabled = selectionEnabled;
	}

	public static Cursor getCursorForHandlePosition(
			HandlePosition handlePosition) {
		switch (handlePosition) {
		case NW:
			return Cursor.getPredefinedCursor(Cursor.NW_RESIZE_CURSOR);
		case N:
			return Cursor.getPredefinedCursor(Cursor.N_RESIZE_CURSOR);
		case NE:
			return Cursor.getPredefinedCursor(Cursor.NE_RESIZE_CURSOR);
		case E:
			return Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR);
		case SE:
			return Cursor.getPredefinedCursor(Cursor.SE_RESIZE_CURSOR);
		case S:
			return Cursor.getPredefinedCursor(Cursor.S_RESIZE_CURSOR);
		case SW:
			return Cursor.getPredefinedCursor(Cursor.SW_RESIZE_CURSOR);
		case W:
			return Cursor.getPredefinedCursor(Cursor.W_RESIZE_CURSOR);
		}
		return null;
	}

	/**
	 * Updates the Cursor to reflect the current state of the component.
	 */
	private void updateCursor() {
		if (selectionEnabled) {
			if (selectionMode == SelectionMode.Moving) {
				setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
			}
			else if (selectionMode == SelectionMode.Resizing) {
				setCursor(getCursorForHandlePosition(selectionActiveHandle));
			}
			else if (selectionMode == null && selection != null) {
				int x = getMousePosition().x;
				int y = getMousePosition().y;

				HandlePosition handlePosition = getSelectionHandleAtPosition(x,
						y);
				if (handlePosition != null) {
					setCursor(getCursorForHandlePosition(handlePosition));
				}
				else if (selectionScaled.contains(x, y)) {
					setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
				}
				else {
					setCursor(Cursor
							.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
				}
			}
			else {
				setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
			}
		}
		else {
			setCursor(Cursor.getDefaultCursor());
		}
	}

	private MouseListener mouseListener = new MouseAdapter() {
		@Override
		public void mouseClicked(MouseEvent e) {
			popupMenu.show(e.getComponent(), e.getX(), e.getY());
		}

		@Override
		public void mousePressed(MouseEvent e) {
			int x = e.getX();
			int y = e.getY();

			if (selectionEnabled) {
				// If we're not doing anything currently, we can start
				// a new operation.
				if (selectionMode == null) {
					// See if there is a handle under the cursor.
					HandlePosition handlePosition = getSelectionHandleAtPosition(
							x, y);
					if (handlePosition != null) {
						selectionMode = SelectionMode.Resizing;
						selectionActiveHandle = handlePosition;
					}
					// If not, perhaps they want to move the rectangle
					else if (selection != null
							&& selectionScaled.contains(x, y)) {

						selectionMode = SelectionMode.Moving;
						// Store the distance between the rectangle's origin and
						// where they started moving it from.
						selectionStartX = x - selectionScaled.x;
						selectionStartY = y - selectionScaled.y;
					}
					// If not those, it's time to create a rectangle
					else {
						selectionMode = SelectionMode.Creating;
						selectionStartX = x;
						selectionStartY = y;
					}
				}
			}
		}

		@Override
		public void mouseReleased(MouseEvent arg0) {
			selectionMode = null;
			selectionActiveHandle = null;
		}
	};

	private MouseMotionListener mouseMotionListener = new MouseMotionAdapter() {
		@Override
		public void mouseMoved(MouseEvent e) {
			updateCursor();
		}

		@Override
		public void mouseDragged(MouseEvent e) {
			if (selectionEnabled) {
				int x = e.getX();
				int y = e.getY();

				if (selectionMode == SelectionMode.Resizing) {
					int rx = selectionScaled.x;
					int ry = selectionScaled.y;
					int rw = selectionScaled.width;
					int rh = selectionScaled.height;

					if (selectionActiveHandle == HandlePosition.NW) {
						setScaledSelection(x, y, (rw - (x - rx)),
								(rh - (y - ry)));
					}
					else if (selectionActiveHandle == HandlePosition.NE) {
						setScaledSelection(rx, y, x - rx, (rh - (y - ry)));
					}
					else if (selectionActiveHandle == HandlePosition.N) {
						setScaledSelection(rx, y, rw, (rh - (y - ry)));
					}
					else if (selectionActiveHandle == HandlePosition.E) {
						setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh);
					}
					else if (selectionActiveHandle == HandlePosition.SE) {
						setScaledSelection(rx, ry, rw + (x - (rx + rw)), rh
								+ (y - (ry + rh)));
					}
					else if (selectionActiveHandle == HandlePosition.S) {
						setScaledSelection(rx, ry, rw, rh + (y - (ry + rh)));
					}
					else if (selectionActiveHandle == HandlePosition.SW) {
						setScaledSelection(x, ry, (rw - (x - rx)), rh
								+ (y - (ry + rh)));
					}
					else if (selectionActiveHandle == HandlePosition.W) {
						setScaledSelection(x, ry, (rw - (x - rx)), rh);
					}
				}
				else if (selectionMode == SelectionMode.Moving) {
					setScaledSelection(x - selectionStartX,
							y - selectionStartY, selectionScaled.width,
							selectionScaled.height);
				}
				else if (selectionMode == SelectionMode.Creating) {
					int sx = selectionStartX;
					int sy = selectionStartY;
					int w = x - sx;
					int h = y - sy;
					setScaledSelection(sx, sy, w, h);
				}
				updateCursor();
				repaint();
			}
		}
	};

	private ComponentListener componentListener = new ComponentAdapter() {
		@Override
		public void componentResized(ComponentEvent e) {
			calculateScalingData();
		}
	};

	public CameraViewSelectionTextDelegate pixelsAndUnitsTextSelectionDelegate = new CameraViewSelectionTextDelegate() {
		@Override
		public String getSelectionText(CameraView cameraView) {
			double widthInUnits = selection.width
					* camera.getUnitsPerPixel().getX();
			double heightInUnits = selection.height
					* camera.getUnitsPerPixel().getY();

			String text = String.format("%dpx, %dpx\n%2.3f%s, %2.3f%s",
					(int) selection.getWidth(), (int) selection.getHeight(),
					widthInUnits, camera.getUnitsPerPixel().getUnits()
							.getShortName(), heightInUnits, camera
							.getUnitsPerPixel().getUnits().getShortName());
			return text;
		}
	};
}
