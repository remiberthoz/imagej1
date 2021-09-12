package ij.plugin;
import ij.*;
import ij.process.*;
import ij.gui.*;
import java.awt.*;
import ij.measure.*;
import java.awt.event.*;

/** This plugin implements the Analyze/Tools/Draw Scale Bar command.
	Divakar Ramachandran added options to draw a background 
	and use a serif font on 23 April 2006.
*/
public class ScaleBar implements PlugIn {

	static final String[] locations = {"Upper Right", "Lower Right", "Lower Left", "Upper Left", "At Selection"};
	static final int UPPER_RIGHT=0, LOWER_RIGHT=1, LOWER_LEFT=2, UPPER_LEFT=3, AT_SELECTION=4;
	static final String[] colors = {"White","Black","Light Gray","Gray","Dark Gray","Red","Green","Blue","Yellow"};
	static final String[] bcolors = {"None","Black","White","Dark Gray","Gray","Light Gray","Yellow","Blue","Green","Red"};
	static final String[] checkboxLabels = {"Bold Text", "Hide Text", "Serif Font", "Overlay"};
	final static String SCALE_BAR = "|SB|";
	
	private static final ScaleBarConfiguration sConfig = new ScaleBarConfiguration();
	private ScaleBarConfiguration config = new ScaleBarConfiguration(sConfig);

	ImagePlus imp;
	double imageWidth;
	double mag = 1.0;
	int xloc, yloc;
	int barWidthInPixels;
	int roiX, roiY, roiWidth, roiHeight;
	boolean userRoiExists;
	boolean[] checkboxStates = new boolean[4];
	boolean showingOverlay, drawnScaleBar;

	public void run(String arg) {
		imp = WindowManager.getCurrentImage();
		if (imp == null) {
			IJ.noImage();
			return;
		}

		userRoiExists = parseCurrentROI();
		GenericDialog dialog = prepareDialog(userRoiExists);

		dialog.showDialog();
		if (dialog.wasCanceled()) {
			removeScalebar();
			return;
		}

		persistConfiguration();
		updateScalebar(!config.labelAll);
	 }

	void removeScalebar() {
		// Revert with Undo, in case "Use Overlay" is not ticked
		imp.getProcessor().reset();
		imp.updateAndDraw();
		// Remove overlay drawn by this plugin, in case "Use Overlay" is ticked
		Overlay overlay = imp.getOverlay();
		if (overlay!=null) {
			overlay.remove(SCALE_BAR);
			imp.draw();
		}
	}

	/**
	 * If there is a user selected ROI, set the class variables {roiX}
	 * and {roiY}, {roiWidth}, {roiHeight} to the corresponding
	 * features of the ROI, and return true. Otherwise, return false.
	 */
    boolean parseCurrentROI() {
        Roi roi = imp.getRoi();
        if (roi == null) return false;

        Rectangle r = roi.getBounds();
        roiX = r.x;
        roiY = r.y;
		roiWidth = r.width;
		roiHeight = r.height;
        return true;
    }

	void computeDefaultBarWidth(boolean currentROIExists) {
		Calibration cal = imp.getCalibration();
		ImageWindow win = imp.getWindow();
		mag = (win!=null)?win.getCanvas().getMagnification():1.0;
		if (mag>1.0)
			mag = 1.0;

		double pixelWidth = cal.pixelWidth;
		if (pixelWidth==0.0)
			pixelWidth = 1.0;
		imageWidth = imp.getWidth()*pixelWidth;

		if (currentROIExists && roiX>0 && roiWidth>10) {
			// If the user has a ROI, set the bar width according to ROI width.
			config.barWidth = roiWidth*pixelWidth;
		}
		else if (config.barWidth<=0.0 || config.barWidth>0.67*imageWidth) {
			// If the bar is of negative width or too wide for the image,
			// set the bar width to 80 pixels.
			config.barWidth = (80.0*pixelWidth)/mag;
			if (config.barWidth>0.67*imageWidth)
				// If 80 pixels is too much, do 2/3 of the image.
				config.barWidth = 0.67*imageWidth;
			if (config.barWidth>5.0)
				// If the resulting size is larger than 5 units, round the value.
				config.barWidth = (int) config.barWidth;
		}
	} 

