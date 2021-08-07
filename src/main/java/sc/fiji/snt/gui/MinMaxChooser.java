package sc.fiji.snt.gui;

import java.awt.event.ActionEvent;
import java.awt.event.WindowEvent;

import javax.swing.SwingUtilities;

import ij.ImagePlus;
import ij.gui.HistogramWindow;
import ij.gui.Roi;
import ij.gui.Toolbar;
import sc.fiji.snt.SNT;
import sc.fiji.snt.SNTUI;

public class MinMaxChooser extends HistogramWindow {

	private static final long serialVersionUID = -2851718045534724460L;
	public static final String HIST_TITLE = "Choosing Min-Max...";

	private final SNT snt;
	private final SNTUI ui;
	private int initialState;
	private final boolean secondaryImg;
	private final int uiStateWhileDisplaying;
	private boolean minMaxApplied;
	private final boolean paused;
	private int initialTool;

	public MinMaxChooser(final SNTUI ui, final SNT snt, final ImagePlus imp, final boolean secondaryImg,
			final int uiStateWhileDisplaying) {
		super(HIST_TITLE, imp, imp.getStatistics(ImagePlus.MEAN + ImagePlus.MODE + ImagePlus.MIN_MAX, 256, 0, 0));
		this.ui = ui;
		this.snt = snt;
		this.secondaryImg = secondaryImg;
		this.uiStateWhileDisplaying = uiStateWhileDisplaying;
		paused = ui.getState() == SNTUI.SNT_PAUSED;
		list.setLabel("Apply Min-Max");
		copy.setLabel("Help");
		live.setVisible(false);
		setLocationRelativeTo(ui);
		initROI();
	}

	@Override
	public void actionPerformed(final ActionEvent e) {
		final Object b = e.getSource();
		if (b == list) {
			applyMinMax();
			resetUI();
			dispose();
		} else if (b == copy) {
			showHelpMsg();
		} else {
			super.actionPerformed(e);
		}
	}

	@Override
	public void windowOpened(final WindowEvent e) {
		super.windowOpened(e);
		// HACK: We need to activate live mode. With the current API, 'clicking' on it
		// seems the easiest?
		live.dispatchEvent(new ActionEvent(live, ActionEvent.ACTION_PERFORMED, "Live"));
		initialState = ui.getState();
		snt.pause(true, false);
		snt.setCanvasLabelAllPanes(HIST_TITLE);
		ui.changeState(uiStateWhileDisplaying);
	}

	@Override
	public void windowClosing(final WindowEvent e) {
		if (!minMaxApplied && new GuiUtils(this).getConfirmation(
				"Min-Max range has not yet been applied. " + "Would you like to apply it now?", "Apply Range?",
				"Yes. Apply Current Range.", "No. Dismiss. I'll Set Range Later.")) {
			applyMinMax();
		}
		resetUI();
		super.windowClosing(e);
	}

	@Override
	public void windowClosed(final WindowEvent e) {
		resetUI();
		super.windowClosed(e);
	}

	private void initROI() {
		initialTool = Toolbar.getToolId();
		Toolbar.getInstance().setTool(Toolbar.RECTANGLE);
		final ImagePlus imp = snt.getImagePlus();
		if (imp.getRoi() == null || !imp.getRoi().isArea()) {
			final int w = Math.min(100, imp.getWidth() / 2);
			final int h = Math.min(100, imp.getHeight() / 2);
			imp.setRoi(new Roi(imp.getWidth() / 2 - w / 2, imp.getHeight() / 2 - h / 2, w, h), true);
		}
	}

	private void applyMinMax() {
		if (secondaryImg) {
			snt.setSecondaryImageMinMax((float) stats.min, (float) stats.max);
		} else {
			snt.setImageMinMax((float) stats.min, (float) stats.max);
		}
		minMaxApplied = true;
		SwingUtilities.invokeLater(() -> ui.refresh());
	}

	private void resetUI() {
		Toolbar.getInstance().setTool(initialTool);
		snt.getImagePlus().killRoi();
		snt.setCanvasLabelAllPanes(null);
		snt.pause(paused, false);
		ui.changeState(initialState);
	}

	private void showHelpMsg() {
		new GuiUtils(this).showHTMLDialog("Help goes here", "Help on " + HIST_TITLE, false).setVisible(true);
	}
}