	GenericDialog prepareDialog(boolean currentROIExists) {
		if (currentROIExists)
			config.location = locations[AT_SELECTION];
		if (config.barWidth <= 0 || currentROIExists)
			computeDefaultBarWidth(currentROIExists);
			
		int stackSize = imp.getStackSize();
		int digits = (int)config.barWidth==config.barWidth?0:1;
		if (config.barWidth<1.0)
			digits = 2;
			
		imp.getProcessor().snapshot();
		updateScalebar(true);

		boolean multipleSlices = stackSize > 1;
		return new BarDialog(getUnits(), digits, multipleSlices);
	}

	void persistConfiguration() {
		sConfig.updateFrom(config);
	}

	/**
	 * Create & draw the scalebar by editing pixels in the image.
	 */
	void createScaleBarDrawing(boolean previewOnly) {
		if (previewOnly) {
			drawScaleBarOnImageProcessor(imp.getProcessor(), getUnits());
			imp.updateAndDraw();
		} else {
			ImageStack stack = imp.getStack();
			String units = getUnits();
			for (int i=1; i<=stack.size(); i++)
				drawScaleBarOnImageProcessor(stack.getProcessor(i), units);
			imp.setStack(stack);
		}
	}
	
	String getUnits() {
		String units = imp.getCalibration().getUnits();
		if (units.equals("microns"))
			units = IJ.micronSymbol+"m";
		return units;
	}

	/**
	 * Create & draw the scalebar using an Overlay.
	 */
	void createScaleBarOverlay(boolean previewOnly) {
		Overlay overlay = imp.getOverlay();
		if (overlay==null)
			overlay = new Overlay();
		else
			overlay.remove(SCALE_BAR);
		Color color = getColor();
		Color bcolor = getBColor();
		int x = xloc;
		int y = yloc;
		int fontType = config.boldText?Font.BOLD:Font.PLAIN;
		String face = config.serifFont?"Serif":"SanSerif";
		Font font = new Font(face, fontType, config.fontSize);
		String label = getLength(config.barWidth) + " "+ getUnits();
		ImageProcessor ip = imp.getProcessor();
		ip.setFont(font);
		int swidth = config.hideText?0:ip.getStringWidth(label);
		int xoffset = (barWidthInPixels - swidth)/2;
		int yoffset =  config.barHeightInPixels + (config.hideText?0:config.fontSize+config.fontSize/4);
		if (bcolor!=null) {
			int w = barWidthInPixels;
			int h = yoffset;
			if (w<swidth) w = swidth;
			int x2 = x;
			if (x+xoffset<x2) x2 = x + xoffset;
			int margin = w/20;
			if (margin<2) margin = 2;
			x2 -= margin;
			int y2 = y - margin;
			w = w+ margin*2;
			h = h+ margin*2;
			Roi background = new Roi(x2, y2, w, h);
			background.setFillColor(bcolor);
			overlay.add(background, SCALE_BAR);
		}
		Roi bar = new Roi(x, y, barWidthInPixels, config.barHeightInPixels);
		bar.setFillColor(color);
		overlay.add(bar, SCALE_BAR);
		if (!config.hideText) {
			TextRoi text = new TextRoi(x+xoffset, y+config.barHeightInPixels, label, font);
			text.setStrokeColor(color);
			overlay.add(text, SCALE_BAR);
		}
		imp.setOverlay(overlay);
		showingOverlay = true;
	}
	
	/**
	 * Draw the scalebar on pixels of the {ip} ImageProcessor.
	 */
	void drawScaleBarOnImageProcessor(ImageProcessor ip, String units) {
		Color color = getColor();
		Color bcolor = getBColor();
		int x = xloc;
		int y = yloc;
		int fontType = config.boldText?Font.BOLD:Font.PLAIN;
		String font = config.serifFont?"Serif":"SanSerif";
		ip.setFont(new Font(font, fontType, config.fontSize));
		ip.setAntialiasedText(true);
		String label = getLength(config.barWidth) + " "+ units;
		int swidth = config.hideText?0:ip.getStringWidth(label);
		int xoffset = (barWidthInPixels - swidth)/2;
		int yoffset =  config.barHeightInPixels + (config.hideText?0:config.fontSize+config.fontSize/(config.serifFont?8:4));

		// Draw bkgnd box first,  based on bar width and height (and font size if hideText is not checked)
		if (bcolor!=null) {
			int w = barWidthInPixels;
			int h = yoffset;
			if (w<swidth) w = swidth;
			int x2 = x;
			if (x+xoffset<x2) x2 = x + xoffset;
			int margin = w/20;
			if (margin<2) margin = 2;
			x2 -= margin;
			int y2 = y - margin;
			w = w+ margin*2;
			h = h+ margin*2;
			ip.setColor(bcolor);
			ip.setRoi(x2, y2, w, h);
			ip.fill();
		}
		
		ip.resetRoi();
		ip.setColor(color);
		ip.setRoi(x, y, barWidthInPixels, config.barHeightInPixels);
		ip.fill();
		ip.resetRoi();
		if (!config.hideText)
			ip.drawString(label, x+xoffset, y+yoffset);
		drawnScaleBar = true;
	}

	String getLength(double barWidth) {
		int digits = (int)barWidth==barWidth?0:1;
		if (barWidth<1.0) digits=1;
		if (digits==1) {
			String s = IJ.d2s(barWidth/0.1, 2);
			if (!s.endsWith(".00")) digits = 2;
		}
		return IJ.d2s(barWidth, digits);
	}

	int computeLabelWidthInPixels() {
		String label = getLength(config.barWidth)+" "+getUnits();
		ImageProcessor ip = imp.getProcessor();
		int swidth = config.hideText?0:ip.getStringWidth(label);
		return (swidth < barWidthInPixels)?0:(int) (barWidthInPixels-swidth)/2;
	}

	void updateFont() {
		int fontType = config.boldText?Font.BOLD:Font.PLAIN;
		String font = config.serifFont?"Serif":"SanSerif";
		ImageProcessor ip = imp.getProcessor();
		ip.setFont(new Font(font, fontType, config.fontSize));
		ip.setAntialiasedText(true);
	}

	void updateLocation() throws MissingRoiException {
		Calibration cal = imp.getCalibration();
		barWidthInPixels = (int)(config.barWidth/cal.pixelWidth);
		int width = imp.getWidth();
		int height = imp.getHeight();
		int margin = (width+height)/100;
		if (mag==1.0)
			margin = (int)(margin*1.5);
		updateFont();
		int labelWidth = computeLabelWidthInPixels();
		int x = 0;
		int y = 0;
		if (config.location.equals(locations[UPPER_RIGHT])) {
			x = width - margin - barWidthInPixels + labelWidth;
			y = margin;
		} else if (config.location.equals(locations[LOWER_RIGHT])) {
			x = width - margin - barWidthInPixels + labelWidth;
			y = height - margin - config.barHeightInPixels - config.fontSize;
		} else if (config.location.equals(locations[UPPER_LEFT])) {
			x = margin - labelWidth;
			y = margin;
		} else if (config.location.equals(locations[LOWER_LEFT])) {
			x = margin - labelWidth;
			y = height - margin - config.barHeightInPixels - config.fontSize;
		} else {
			if (!userRoiExists)
				throw new MissingRoiException();
			x = roiX;
			y = roiY;
		}
		xloc = x;
		yloc = y;
	}

	Color getColor() {
		Color c = Color.black;
		if (config.color.equals(colors[0])) c = Color.white;
		else if (config.color.equals(colors[2])) c = Color.lightGray;
		else if (config.color.equals(colors[3])) c = Color.gray;
		else if (config.color.equals(colors[4])) c = Color.darkGray;
		else if (config.color.equals(colors[5])) c = Color.red;
		else if (config.color.equals(colors[6])) c = Color.green;
		else if (config.color.equals(colors[7])) c = Color.blue;
		else if (config.color.equals(colors[8])) c = Color.yellow;
	   return c;
	}

	// Div., mimic getColor to write getBColor for bkgnd	
	Color getBColor() {
		if (config.bcolor==null || config.bcolor.equals(bcolors[0])) return null;
		Color bc = Color.white;
		if (config.bcolor.equals(bcolors[1])) bc = Color.black;
		else if (config.bcolor.equals(bcolors[3])) bc = Color.darkGray;
		else if (config.bcolor.equals(bcolors[4])) bc = Color.gray;
		else if (config.bcolor.equals(bcolors[5])) bc = Color.lightGray;
		else if (config.bcolor.equals(bcolors[6])) bc = Color.yellow;
		else if (config.bcolor.equals(bcolors[7])) bc = Color.blue;
		else if (config.bcolor.equals(bcolors[8])) bc = Color.green;
		else if (config.bcolor.equals(bcolors[9])) bc = Color.red;
		return bc;
	}

	void updateScalebar(boolean previewOnly) {
		removeScalebar();
		try {
			updateLocation();
		} catch (MissingRoiException e) {
			return; // Simply don't draw the scalebar.
		}
		if (config.useOverlay)
			createScaleBarOverlay(previewOnly);
		else
			createScaleBarDrawing(previewOnly);
	}

   class BarDialog extends GenericDialog {

		private boolean multipleSlices;

		BarDialog(String units, int digits, boolean multipleSlices) {
			super("Scale Bar");
			this.multipleSlices = multipleSlices;

			addNumericField("Width in "+units+": ", config.barWidth, digits);
			addNumericField("Height in pixels: ", config.barHeightInPixels, 0);
			addNumericField("Font size: ", config.fontSize, 0);
			addChoice("Color: ", colors, config.color);
			addChoice("Background: ", bcolors, config.bcolor);
			addChoice("Location: ", locations, config.location);
			checkboxStates[0] = config.boldText; checkboxStates[1] = config.hideText;
			checkboxStates[2] = config.serifFont; checkboxStates[3] = config.useOverlay;
			setInsets(10, 25, 0);
			addCheckboxGroup(2, 2, checkboxLabels, checkboxStates);

			if (multipleSlices) {
				setInsets(0, 25, 0);
				addCheckbox("Label all slices", config.labelAll);
			}
		}

		public void textValueChanged(TextEvent e) {
			TextField widthField = ((TextField)numberField.elementAt(0));
			Double d = getValue(widthField.getText());
			if (d==null)
				return;
			config.barWidth = d.doubleValue();
			TextField heightField = ((TextField)numberField.elementAt(1));
			d = getValue(heightField.getText());
			if (d==null)
				return;
			config.barHeightInPixels = (int)d.doubleValue();
			TextField fontSizeField = ((TextField)numberField.elementAt(2));
			d = getValue(fontSizeField.getText());
			if (d==null)
				return;
			int size = (int)d.doubleValue();
			if (size>5)
				config.fontSize = size;
			updateScalebar(true);
		}

		public void itemStateChanged(ItemEvent e) {
			Choice col = (Choice)(choice.elementAt(0));
			config.color = col.getSelectedItem();
			Choice bcol = (Choice)(choice.elementAt(1));
			config.bcolor = bcol.getSelectedItem();
			Choice loc = (Choice)(choice.elementAt(2));
			config.location = loc.getSelectedItem();
			config.boldText = ((Checkbox)(checkbox.elementAt(0))).getState();
			config.hideText = ((Checkbox)(checkbox.elementAt(1))).getState();
			config.serifFont = ((Checkbox)(checkbox.elementAt(2))).getState();
			config.useOverlay = ((Checkbox)(checkbox.elementAt(3))).getState();
			if (multipleSlices)
				config.labelAll = ((Checkbox)(checkbox.elementAt(4))).getState();
			updateScalebar(true);
		}

   } //BarDialog inner class

   class MissingRoiException extends Exception {
		MissingRoiException() {
			super("Scalebar location is set to AT_SELECTION but there is no selection on the image.");
		}
   } //MissingRoiException inner class

	static class ScaleBarConfiguration {
	
		private static int defaultBarHeight = 4;

		double barWidth;
		int barHeightInPixels;
		String location;
		String color;
		String bcolor;
		boolean boldText;
		boolean hideText;
		boolean serifFont;
		boolean useOverlay;
		int fontSize;
		boolean labelAll;

		/**
		 * Create ScaleBarConfiguration with default values.
		 */
		ScaleBarConfiguration() {
			this.barWidth = -1;
			this.barHeightInPixels = defaultBarHeight;
			this.location = locations[LOWER_RIGHT];
			this.color = colors[0];
			this.bcolor = bcolors[0];
			this.boldText = true;
			this.hideText = false;
			this.serifFont = false;
			this.useOverlay = true;
			this.fontSize = 14;
			this.labelAll = false;
		}

		/**
		 * Copy constructor.
		 */
		ScaleBarConfiguration(ScaleBarConfiguration model) {
			this.updateFrom(model);
		}
		
		void updateFrom(ScaleBarConfiguration model) {
			this.barWidth = model.barWidth;
			this.barHeightInPixels = model.barHeightInPixels;
			this.location = locations[LOWER_RIGHT];
			this.color = model.color;
			this.bcolor = model.bcolor;
			this.boldText = model.boldText;
			this.serifFont = model.serifFont;
			this.hideText = model.hideText;
			this.useOverlay = model.useOverlay;
			this.fontSize = model.fontSize;
			this.labelAll = model.labelAll;
		}
	} //ScaleBarConfiguration inner class

} //ScaleBar class
