/*-
 * #%L
 * Fiji distribution of ImageJ for the life sciences.
 * %%
 * Copyright (C) 2010 - 2022 Fiji developers.
 * %%
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 * 
 * You should have received a copy of the GNU General Public
 * License along with this program.  If not, see
 * <http://www.gnu.org/licenses/gpl-3.0.html>.
 * #L%
 */

package sc.fiji.snt;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Frame;
import java.awt.GraphicsConfiguration;
import java.awt.GraphicsDevice;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.Map.Entry;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.stream.IntStream;

import javax.swing.Timer;
import javax.swing.border.BevelBorder;
import javax.swing.border.EmptyBorder;
import javax.swing.event.ChangeListener;

import org.apache.commons.lang3.StringUtils;
import org.scijava.command.Command;
import org.scijava.command.CommandModule;
import org.scijava.command.CommandService;
import org.scijava.util.ColorRGB;
import org.scijava.util.Types;

import ij.ImageListener;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.ImageCanvas;
import ij.gui.ImageWindow;
import ij3d.Content;
import ij3d.ContentConstants;
import ij3d.Image3DUniverse;
import ij3d.ImageWindow3D;
import net.imagej.Dataset;
import sc.fiji.snt.analysis.SNTTable;
import sc.fiji.snt.analysis.TreeAnalyzer;
import sc.fiji.snt.analysis.sholl.ShollUtils;
import sc.fiji.snt.event.SNTEvent;
import sc.fiji.snt.gui.cmds.*;
import sc.fiji.snt.hyperpanes.MultiDThreePanes;
import sc.fiji.snt.gui.CheckboxSpinner;
import sc.fiji.snt.gui.ColorChooserButton;
import sc.fiji.snt.gui.FileDrop;
import sc.fiji.snt.gui.GuiUtils;
import sc.fiji.snt.gui.IconFactory;
import sc.fiji.snt.gui.IconFactory.GLYPH;
import sc.fiji.snt.gui.SNTCommandFinder;
import sc.fiji.snt.gui.SaveMeasurementsCmd;
import sc.fiji.snt.gui.SigmaPaletteListener;
import sc.fiji.snt.gui.ScriptInstaller;
import sc.fiji.snt.gui.SigmaPalette;
import sc.fiji.snt.io.FlyCircuitLoader;
import sc.fiji.snt.io.NeuroMorphoLoader;
import sc.fiji.snt.plugin.*;
import sc.fiji.snt.tracing.cost.OneMinusErf;
import sc.fiji.snt.viewer.Viewer3D;

import javax.swing.*;
import java.util.*;

/**
 * Implements SNT's main dialog.
 *
 * @author Tiago Ferreira
 */
@SuppressWarnings("serial")
public class SNTUI extends JDialog {

	/* UI */
	private static final int MARGIN = 2;
	private final JMenuBar menuBar;
	private JCheckBox showPathsSelected;
	protected CheckboxSpinner partsNearbyCSpinner;
	protected JCheckBox useSnapWindow;
	private JCheckBox onlyActiveCTposition;
	protected JSpinner snapWindowXYsizeSpinner;
	protected JSpinner snapWindowZsizeSpinner;
	private JButton showOrHidePathList;
	private JButton showOrHideFillList = new JButton(); // must be initialized
	private JMenuItem loadTracesMenuItem;
	private JMenuItem loadSWCMenuItem;
	private JMenuItem loadLabelsMenuItem;
	private JMenuItem saveMenuItem;
	private JMenuItem exportCSVMenuItem;
	private JMenuItem exportAllSWCMenuItem;
	private JMenuItem quitMenuItem;
	private JMenuItem sendToTrakEM2;
	private JLabel statusText;
	private JLabel statusBarText;
	private JButton keepSegment;
	private JButton junkSegment;
	private JButton completePath;
	private JButton rebuildCanvasButton;
	private JCheckBox debugCheckBox;

	// UI controls for auto-tracing
	//TODO: reduce the need for all these fields
	private JComboBox<String> searchAlgoChoice;
	private JPanel aStarPanel;
	private JCheckBox aStarCheckBox;
	private SigmaPalette sigmaPalette;
	private final SNTCommandFinder commandFinder;
	private JTextArea settingsArea;

	// UI controls for CT data source
	private JPanel sourcePanel;

	// UI controls for loading  on 'secondary layer'
	private JPanel secLayerPanel;
	private JCheckBox secLayerActivateCheckbox;
	private JRadioButton secLayerBuiltinRadioButton;
	private JRadioButton secLayerExternalRadioButton;
	private JButton secLayerGenerate;

	private JButton secLayerExternalImgOptionsButton;
	private CheckboxSpinner secLayerExternalImgOverlayCSpinner;
	private JMenuItem secLayerExternalImgLoadFlushMenuItem;

	private ActiveWorker activeWorker;
	private volatile int currentState = -1;

	private final SNT plugin;
	private final PathAndFillManager pathAndFillManager;
	protected final GuiUtils guiUtils;
	private final PathManagerUI pmUI;
	private final FillManagerUI fmUI;

	/* Reconstruction Viewer */
	protected Viewer3D recViewer;
	protected Frame recViewerFrame;
	private JButton openRecViewer;

	/* SciView */
	protected SciViewSNT sciViewSNT;
	private JButton openSciView;
	private JButton svSyncPathManager;


	protected final GuiListener listener;

	/* These are the states that the UI can be in: */
	/**
	 * Flag specifying that image data is available and the UI is not waiting on any
	 * pending operations, thus 'ready to trace'
	 */
	public static final int READY = 0;
	static final int WAITING_TO_START_PATH = 0; /* legacy flag */
	static final int PARTIAL_PATH = 1;
	static final int SEARCHING = 2;
	static final int QUERY_KEEP = 3;
	public static final int RUNNING_CMD = 4;
	static final int CACHING_DATA = 5;
	static final int FILLING_PATHS = 6;
	static final int CALCULATING_HESSIAN_I = 7;
	static final int CALCULATING_HESSIAN_II = 8;
	public static final int WAITING_FOR_SIGMA_POINT_I = 9;
	//static final int WAITING_FOR_SIGMA_POINT_II = 10;
	static final int WAITING_FOR_SIGMA_CHOICE = 11;
	static final int SAVING = 12;
	/** Flag specifying UI is currently waiting for I/0 operations to conclude */
	public static final int LOADING = 13;
	/** Flag specifying UI is currently waiting for fitting operations to conclude */
	public static final int FITTING_PATHS = 14;
	/**Flag specifying UI is currently waiting for user to edit a selected Path */
	public static final int EDITING = 15;
	/**
	 * Flag specifying all SNT are temporarily disabled (all user interactions are
	 * waived back to ImageJ)
	 */
	public static final int SNT_PAUSED = 16;
	/**
	 * Flag specifying tracing functions are (currently) disabled. Tracing is
	 * disabled when the user chooses so or when no valid image data is available
	 * (e.g., when no image has been loaded and a placeholder display canvas is
	 * being used)
	 */
	public static final int TRACING_PAUSED = 17;


	// TODO: Internal preferences: should be migrated to SNTPrefs
	protected boolean confirmTemporarySegments = true;
	protected boolean finishOnDoubleConfimation = true;
	protected boolean discardOnDoubleCancellation = true;
	protected boolean askUserConfirmation = true;
	private boolean openingSciView;
	private SigmaPaletteListener sigmaPaletteListener;

	/**
	 * Instantiates SNT's main UI and associated {@link PathManagerUI} and
	 * {@link FillManagerUI} instances.
	 *
	 * @param plugin the {@link SNT} instance associated with this
	 *               UI
	 */
	public SNTUI(final SNT plugin) {
		this(plugin, null, null);
	}

	private SNTUI(final SNT plugin, final PathManagerUI pmUI, final FillManagerUI fmUI) {

		super(plugin.legacyService.getIJ1Helper().getIJ(), "SNT v" + SNTUtils.VERSION, false);
		guiUtils = new GuiUtils(this);
		this.plugin = plugin;
		new ClarifyingKeyListener(plugin).addKeyAndContainerListenerRecursively(this);
		listener = new GuiListener();
		pathAndFillManager = plugin.getPathAndFillManager();
		commandFinder = new SNTCommandFinder(this);

		setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(final WindowEvent e) {
				exitRequested();
			}
		});

		GuiUtils.removeIcon(this);

		assert SwingUtilities.isEventDispatchThread();
		final JTabbedPane tabbedPane = getTabbedPane();

		{ // Main tab
			final GridBagConstraints c1 = GuiUtils.defaultGbc();
			{
				final JPanel tab1 = getTab();
				// c.insets.left = MARGIN * 2;
				c1.anchor = GridBagConstraints.NORTHEAST;
				addSeparatorWithURL(tab1, "Cursor Auto-snapping:", false, c1);
				++c1.gridy;
				tab1.add(snappingPanel(), c1);
				++c1.gridy;
				addSeparatorWithURL(tab1, "Auto-tracing:", true, c1);
				++c1.gridy;
				tab1.add(aStarPanel(), c1);
				++c1.gridy;
				//tab1.add(hessianPanel(), c1);
				//++c1.gridy;
				tab1.add(secondaryDataPanel(), c1);
				++c1.gridy;
				tab1.add(settingsPanel(), c1);
				++c1.gridy;
				addSeparatorWithURL(tab1, "Filters for Visibility of Paths:", true, c1);
				++c1.gridy;
				tab1.add(renderingPanel(), c1);
				++c1.gridy;
				addSeparatorWithURL(tab1, "Default Path Colors:", true, c1);
				++c1.gridy;
				tab1.add(colorOptionsPanel(), c1);
				++c1.gridy;
				GuiUtils.addSeparator(tab1, "", true, c1); // empty separator
				++c1.gridy;
				c1.fill = GridBagConstraints.HORIZONTAL;
				c1.insets = new Insets(0, 0, 0, 0);
				tab1.add(hideWindowsPanel(), c1);
				tabbedPane.addTab("<HTML>Main ", tab1);
			}
		}

		{ // Options Tab
			final JPanel tab2 = getTab();
			tab2.setLayout(new GridBagLayout());
			final GridBagConstraints c2 = GuiUtils.defaultGbc();
			// c2.insets.left = MARGIN * 2;
			c2.anchor = GridBagConstraints.NORTHEAST;
			c2.gridwidth = GridBagConstraints.REMAINDER;
			{
				addSeparatorWithURL(tab2, "Data Source:", false, c2);
				++c2.gridy;
				tab2.add(sourcePanel = sourcePanel(plugin.getImagePlus()), c2);
				++c2.gridy;
			}

			addSeparatorWithURL(tab2, "Views:", true, c2);
			++c2.gridy;
			tab2.add(viewsPanel(), c2);
			++c2.gridy;
			{
				addSeparatorWithURL(tab2, "Temporary Paths:", true, c2);
				++c2.gridy;
				tab2.add(tracingPanel(), c2);
				++c2.gridy;
			}
			addSeparatorWithURL(tab2, "Path Rendering:", true, c2);
			++c2.gridy;
			tab2.add(pathOptionsPanel(), c2);
			++c2.gridy;
			addSeparatorWithURL(tab2, "Misc:", true, c2);
			++c2.gridy;
			c2.weighty = 1;
			tab2.add(miscPanel(), c2);
			tabbedPane.addTab("<HTML>Options ", tab2);
		}

		{ // 3D tab
			final JPanel tab3 = getTab();
			tab3.setLayout(new GridBagLayout());
			final GridBagConstraints c3 = GuiUtils.defaultGbc();
			// c3.insets.left = MARGIN * 2;
			c3.anchor = GridBagConstraints.NORTHEAST;
			c3.gridwidth = GridBagConstraints.REMAINDER;

			tabbedPane.addTab("<HTML> 3D ", tab3);
			addSeparatorWithURL(tab3, "Reconstruction Viewer:", true, c3);
			c3.gridy++;
			final String msg = "A dedicated OpenGL visualization tool specialized in Neuroanatomy, " +
				"supporting morphometric annotations, reconstructions and meshes. For " +
				"performance reasons, some Path Manager changes may need to be synchronized " +
				"manually from the \"Scene Controls\" menu.";
			tab3.add(largeMsg(msg), c3);
			c3.gridy++;
			tab3.add(reconstructionViewerPanel(), c3);
			c3.gridy++;
			addSpacer(tab3, c3);
			addSeparatorWithURL(tab3, "sciview:", true, c3);
			++c3.gridy;
			final String msg3 =
				"IJ2's modern 3D visualization framework supporting large image volumes, " +
				"reconstructions, meshes, and virtual reality. Discrete graphics card recommended. " +
				"For performance reasons, some Path Manager changes may need to be synchronized " +
				"manually using \"Sync Changes\".";
			tab3.add(largeMsg(msg3), c3);
			c3.gridy++;
			tab3.add(sciViewerPanel(), c3);
			c3.gridy++;
			addSpacer(tab3, c3);
			addSeparatorWithURL(tab3, "Legacy 3D Viewer:", true, c3);
			++c3.gridy;
			final String msg2 =
				"The Legacy 3D Viewer is a functional tracing canvas " +
					"but it depends on outdated services that are now deprecated. " +
					"It may not function reliably on recent operating systems.";
			tab3.add(largeMsg(msg2), c3);
			c3.gridy++;
			try {
				tab3.add(legacy3DViewerPanel(), c3);
			} catch (final NoClassDefFoundError ignored) {
				tab3.add(largeMsg("Error: Legacy 3D Viewer could not be initialized!"), c3);
			}
			c3.gridy++;
			tab3.add(largeMsg(""), c3); // add bottom spacer

			{
				tabbedPane.setIconAt(0, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.HOME));
				tabbedPane.setIconAt(1, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.TOOL));
				tabbedPane.setIconAt(2, IconFactory.getTabbedPaneIcon(IconFactory.GLYPH.CUBE));
			}
		}

		setJMenuBar(menuBar = createMenuBar());
		setLayout(new GridBagLayout());
		final GridBagConstraints dialogGbc = GuiUtils.defaultGbc();
		add(statusPanel(), dialogGbc);
		dialogGbc.gridy++;
		add(new JLabel(" "), dialogGbc);
		dialogGbc.gridy++;

		add(tabbedPane, dialogGbc);
		dialogGbc.gridy++;
		add(statusBar(), dialogGbc);
		addFileDrop(this, guiUtils);
		registerMainButtonsInCommandFinder();
		//registerCommandFinder(menuBar); // spurious addition if added here!?
		pack();
		toFront();

		if (pmUI == null) {
			this.pmUI = new PathManagerUI(plugin);
			this.pmUI.setLocation(getX() + getWidth(), getY());
			if (showOrHidePathList != null) {
				this.pmUI.addWindowStateListener(evt -> {
					if ((evt.getNewState() & Frame.ICONIFIED) == Frame.ICONIFIED) {
						showOrHidePathList.setText("Show Path Manager");
					}
				});
				this.pmUI.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosing(final WindowEvent e) {
						showOrHidePathList.setText("Show Path Manager");
					}
				});
			}
			registerCommandFinder(this.pmUI.getJMenuBar());
		} else {
			this.pmUI = pmUI;
		}
		addFileDrop(this.pmUI, this.pmUI.guiUtils);
		if (fmUI == null) {
			this.fmUI = new FillManagerUI(plugin);
			this.fmUI.setLocation(getX() + getWidth(), getY() + this.pmUI.getHeight());
			if (showOrHidePathList != null) {
				this.fmUI.addWindowStateListener(evt -> {
					if (showOrHideFillList != null && (evt.getNewState() & Frame.ICONIFIED) == Frame.ICONIFIED) {
						showOrHideFillList.setText("Show Fill Manager");
					}
				});
				this.fmUI.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosing(final WindowEvent e) {
						showOrHideFillList.setText("Show Fill Manager");
					}
				});
			}
		} else {
			this.fmUI = fmUI;
		}

	}

	private JTabbedPane getTabbedPane() {

		/*
		 * TF: This is an effort at improving the tabbed interface. JIDE provides such
		 * functionality by default, but causes some weird looking L&F overrides (at
		 * least on macOS). Since I have no idea on how to stop JIDE from injecting
		 * such weirdness, we'll implement the customization ourselves.
		 */
		// final JideTabbedPane tabbedPane = new JideTabbedPane(JTabbedPane.TOP);
		// tabbedPane.setBoldActiveTab(true);
		// tabbedPane.setScrollSelectedTabOnWheel(true);
		// tabbedPane.setTabResizeMode(JideTabbedPane.RESIZE_MODE_NONE);

		final JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
		tabbedPane.addChangeListener(e -> {
			final JTabbedPane source = (JTabbedPane) e.getSource();
			final int selectedTab = source.getSelectedIndex();

			// Do not allow secondary tabs to be selected while operations are pending 
			if (selectedTab > 0 && userInteractionConstrained()) {
				tabbedPane.setSelectedIndex(0);
				guiUtils.blinkingError(statusText,
						"Please complete current task before selecting the "+ source.getTitleAt(selectedTab) +" tab.");
				return;
			}

			// Highlight active tab. This assumes tab's title contains "HTML"
			for (int i = 0; i < source.getTabCount(); i++) {
				final String existingTile = source.getTitleAt(i);
				final String newTitle = (i == selectedTab) ? existingTile.replace("<HTML>", "<HTML><b>")
						: existingTile.replace("<HTML><b>", "<HTML>");
				source.setTitleAt(i, newTitle);
			}
		});
		tabbedPane.addMouseWheelListener(e -> {
			//https://stackoverflow.com/a/38463104
			final JTabbedPane pane = (JTabbedPane) e.getSource();
			final int units = e.getWheelRotation();
			final int oldIndex = pane.getSelectedIndex();
			final int newIndex = oldIndex + units;
			if (newIndex < 0)
				pane.setSelectedIndex(0);
			else if (newIndex >= pane.getTabCount())
				pane.setSelectedIndex(pane.getTabCount() - 1);
			else
				pane.setSelectedIndex(newIndex);
		});

		final JPopupMenu popup = new JPopupMenu();
		tabbedPane.setComponentPopupMenu(popup);
		final ButtonGroup group = new ButtonGroup();
		for (final String pos : new String[] { "Top", "Bottom", "Left", "Right" }) {
			final JMenuItem jcbmi = new JCheckBoxMenuItem("Place on " + pos, "Top".equals(pos));
			jcbmi.addItemListener(e -> {
				switch (pos) {
				case "Top":
					tabbedPane.setTabPlacement(JTabbedPane.TOP);
					break;
				case "Bottom":
					tabbedPane.setTabPlacement(JTabbedPane.BOTTOM);
					break;
				case "Left":
					tabbedPane.setTabPlacement(JTabbedPane.LEFT);
					break;
				case "Right":
					tabbedPane.setTabPlacement(JTabbedPane.RIGHT);
					break;
				}
			});
			group.add(jcbmi);
			popup.add(jcbmi);
		}
		return tabbedPane;
	}

	/**
	 * Gets the current UI state.
	 *
	 * @return the current UI state, e.g., {@link SNTUI#READY},
	 *         {@link SNTUI#RUNNING_CMD}, etc.
	 */
	public int getState() {
		if (plugin.tracingHalted && currentState == READY)
			currentState = TRACING_PAUSED;
		return currentState;
	}

	private boolean userInteractionConstrained() {
		switch (getState()) {
		case PARTIAL_PATH:
		case SEARCHING:
		case QUERY_KEEP:
		case RUNNING_CMD:
		case CALCULATING_HESSIAN_I:
		case CALCULATING_HESSIAN_II:
		case WAITING_FOR_SIGMA_POINT_I:
		case WAITING_FOR_SIGMA_CHOICE:
		case LOADING:
			return true;
		default:
			return false;
		}
	}

	/**
	 * Assesses whether the UI is blocked.
	 *
	 * @return true if the UI is currently unblocked, i.e., ready for
	 *         tracing/editing/analysis *
	 */
	public boolean isReady() {
		final int state = getState();
		return isVisible() && (state == SNTUI.READY || state == SNTUI.TRACING_PAUSED || state == SNTUI.SNT_PAUSED);
	}

	/**
	 * Enables/disables debug mode
	 *
	 * @param enable true to enable debug mode, otherwise false
	 */
	public void setEnableDebugMode(final boolean enable) {
		debugCheckBox.setSelected(enable);
		if (getReconstructionViewer(false) == null) {
			SNTUtils.setDebugMode(enable);
		} else {
			// will call SNT.setDebugMode(enable);
			getReconstructionViewer(false).setEnableDebugMode(enable);
		}
	}

	/**
	 * Runs a menu command (as listed in the menu bar hierarchy).
	 *
	 * @param cmd The command to be run, exactly as listed in its menu (either in
	 *            the this dialog, or {@link PathManagerUI})
	 * @throws IllegalArgumentException if {@code cmd} was not found.
	 */
	public void runCommand(final String cmd) throws IllegalArgumentException {
		if ("validateImgDimensions".equals(cmd)) {
			validateImgDimensions();
			return;
		}
		try {
			runCommand(menuBar, cmd);
		} catch (final IllegalArgumentException ie) {
			getPathManager().runCommand(cmd);
		}
	}

	protected static void runCommand(final JMenuBar menuBar, final String cmd) throws IllegalArgumentException {
		if (cmd == null || cmd.trim().isEmpty()) {
			throw new IllegalArgumentException("Not a recognizable command: " + cmd);
		}
		for (final JMenuItem jmi : GuiUtils.getMenuItems(menuBar)) {
			if (cmd.equals(jmi.getText()) || cmd.equals(jmi.getActionCommand())) {
				jmi.doClick();
				return;
			}
		}
		throw new IllegalArgumentException("Not a recognizable command: " + cmd);
	}

	private void addSeparatorWithURL(final JComponent component, final String label, final boolean vgap,
			final GridBagConstraints c) {
		final String anchor = label.toLowerCase().replace(" ", "-").replace(":", "");
		final String uri = "https://imagej.net/plugins/snt/manual#" + anchor;
		JLabel jLabel = GuiUtils.leftAlignedLabel(label, uri, true);
		GuiUtils.addSeparator(component, jLabel, vgap, c);
	}

	private void updateStatusText(final String newStatus, final boolean includeStatusBar) {
		updateStatusText(newStatus);
		if (includeStatusBar)
			showStatus(newStatus, true);
	}

	private void updateStatusText(final String newStatus) {
		statusText.setText("<html><strong>" + newStatus + "</strong></html>");
	}

	protected void gaussianCalculated(final boolean succeeded) {
		SwingUtilities.invokeLater(() -> {
			secLayerBuiltinRadioButton.setSelected(succeeded);
			changeState(READY);
			showStatus("Gaussian " + ((succeeded) ? " completed" : "failed"), true);
		});
	}

	public void refresh() {
		updateSettingsString();
		refreshStatus();
	}

	protected void updateSettingsString() {
		final StringBuilder sb = new StringBuilder();
		sb.append("Auto-tracing: ").append((plugin.isAstarEnabled()) ? searchAlgoChoice.getSelectedItem() : "Disabled");
		sb.append("\n");
		sb.append("    Data structure: ").append(plugin.searchImageType);
		sb.append("\n");
		sb.append("    Cost function: ").append(plugin.getCostType());
		if (plugin.getCostType() == SNT.CostType.PROBABILITY) {
			sb.append("; Z-fudge: ").append(SNTUtils.formatDouble(plugin.getOneMinusErfZFudge(), 3));
		}
		sb.append("\n");
		sb.append("    Min-Max: ").append(SNTUtils.formatDouble(plugin.getStats().min, 3)).append("-")
				.append(SNTUtils.formatDouble(plugin.getStats().max, 3));
		sb.append("\n");
		if (plugin.getSecondaryData() != null) {
			sb.append("Secondary layer: Active");
			sb.append("\n");
			sb.append("    Filter: ")
					.append((plugin.isSecondaryImageFileLoaded()) ? "External" : plugin.getFilterType());
			sb.append("\n");
			sb.append("    Min-Max: ").append(SNTUtils.formatDouble(plugin.getStatsSecondary().min, 3)).append("-")
					.append(SNTUtils.formatDouble(plugin.getStatsSecondary().max, 3));
		} else {
			sb.append("Secondary layer: Disabled");
		}
		assert SwingUtilities.isEventDispatchThread();
		settingsArea.setText(sb.toString());
		settingsArea.setCaretPosition(0);
		if (fmUI != null) fmUI.updateSettingsString();
	}

	protected void exitRequested() {
		assert SwingUtilities.isEventDispatchThread();
		String msg = "Exit SNT?";
		if (plugin.isChangesUnsaved())
			msg = "There are unsaved paths. Do you really want to quit?";
		if (pmUI.measurementsUnsaved())
			msg = "There are unsaved measurements. Do you really want to quit?";
		if (!guiUtils.getConfirmation(msg, "Really Quit?"))
			return;
		commandFinder.dispose();
		abortCurrentOperation();
		plugin.cancelSearch(true);
		plugin.notifyListeners(new SNTEvent(SNTEvent.QUIT));
		plugin.getPrefs().savePluginPrefs(true);
		pmUI.dispose();
		pmUI.closeTable();
		fmUI.dispose();
		if (recViewer != null)
			recViewer.dispose();
		dispose();
		// NB: If visible Reconstruction Plotter will remain open
		plugin.closeAndResetAllPanes();
		ImagePlus.removeImageListener(listener);
		SNTUtils.setPlugin(null);
		GuiUtils.restoreLookAndFeel();
	}

	private void setEnableAutoTracingComponents(final boolean enable, final boolean enableAstar) {
		if (secLayerPanel != null) {
			GuiUtils.enableComponents(secLayerPanel, enable);
			GuiUtils.enableComponents(aStarPanel, enableAstar);
			secLayerActivateCheckbox.setEnabled(enable);
		}
		updateSettingsString();
		updateExternalImgWidgets();
	}

	protected void disableImageDependentComponents() {
		assert SwingUtilities.isEventDispatchThread();
		loadLabelsMenuItem.setEnabled(false);
		fmUI.setEnabledNone();
		setEnableAutoTracingComponents(false, false);
	}

	private void disableEverything() {
		assert SwingUtilities.isEventDispatchThread();
		disableImageDependentComponents();
		loadTracesMenuItem.setEnabled(false);
		loadSWCMenuItem.setEnabled(false);
		exportCSVMenuItem.setEnabled(false);
		exportAllSWCMenuItem.setEnabled(false);
		sendToTrakEM2.setEnabled(false);
		saveMenuItem.setEnabled(false);
		quitMenuItem.setEnabled(false);
	}

	private void updateRebuildCanvasButton() {
		final ImagePlus imp = plugin.getImagePlus();
		final String label = (imp == null || imp.getProcessor() == null || plugin.accessToValidImageData()) ? "Create Canvas"
				: "Resize Canvas";
		rebuildCanvasButton.setText(label);
	}

	/**
	 * Changes this UI to a new state. Does nothing if {@code newState} is the
	 * current UI state
	 *
	 * @param newState the new state, e.g., {@link SNTUI#READY},
	 *                 {@link SNTUI#TRACING_PAUSED}, etc.
	 */
	public void changeState(final int newState) {

		if (newState == currentState) return;
		currentState = newState;
		SwingUtilities.invokeLater(() -> {
			switch (newState) {

			case WAITING_TO_START_PATH:
//					if (plugin.analysisMode || !plugin.accessToValidImageData()) {
//						changeState(ANALYSIS_MODE);
//						return;
//					}
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);

				//FIXME: Check that we really don't need this: pmUI.valueChanged(null); // Fake a selection change in the path tree
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(plugin.isAstarEnabled(), true);
				fmUI.setEnabledWhileNotFilling();
				loadLabelsMenuItem.setEnabled(true);
				saveMenuItem.setEnabled(true);
				loadTracesMenuItem.setEnabled(true);
				loadSWCMenuItem.setEnabled(true);

				exportCSVMenuItem.setEnabled(true);
				exportAllSWCMenuItem.setEnabled(true);
				sendToTrakEM2.setEnabled(plugin.anyListeners());
				quitMenuItem.setEnabled(true);
				showPathsSelected.setEnabled(true);
				updateStatusText("Click somewhere to start a new path...");
				showOrHideFillList.setEnabled(true);
				updateRebuildCanvasButton();
				break;

			case TRACING_PAUSED:

				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				pmUI.valueChanged(null); // Fake a selection change in the path tree:
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				plugin.discardFill();
				fmUI.setEnabledWhileNotFilling();
				// setFillListVisible(false);
				loadLabelsMenuItem.setEnabled(true);
				saveMenuItem.setEnabled(true);
				loadTracesMenuItem.setEnabled(true);
				loadSWCMenuItem.setEnabled(true);

				exportCSVMenuItem.setEnabled(true);
				exportAllSWCMenuItem.setEnabled(true);
				sendToTrakEM2.setEnabled(plugin.anyListeners());
				quitMenuItem.setEnabled(true);
				showPathsSelected.setEnabled(true);
				updateRebuildCanvasButton();
				updateStatusText("Tracing functions disabled...");
				break;

			case PARTIAL_PATH:
				updateStatusText("Select a point further along the structure...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(true);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(plugin.isAstarEnabled(), true);
				quitMenuItem.setEnabled(false);
				break;

			case SEARCHING:
				updateStatusText("Searching for path between points...");
				disableEverything();
				break;

			case QUERY_KEEP:
				updateStatusText("Keep this new path segment?");
				disableEverything();
				keepSegment.setEnabled(true);
				junkSegment.setEnabled(true);
				break;

			case FILLING_PATHS:
				updateStatusText("Filling out selected paths...");
				disableEverything();
				fmUI.setEnabledWhileFilling();
				break;

			case FITTING_PATHS:
				updateStatusText("Fitting volumes around selected paths...");
				break;

			case RUNNING_CMD:
				updateStatusText("Running Command...");
				disableEverything();
				break;

			case CACHING_DATA:
				updateStatusText("Caching data. This could take a while...");
				disableEverything();
				break;

			case CALCULATING_HESSIAN_I:
				updateStatusText("Calculating Hessian...");
				showStatus("Computing Hessian for main image...", false);
				disableEverything();
				break;

			case CALCULATING_HESSIAN_II:
				updateStatusText("Calculating Hessian (II Image)..");
				showStatus("Computing Hessian (secondary image)...", false);
				disableEverything();
				break;

			case WAITING_FOR_SIGMA_POINT_I:
				updateStatusText("Click on a representative structure...");
				showStatus("Adjusting Hessian (main image)...", false);
				//disableEverything();
				break;

			case WAITING_FOR_SIGMA_CHOICE:
				updateStatusText("Close 'Pick Sigma &amp; Max' to continue...");
				//disableEverything();
				break;

			case LOADING:
				updateStatusText("Loading...");
				disableEverything();
				break;

			case SAVING:
				updateStatusText("Saving...");
				disableEverything();
				break;

			case EDITING:
				if (noPathsError())
					return;
				plugin.setCanvasLabelAllPanes(InteractiveTracerCanvas.EDIT_MODE_LABEL);
				updateStatusText("Editing Mode. Tracing functions disabled...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				getFillManager().setVisible(false);
				showOrHideFillList.setEnabled(false);
				break;

			case SNT_PAUSED:
				updateStatusText("SNT is paused. Core functions disabled...");
				disableEverything();
				keepSegment.setEnabled(false);
				junkSegment.setEnabled(false);
				completePath.setEnabled(false);
				partsNearbyCSpinner.setEnabled(isStackAvailable());
				setEnableAutoTracingComponents(false, false);
				getFillManager().setVisible(false);
				showOrHideFillList.setEnabled(false);
				break;

			default:
				SNTUtils.error("BUG: switching to an unknown state");
				return;
			}
			SNTUtils.log("UI state: " + getState(currentState));
			plugin.updateTracingViewers(true);
		});

	}

	protected void resetState() {
		plugin.pauseTracing(!plugin.accessToValidImageData() || plugin.tracingHalted, false); // will set UI state
	}

	public void error(final String msg) {
		plugin.error(msg);
	}

	public void showMessage(final String msg, final String title) {
		plugin.showMessage(msg, title);
	}

	private boolean isStackAvailable() {
		return plugin != null && !plugin.is2D();
	}

	/* User inputs for multidimensional images */
	private JPanel sourcePanel(final ImagePlus imp) {
		final JPanel sourcePanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;
		final boolean hasChannels = imp != null && imp.getNChannels() > 1;
		final boolean hasFrames = imp != null && imp.getNFrames() > 1;
		final JPanel positionPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 4, 0));
		positionPanel.add(GuiUtils.leftAlignedLabel("Channel", true));
		final JSpinner channelSpinner = GuiUtils.integerSpinner(plugin.channel, 1,
				(hasChannels) ? imp.getNChannels() : 1, 1, true);
		positionPanel.add(channelSpinner);
		positionPanel.add(GuiUtils.leftAlignedLabel(" Frame", true));
		final JSpinner frameSpinner = GuiUtils.integerSpinner(plugin.frame, 1, (hasFrames) ? imp.getNFrames() : 1, 1, true);
		positionPanel.add(frameSpinner);
		final JButton applyPositionButton = new JButton("Reload");
		final ChangeListener spinnerListener = e -> applyPositionButton.setText(
				((int) channelSpinner.getValue() == plugin.channel && (int) frameSpinner.getValue() == plugin.frame)
						? "Reload"
						: "Apply");
		channelSpinner.addChangeListener(spinnerListener);
		frameSpinner.addChangeListener(spinnerListener);
		channelSpinner.setEnabled(hasChannels);
		frameSpinner.setEnabled(hasFrames);
		applyPositionButton.addActionListener(e -> {
			if (!plugin.accessToValidImageData()) {
				guiUtils.error("There is no valid image data to be loaded.");
				return;
			}
			final int newC = (int) channelSpinner.getValue();
			final int newT = (int) frameSpinner.getValue();
			loadImagefromGUI(newC, newT);
		});
		positionPanel.add(applyPositionButton);
		sourcePanel.add(positionPanel, gdb);
		return sourcePanel;
	}

	protected void loadImagefromGUI(final int newC, final int newT) {
		final boolean reload = newC == plugin.channel && newT == plugin.frame;
		if (!reload && askUserConfirmation
				&& !guiUtils
				.getConfirmation(
						"You are currently tracing position C=" + plugin.channel + ", T=" + plugin.frame
						+ ". Start tracing C=" + newC + ", T=" + newT + "?",
						"Change Hyperstack Position?")) {
			return;
		}
		// take this opportunity to update 3-pane status
		updateSinglePaneFlag();
		abortCurrentOperation();
		changeState(LOADING);
		plugin.reloadImage(newC, newT); // nullifies hessianData
		if (!reload)
			plugin.getImagePlus().setPosition(newC, plugin.getImagePlus().getZ(), newT);
		plugin.showMIPOverlays(0);
		if (plugin.isSecondaryDataAvailable()) {
			flushSecondaryDataPrompt();
		}
		resetState();
		showStatus(reload ? "Image reloaded into memory..." : null, true);
	}

	private JPanel viewsPanel() {
		final JPanel viewsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		gdb.gridwidth = 1;

		final CheckboxSpinner mipCS = new CheckboxSpinner(new JCheckBox("Overlay MIP(s) at"),
				GuiUtils.integerSpinner(20, 10, 80, 1, true));
		mipCS.getSpinner().addChangeListener(e -> mipCS.setSelected(false));
		mipCS.appendLabel(" % opacity");
		mipCS.getCheckBox().addActionListener(e -> {
			if (!plugin.accessToValidImageData()) {
				noValidImageDataError();
				mipCS.setSelected(false);
			} else if (plugin.is2D()) {
				guiUtils.error(plugin.getImagePlus().getTitle() + " has no depth. Cannot generate projection.");
				mipCS.setSelected(false);
			} else {
				plugin.showMIPOverlays(false, (mipCS.isSelected()) ? (int) mipCS.getValue() * 0.01 : 0);
			}
		});
		viewsPanel.add(mipCS, gdb);
		++gdb.gridy;

		final JCheckBox zoomAllPanesCheckBox = new JCheckBox("Apply zoom changes to all views",
				!plugin.isZoomAllPanesDisabled());
		zoomAllPanesCheckBox
				.addItemListener(e -> plugin.disableZoomAllPanes(e.getStateChange() == ItemEvent.DESELECTED));
		viewsPanel.add(zoomAllPanesCheckBox, gdb);
		++gdb.gridy;

		final String bLabel = (plugin.getSinglePane()) ? "Display" : "Rebuild";
		final JButton refreshPanesButton = new JButton(bLabel + " ZY/XZ Views");
		refreshPanesButton.addActionListener(e -> {
			final boolean noImageData = !plugin.accessToValidImageData();
			if (noImageData && pathAndFillManager.size() == 0) {
				guiUtils.error("No paths exist to compute side-view canvases.");
				return;
			}
			if (plugin.getImagePlus() == null) {
				guiUtils.error("There is no loaded image. Please load one or create a display canvas.",
						"No Canvas Exist");
				return;
			}
			if (plugin.is2D()) {
				guiUtils.error(plugin.getImagePlus().getTitle() + " has no depth. Cannot generate side views!");
				return;
			}
			showStatus("Rebuilding ZY/XZ views...", false);
			changeState(LOADING);
			try {
				plugin.setSinglePane(false);
				plugin.rebuildZYXZpanes();
				arrangeCanvases(false);
				showStatus("ZY/XZ views reloaded...", true);
				refreshPanesButton.setText("Rebuild ZY/XZ views");
			} catch (final Throwable t) {
				if (t instanceof OutOfMemoryError) {
					guiUtils.error("Out of Memory: There is not enough RAM to load side views!");
				} else {
					guiUtils.error("An error occured. See Console for details.");
					t.printStackTrace();
				}
				plugin.setSinglePane(true);
				if (noImageData) {
					plugin.rebuildDisplayCanvases();
					arrangeCanvases(false);
				}
				showStatus("Out of memory error...", true);
			} finally {
				resetState();
			}
		});

		rebuildCanvasButton = new JButton();
		updateRebuildCanvasButton();
		rebuildCanvasButton.addActionListener(e -> {
			if (pathAndFillManager.size() == 0) {
				guiUtils.error("No paths exist to compute a display canvas.");
				return;
			}

			String msg = "";
			if (plugin.accessToValidImageData()) {
				msg = "Replace current image with a display canvas and ";
			} else if (plugin.getPrefs().getTemp(SNTPrefs.NO_IMAGE_ASSOCIATED_DATA, false)) {
				msg = "You have loaded paths without loading an image.";
			} else if (!plugin.getPathAndFillManager().allPathsShareSameSpatialCalibration())
				msg = "You seem to have loaded paths associated with images with conflicting spatial calibration.";
			if (!msg.isEmpty()) {
				resetPathSpacings(msg);
			}

			if (!plugin.accessToValidImageData()) {
				// depending on what the user chose in the resetPathSpacings() prompt
				// we need to check again if the plugin has access to a valid image
				changeState(LOADING);
				showStatus("Resizing Canvas...", false);
				updateSinglePaneFlag();
				plugin.rebuildDisplayCanvases(); // will change UI state
				arrangeCanvases(false);
				showStatus("Canvas rebuilt...", true);
			}
		});

		final JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 2, 0));
		buttonPanel.add(rebuildCanvasButton);
		buttonPanel.add(refreshPanesButton);
		gdb.fill = GridBagConstraints.NONE;
		viewsPanel.add(buttonPanel, gdb);
		return viewsPanel;
	}

	private boolean resetPathSpacings(final String promptReason) {
		boolean nag = plugin.getPrefs().getTemp("pathscaling-nag", true);
		boolean reset = plugin.getPrefs().getTemp("pathscaling", true);
		if (nag) {
			final boolean[] options = guiUtils.getPersistentConfirmation(promptReason //
					+ " Reset spatial calibration of paths?<br>" //
					+ "This will force paths and display canvas(es) to have unitary spacing (e.g.,"//
					+ "1px&rarr;1" + GuiUtils.micrometer() + "). Path lengths will be preserved.",//
					"Reset Path Calibrations?");
			plugin.getPrefs().setTemp("pathscaling", reset = options[0]);
			plugin.getPrefs().setTemp("pathscaling-nag", !options[1]);
		}
		if (reset) {
			if (plugin.accessToValidImageData()) {
				plugin.getImagePlus().close(); 
				if (plugin.getImagePlus() != null) {
					// user canceled the "save changes" dialog
					return false;
				}
				plugin.closeAndResetAllPanes();
				plugin.tracingHalted = true;
			}
			plugin.getPathAndFillManager().resetSpatialSettings(true);
		}
		return reset;
	}

	private void validateImgDimensions() {
		if (plugin.getPrefs().getTemp(SNTPrefs.RESIZE_REQUIRED, false)) {
			boolean nag = plugin.getPrefs().getTemp("canvasResize-nag", true);
			if (nag) {
				final StringBuilder sb = new StringBuilder("Some nodes are being displayed outside the image canvas. To visualize them you can:<ul>");
				String type = "canvas";
				if (plugin.accessToValidImageData()) {
					type = "image";
					sb.append("<li>Use IJ's command Image&rarr;Adjust&rarr;Canvas Size... and press <i>Reload</i> in the Data Source widget of the Options pane</li>");
					sb.append("<li>Close the current image and create a Display Canvas using the <i>Create Canvas</i> command in the Options pane</li>");
				}
				else {
					sb.append("<li>Use the <i>Create/Resize Canvas</i> commands in the Options pane</li>");
				}
				sb.append("<li>Replace the current ").append(type).append(" using File&rarr;Choose Tracing Image...</li>");
				final Boolean userPrompt = guiUtils.getPersistentWarning(sb.toString(), "Image Needs Resizing");
				if (userPrompt != null) // do nothing if user dismissed the dialog
					plugin.getPrefs().setTemp("canvasResize-nag", !userPrompt.booleanValue());
			} else {
				showStatus("Some nodes rendered outside image!", false);
			}
		}
	}

	private void updateSinglePaneFlag() {
		if (plugin.getImagePlus(MultiDThreePanes.XZ_PLANE) == null
				&& plugin.getImagePlus(MultiDThreePanes.ZY_PLANE) == null)
			plugin.setSinglePane(true);
	}

	private JPanel tracingPanel() {
		final JPanel tPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();

		final JCheckBox confirmTemporarySegmentsCheckbox = new JCheckBox("Confirm temporary segments",
				confirmTemporarySegments);
		tPanel.add(confirmTemporarySegmentsCheckbox, gdb);
		++gdb.gridy;

		final JCheckBox confirmCheckbox = new JCheckBox("Pressing 'Y' twice finishes path", finishOnDoubleConfimation);
		final JCheckBox finishCheckbox = new JCheckBox("Pressing 'N' twice cancels path", discardOnDoubleCancellation);
		confirmTemporarySegmentsCheckbox.addItemListener(e -> {
			confirmTemporarySegments = (e.getStateChange() == ItemEvent.SELECTED);
			confirmCheckbox.setEnabled(confirmTemporarySegments);
			finishCheckbox.setEnabled(confirmTemporarySegments);
		});

		confirmCheckbox.addItemListener(e -> finishOnDoubleConfimation = (e.getStateChange() == ItemEvent.SELECTED));
		confirmCheckbox.addItemListener(e -> discardOnDoubleCancellation = (e.getStateChange() == ItemEvent.SELECTED));
		gdb.insets.left = (int) new JCheckBox("").getPreferredSize().getWidth();
		tPanel.add(confirmCheckbox, gdb);
		++gdb.gridy;
		tPanel.add(finishCheckbox, gdb);
		++gdb.gridy;
		gdb.insets.left = 0;

		final JCheckBox activateFinishedPathCheckbox = new JCheckBox("Finishing a path selects it",
				plugin.activateFinishedPath);
		GuiUtils.addTooltip(activateFinishedPathCheckbox, "Whether the path being traced should automatically be selected once finished.");
		activateFinishedPathCheckbox.addItemListener(e -> plugin.enableAutoSelectionOfFinishedPath(e.getStateChange() == ItemEvent.SELECTED));
		tPanel.add(activateFinishedPathCheckbox, gdb);
		++gdb.gridy;

		final JCheckBox requireShiftToForkCheckbox = new JCheckBox("Require 'Shift' to branch off a path", plugin.requireShiftToFork);
		GuiUtils.addTooltip(requireShiftToForkCheckbox, "When branching off a path: Use Shift+Alt+click or Alt+click at the forking node? "
				+ "NB: Alt+click is a common trigger for window dragging on Linux. Use Super+Alt+click to circumvent OS conflics.");
		requireShiftToForkCheckbox.addItemListener(e ->plugin.requireShiftToFork = e.getStateChange() == ItemEvent.SELECTED);
		tPanel.add(requireShiftToForkCheckbox, gdb);
		return tPanel;

	}

	private JPanel pathOptionsPanel() {
		final JPanel intPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		final JCheckBox diametersCheckBox = new JCheckBox("Draw diameters", plugin.getDrawDiameters());
		diametersCheckBox.addItemListener(e -> plugin.setDrawDiameters(e.getStateChange() == ItemEvent.SELECTED));
		intPanel.add(diametersCheckBox, gdb);
		++gdb.gridy;
		intPanel.add(nodePanel(), gdb);
		++gdb.gridy;
		intPanel.add(transparencyDefPanel(), gdb);
		++gdb.gridy;
		intPanel.add(transparencyOutOfBoundsPanel(), gdb);
		return intPanel;
	}

	private JPanel nodePanel() {
		final JSpinner nodeSpinner = GuiUtils.doubleSpinner((plugin.getXYCanvas() == null) ? 1 : plugin.getXYCanvas().nodeDiameter(), 0.5, 100, .5, 1);
		nodeSpinner.addChangeListener(e -> {
			final double value = (double) (nodeSpinner.getValue());
			plugin.getXYCanvas().setNodeDiameter(value);
			if (!plugin.getSinglePane()) {
				plugin.getXZCanvas().setNodeDiameter(value);
				plugin.getZYCanvas().setNodeDiameter(value);
			}
			plugin.updateTracingViewers(false);
		});
		final JButton defaultsButton = new JButton("Reset");
		defaultsButton.addActionListener(e -> {
			plugin.getXYCanvas().setNodeDiameter(-1);
			if (!plugin.getSinglePane()) {
				plugin.getXZCanvas().setNodeDiameter(-1);
				plugin.getZYCanvas().setNodeDiameter(-1);
			}
			nodeSpinner.setValue(plugin.getXYCanvas().nodeDiameter());
			showStatus("Node scale reset", true);
		});
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Rendering scale: ", true));
		c.gridx = 1;
		p.add(nodeSpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defaultsButton);
		GuiUtils.addTooltip(p, "The scaling factor for path nodes");
		return p;
	}

	private JPanel transparencyDefPanel() {
		final JSpinner defTransparencySpinner = GuiUtils.integerSpinner(
				(plugin.getXYCanvas() == null) ? 100 : plugin.getXYCanvas().getDefaultTransparency(), 0, 100, 1, true);
		defTransparencySpinner.addChangeListener(e -> {
			setDefaultTransparency((int)(defTransparencySpinner.getValue()));
		});
		final JButton defTransparencyButton = new JButton("Reset");
		defTransparencyButton.addActionListener(e -> {
			setDefaultTransparency(100);
			defTransparencySpinner.setValue(100);
			showStatus("Default transparency reset", true);
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Centerline opacity (%): ", true));
		c.gridx = 1;
		p.add(defTransparencySpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defTransparencyButton);
		GuiUtils.addTooltip(p, "Rendering opacity (0-100%) for diameters and segments connecting path nodes");
		return p;
	}

	private JPanel transparencyOutOfBoundsPanel() {
		final JSpinner transparencyOutOfBoundsSpinner = GuiUtils.integerSpinner(
				(plugin.getXYCanvas() == null) ? 100 : plugin.getXYCanvas().getOutOfBoundsTransparency(), 0, 100, 1,
				true);
		transparencyOutOfBoundsSpinner.addChangeListener(e -> {
			setOutOfBoundsTransparency((int)(transparencyOutOfBoundsSpinner.getValue()));
		});
		final JButton defaultOutOfBoundsButton = new JButton("Reset");
		defaultOutOfBoundsButton.addActionListener(e -> {
			setOutOfBoundsTransparency(50);
			transparencyOutOfBoundsSpinner.setValue(50);
			showStatus("Default transparency reset", true);
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Out-of-plane opacity (%): ", true));
		c.gridx = 1;
		p.add(transparencyOutOfBoundsSpinner, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(defaultOutOfBoundsButton);
		GuiUtils.addTooltip(p, "The opacity (0-100%) of path segments that are out-of-plane. "
				+ "Only considered when tracing 3D images and the visibility filter is "
				+ "<i>Only nodes within # nearby Z-slices</i>");
		return p;
	}

	private void setDefaultTransparency(final int value) {
		plugin.getXYCanvas().setDefaultTransparency(value);
		if (!plugin.getSinglePane()) {
			plugin.getXZCanvas().setDefaultTransparency(value);
			plugin.getZYCanvas().setDefaultTransparency(value);
		}
		plugin.updateTracingViewers(false);
	}

	private void setOutOfBoundsTransparency(final int value) {
		plugin.getXYCanvas().setOutOfBoundsTransparency(value);
		if (!plugin.getSinglePane()) {
			plugin.getXZCanvas().setOutOfBoundsTransparency(value);
			plugin.getZYCanvas().setOutOfBoundsTransparency(value);
		}
		plugin.updateTracingViewers(false);
	}

	private JPanel extraColorsPanel() {

		final LinkedHashMap<String, Color> hm = new LinkedHashMap<>();
		final InteractiveTracerCanvas canvas = plugin.getXYCanvas();
		hm.put("Canvas annotations", (canvas == null) ? null : canvas.getAnnotationsColor());
		hm.put("Fills", (canvas == null) ? null : canvas.getFillColor());
		hm.put("Temporary paths", (canvas == null) ? null : canvas.getTemporaryPathColor());
		hm.put("Unconfirmed paths", (canvas == null) ? null : canvas.getUnconfirmedPathColor());

		final JComboBox<String> colorChoice = new JComboBox<>();
		for (final Entry<String, Color> entry : hm.entrySet())
			colorChoice.addItem(entry.getKey());

		final String selectedKey = String.valueOf(colorChoice.getSelectedItem());
		final ColorChooserButton cChooser = new ColorChooserButton(hm.get(selectedKey), "Change", 1,
				SwingConstants.RIGHT);
		cChooser.setPreferredSize(new Dimension(cChooser.getPreferredSize().width, colorChoice.getPreferredSize().height));
		cChooser.setMinimumSize(new Dimension(cChooser.getMinimumSize().width, colorChoice.getMinimumSize().height));
		cChooser.setMaximumSize(new Dimension(cChooser.getMaximumSize().width, colorChoice.getMaximumSize().height));

		colorChoice.addActionListener(
				e -> cChooser.setSelectedColor(hm.get(String.valueOf(colorChoice.getSelectedItem())), false));

		cChooser.addColorChangedListener(newColor -> {
			final String selectedKey1 = String.valueOf(colorChoice.getSelectedItem());
			switch (selectedKey1) {
			case "Canvas annotations":
				plugin.setAnnotationsColorAllPanes(newColor);
				plugin.updateTracingViewers(false);
				break;
			case "Fills":
				plugin.getXYCanvas().setFillColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setFillColor(newColor);
					plugin.getXZCanvas().setFillColor(newColor);
				}
				plugin.updateTracingViewers(false);
				break;
			case "Unconfirmed paths":
				plugin.getXYCanvas().setUnconfirmedPathColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setUnconfirmedPathColor(newColor);
					plugin.getXZCanvas().setUnconfirmedPathColor(newColor);
				}
				plugin.updateTracingViewers(true);
				break;
			case "Temporary paths":
				plugin.getXYCanvas().setTemporaryPathColor(newColor);
				if (!plugin.getSinglePane()) {
					plugin.getZYCanvas().setTemporaryPathColor(newColor);
					plugin.getXZCanvas().setTemporaryPathColor(newColor);
				}
				plugin.updateTracingViewers(true);
				break;
			default:
				throw new IllegalArgumentException("Unrecognized option");
			}
		});

		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = GuiUtils.defaultGbc();
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridx = 0;
		c.gridy = 0;
		c.gridwidth = 3;
		c.ipadx = 0;
		p.add(GuiUtils.leftAlignedLabel("Colors: ", true));
		c.gridx = 1;
		p.add(colorChoice, c);
		c.fill = GridBagConstraints.NONE;
		c.gridx = 2;
		p.add(cChooser);
		return p;
	}

	private JPanel miscPanel() {
		final JPanel miscPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = GuiUtils.defaultGbc();
		miscPanel.add(extraColorsPanel(), gdb);
		++gdb.gridy;
		final JCheckBox canvasCheckBox = new JCheckBox("Activate canvas on mouse hovering",
				plugin.autoCanvasActivation);
		GuiUtils.addTooltip(canvasCheckBox, "Whether the image window should be brought to front as soon as the mouse "
				+ "pointer enters it. This may be needed to ensure single key shortcuts work as expected when tracing.");
		canvasCheckBox.addItemListener(e -> plugin.enableAutoActivation(e.getStateChange() == ItemEvent.SELECTED));
		miscPanel.add(canvasCheckBox, gdb);
		++gdb.gridy;
		final JCheckBox askUserConfirmationCheckBox = new JCheckBox("Skip confirmation dialogs", !askUserConfirmation);
		GuiUtils.addTooltip(askUserConfirmationCheckBox,
				"Whether \"Are you sure?\" prompts should precede major operations");
		askUserConfirmationCheckBox
				.addItemListener(e -> askUserConfirmation = e.getStateChange() == ItemEvent.DESELECTED);
		miscPanel.add(askUserConfirmationCheckBox, gdb);
		++gdb.gridy;
		debugCheckBox = new JCheckBox("Debug mode", SNTUtils.isDebugMode());
		debugCheckBox.addItemListener(e -> SNTUtils.setDebugMode(e.getStateChange() == ItemEvent.SELECTED));
		miscPanel.add(debugCheckBox, gdb);
		++gdb.gridy;
		final JButton prefsButton = new JButton("Preferences...");
		prefsButton.addActionListener(e -> {
			(new CmdRunner(PrefsCmd.class)).execute();
		});
		gdb.fill = GridBagConstraints.NONE;
		miscPanel.add(prefsButton, gdb);
		commandFinder.register(prefsButton, "Main", "Options tab");
		return miscPanel;
	}

	@SuppressWarnings("deprecation")
	private JPanel legacy3DViewerPanel() throws java.lang.NoClassDefFoundError {

		// Build panel
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
	
		if (!GuiUtils.isLegacy3DViewerAvailable()) {
			p.add(new JLabel("Viewer not found in your installation."));
			return p;
		}

		final String VIEWER_NONE = "None";
		final String VIEWER_WITH_IMAGE = "New with image...";
		final String VIEWER_EMPTY = "New without image";

		// Define UI components
		final JComboBox<String> univChoice = new JComboBox<>();
		final JButton applyUnivChoice = new JButton("Apply");
		final JComboBox<String> displayChoice = new JComboBox<>();
		final JButton applyDisplayChoice = new JButton("Apply");
		final JButton refreshList = GuiUtils.smallButton("Refresh List");
		final JComboBox<String> actionChoice = new JComboBox<>();
		final JButton applyActionChoice = new JButton("Apply");

		final LinkedHashMap<String, Image3DUniverse> hm = new LinkedHashMap<>();
		hm.put(VIEWER_NONE, null);
		if (!plugin.tracingHalted && !plugin.is2D()) {
			hm.put(VIEWER_WITH_IMAGE, null);
		}
		hm.put(VIEWER_EMPTY, null);
		try {
			for (final Image3DUniverse univ : Image3DUniverse.universes) {
				hm.put(univ.allContentsString(), univ);
			}
		} catch (final Exception ex) {
			SNTUtils.error("Legacy Image3DUniverse unavailable?", ex);
			hm.put("Initialization Error...", null);
		}

		// Build choices widget for viewers
		univChoice.setPrototypeDisplayValue(VIEWER_WITH_IMAGE);
		for (final Entry<String, Image3DUniverse> entry : hm.entrySet()) {
			univChoice.addItem(entry.getKey());
		}
		univChoice.addActionListener(e -> {
			final boolean none = VIEWER_NONE.equals(String.valueOf(univChoice.getSelectedItem()))
					|| String.valueOf(univChoice.getSelectedItem()).endsWith("Error...");
			applyUnivChoice.setEnabled(!none);
		});
		applyUnivChoice.addActionListener(new ActionListener() {

			private void resetChoice() {
				univChoice.setSelectedItem(VIEWER_NONE);
				applyUnivChoice.setEnabled(false);
				final boolean validViewer = plugin.use3DViewer && plugin.get3DUniverse() != null;
				displayChoice.setEnabled(validViewer);
				applyDisplayChoice.setEnabled(validViewer);
				actionChoice.setEnabled(validViewer);
				applyActionChoice.setEnabled(validViewer);
			}

			@Override
			public void actionPerformed(final ActionEvent e) {

				applyUnivChoice.setEnabled(false);

				final String selectedKey = String.valueOf(univChoice.getSelectedItem());
				if (VIEWER_NONE.equals(selectedKey)) {
					plugin.set3DUniverse(null);
					resetChoice();
					return;
				}

				Image3DUniverse univ;
				univ = hm.get(selectedKey);
				if (univ == null) {

					// Presumably a new viewer was chosen. Let's double-check
					final boolean newViewer = selectedKey.equals(VIEWER_WITH_IMAGE) || selectedKey.equals(VIEWER_EMPTY);
					if (!newViewer && !guiUtils.getConfirmation(
							"The chosen viewer does not seem to be available. Create a new one?",
							"Viewer Unavailable")) {
						resetChoice();
						return;
					}
					univ = new Image3DUniverse(512, 512);
				}

				plugin.set3DUniverse(univ);

				if (VIEWER_WITH_IMAGE.equals(selectedKey)) {

					final int defResFactor = Content.getDefaultResamplingFactor(plugin.getImagePlus(),
							ContentConstants.VOLUME);
					final Double userResFactor = guiUtils.getDouble(
							"Please specify the image resampling factor. The default factor for current image is "
							+ defResFactor + ".", "Image Resampling Factor", defResFactor);

					if (userResFactor == null) { // user pressed cancel
						plugin.set3DUniverse(null);
						resetChoice();
						return;
					}

					final int resFactor = (Double.isNaN(userResFactor) || userResFactor < 1) ? defResFactor
							: userResFactor.intValue();
					plugin.getPrefs().set3DViewerResamplingFactor(resFactor);
					plugin.updateImageContent(resFactor);
				}

				// Add PointListener/Keylistener
				new QueueJumpingKeyListener(plugin, univ);
				ImageWindow3D window = univ.getWindow();
				if (univ.getWindow() == null) {
					window = new ImageWindow3D("SNT Legacy 3D Viewer", univ);
					window.setSize(512, 512);
					univ.init(window);
				} else {
					univ.resetView();
				}
				window.addWindowListener(new WindowAdapter() {

					@Override
					public void windowClosed(final WindowEvent e) {
						resetChoice();
					}
				});
				window.setVisible(true);
				resetChoice();
				showStatus("3D Viewer enabled: " + selectedKey, true);
			}
		});

		// Build widget for rendering choices
		displayChoice.addItem("Lines and discs");
		displayChoice.addItem("Lines");
		displayChoice.addItem("Surface reconstructions");
		applyDisplayChoice.addActionListener(e -> {

			switch (String.valueOf(displayChoice.getSelectedItem())) {
			case "Lines":
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_LINES);
				break;
			case "Lines and discs":
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_LINES_AND_DISCS);
				break;
			default:
				plugin.setPaths3DDisplay(SNT.DISPLAY_PATHS_SURFACE);
				break;
			}
		});

		// Build refresh button
		refreshList.addActionListener(e -> {
			for (final Image3DUniverse univ : Image3DUniverse.universes) {
				if (hm.containsKey(univ.allContentsString()))
					continue;
				hm.put(univ.allContentsString(), univ);
				univChoice.addItem(univ.allContentsString());
			}
			showStatus("Viewers list updated...", true);
		});

		// Build actions
		class ApplyLabelsAction extends AbstractAction {

			final static String LABEL = "Apply Color Labels...";

			@Override
			public void actionPerformed(final ActionEvent e) {
				final File imageFile = openFile("Choose Labels Image...", (File) null);
				if (imageFile == null)
					return; // user pressed cancel
				try {
					plugin.statusService.showStatus(("Loading " + imageFile.getName()));
					final Dataset ds = plugin.datasetIOService.open(imageFile.getAbsolutePath());
					final ImagePlus colorImp = plugin.convertService.convert(ds, ImagePlus.class);
					showStatus("Applying color labels...", false);
					plugin.setColorImage(colorImp);
					showStatus("Labels image loaded...", true);

				} catch (final IOException exc) {
					guiUtils.error("Could not open " + imageFile.getAbsolutePath() + ". Maybe it is not a valid image?",
							"IO Error");
					exc.printStackTrace();
					return;
				}
			}
		}

		// Assemble widget for actions
		final String COMPARE_AGAINST = "Compare Reconstructions...";
		actionChoice.addItem(ApplyLabelsAction.LABEL);
		actionChoice.addItem(COMPARE_AGAINST);
		applyActionChoice.addActionListener(new ActionListener() {

			final ActionEvent ev = new ActionEvent(this, ActionEvent.ACTION_PERFORMED, null);

			@Override
			public void actionPerformed(final ActionEvent e) {

				switch (String.valueOf(actionChoice.getSelectedItem())) {
				case ApplyLabelsAction.LABEL:
					new ApplyLabelsAction().actionPerformed(ev);
					break;
				case COMPARE_AGAINST:
					if (noPathsError()) return;
					(new CmdRunner(ShowCorrespondencesCmd.class)).execute();
					break;
				default:
					break;
				}
			}
		});

		// Set defaults
		univChoice.setSelectedItem(VIEWER_NONE);
		applyUnivChoice.setEnabled(false);
		displayChoice.setEnabled(false);
		applyDisplayChoice.setEnabled(false);
		actionChoice.setEnabled(false);
		applyActionChoice.setEnabled(false);

		// row 1
		c.gridy = 0;
		c.gridx = 0;
		p.add(GuiUtils.leftAlignedLabel("Viewer: ", true), c);
		c.gridx++;
		c.weightx = 1;
		p.add(univChoice, c);
		c.gridx++;
		c.weightx = 0;
		p.add(applyUnivChoice, c);
		c.gridx++;

		// row 2
		c.gridy++;
		c.gridx = 1;
		c.gridwidth = 1;
		c.anchor = GridBagConstraints.EAST;
		c.fill = GridBagConstraints.NONE;
		p.add(refreshList, c);

		// row 3
		c.gridy++;
		c.gridx = 0;
		c.anchor = GridBagConstraints.WEST;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Mode: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(displayChoice, c);
		c.gridx++;
		c.fill = GridBagConstraints.NONE;
		p.add(applyDisplayChoice, c);
		c.gridx++;

		// row 4
		c.gridy++;
		c.gridx = 0;
		c.fill = GridBagConstraints.NONE;
		p.add(GuiUtils.leftAlignedLabel("Actions: ", true), c);
		c.gridx++;
		c.fill = GridBagConstraints.HORIZONTAL;
		p.add(actionChoice, c);
		c.gridx++;
		c.fill = GridBagConstraints.NONE;
		p.add(applyActionChoice, c);
		return p;
	}

	private void addSpacer(final JPanel panel, final GridBagConstraints c) {
		// extremely lazy implementation of a vertical spacer
		IntStream.rangeClosed(1, 4).forEach(i -> {
			panel.add(new JPanel(), c);
			c.gridy++;
		});
	}

	private JPanel largeMsg(final String msg) {
		final JTextArea ta = new JTextArea();
		final Font defFont = new JLabel().getFont();
		final Font font = defFont.deriveFont(defFont.getSize() * .85f);
		ta.setBackground(getBackground());
		ta.setEditable(false);
		ta.setMargin(null);
		ta.setColumns(20);
		ta.setBorder(null);
		ta.setAutoscrolls(true);
		ta.setLineWrap(true);
		ta.setWrapStyleWord(true);
		ta.setFocusable(false);
		ta.setText(msg);
		ta.setEnabled(false);
		ta.setFont(font);
		final JPanel p = new JPanel(new BorderLayout());
		p.setBackground(getBackground());
		p.add(ta, BorderLayout.NORTH);
		return p;
	}

	private JPanel reconstructionViewerPanel() {
		InitViewer3DSystemProperties.init(); // needs to be called as early as possible to be effective
		openRecViewer = new JButton("Open Reconstruction Viewer");
		openRecViewer.addActionListener(e -> {
			// if (noPathsError()) return;
			class RecWorker extends SwingWorker<Boolean, Object> {

				@Override
				protected Boolean doInBackground() {
					try {
						recViewer = new SNTViewer3D();
					} catch (final NoClassDefFoundError | RuntimeException exc) {
						exc.printStackTrace();
						return false;
					}
					if (pathAndFillManager.size() > 0) recViewer.syncPathManagerList();
					recViewerFrame = recViewer.show();
					return true;
				}

				@Override
				protected void done() {
					try {
						if (!get())
							no3DcapabilitiesError("Reconstruction Viewer");
					} catch (final InterruptedException | ExecutionException e) {
						guiUtils.error("Unfortunately an error occured. See Console for details.");
						e.printStackTrace();
					} finally {
						setReconstructionViewer(recViewer);
					}
				}
			}
			if (recViewer == null) {
				new RecWorker().execute();
			} else { // button should be now disable. Code below is moot.
				recViewerFrame.setVisible(true);
				recViewerFrame.toFront();
			}
		});

		// Build panel
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		panel.add(openRecViewer, gdb);
		return panel;
	}

	private JPanel sciViewerPanel() {
		openSciView = new JButton("Open sciview");
		openSciView.addActionListener(e -> {
			if (!EnableSciViewUpdateSiteCmd.isSciViewAvailable()) {
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				cmdService.run(EnableSciViewUpdateSiteCmd.class, true);
				return;
			}
			if (openingSciView && sciViewSNT != null) {
				openingSciView = false;
			}
			try {
				if (!openingSciView && sciViewSNT == null
						|| (sciViewSNT.getSciView() == null || sciViewSNT.getSciView().isClosed())) {
					openingSciView = true;
					new Thread(() -> new SciViewSNT(plugin).getSciView()).start();
				}
			} catch (final Throwable exc) {
				exc.printStackTrace();
				no3DcapabilitiesError("sciview");
			}
		});

		svSyncPathManager = new JButton("Sync Changes");
		svSyncPathManager.setToolTipText("Refreshes Viewer contents to reflect Path Manager changes");
		svSyncPathManager.addActionListener(e -> {
			if (sciViewSNT == null || sciViewSNT.getSciView() == null || sciViewSNT.getSciView().isClosed()) {
				guiUtils.error("sciview is not open.");
				openSciView.setEnabled(true);
			} else {
				sciViewSNT.syncPathManagerList();
				final String msg = (pathAndFillManager.size() == 0) ? "There are no traced paths" : "sciview synchronized";
				showStatus(msg, true);
			}
		});

		// Build panel
		final JPanel panel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		panel.add(openSciView, gdb);
		panel.add(svSyncPathManager, gdb);
		return panel;
	}

	private void no3DcapabilitiesError(final String viewer) {
		SwingUtilities.invokeLater(() -> {
			guiUtils.error(viewer + " could not be initialized. Your installation seems "
				+ "to be missing essential 3D libraries. Please use the updater to install any "
				+ "missing files. See Console for details.", "Error: Dependencies Missing");
		});
	}

	private JPanel statusButtonPanel() {
		keepSegment = GuiUtils.smallButton(hotKeyLabel("Yes", "Y"));
		keepSegment.addActionListener(listener);
		junkSegment = GuiUtils.smallButton(hotKeyLabel("No", "N"));
		junkSegment.addActionListener(listener);
		completePath = GuiUtils.smallButton(hotKeyLabel("Finish", "F"));
		completePath.addActionListener(listener);
		final JButton abortButton = GuiUtils.smallButton(hotKeyLabel(hotKeyLabel("Cancel/Esc", "C"), "Esc"));
		abortButton.addActionListener(e -> abortCurrentOperation());

		// Build panel
		return buttonPanel(keepSegment, junkSegment, completePath, abortButton);
	}

	private void registerMainButtonsInCommandFinder() {
		commandFinder.register(openRecViewer, "Main", "3D tab");
		commandFinder.register(openSciView, "Main", "3D tab");
		commandFinder.register(rebuildCanvasButton, "Main", "Options tab");
		commandFinder.register(debugCheckBox, "Main", "Options tab");
	}

	protected static JPanel buttonPanel(final JButton... buttons) {
		final JPanel p = new JPanel();
		p.setLayout(new GridBagLayout());
		final GridBagConstraints c = new GridBagConstraints();
		c.ipadx = 0;
		c.insets = new Insets(0, 0, 0, 0);
		c.anchor = GridBagConstraints.LINE_START;
		c.fill = GridBagConstraints.HORIZONTAL;
		c.gridy = 0;
		c.gridx = 0;
		c.weightx = 0.1;
		for (final JButton button: buttons) {
			p.add(button, c);
			c.gridx++;
		}
		return p;
	}

	private JPanel settingsPanel() {
		final JPanel settingsPanel = new JPanel(new GridBagLayout());
		settingsArea = new JTextArea();
		final JScrollPane sp = new JScrollPane(settingsArea);
		// sp.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_ALWAYS);
		settingsArea.setRows(4); // TODO: CHECK this height on all OSes. May be too large for MacOS
		// settingsArea.setEnabled(false);
		settingsArea.setEditable(false);
		settingsArea.setFont(settingsArea.getFont().deriveFont((float) (settingsArea.getFont().getSize() * .85)));
		settingsArea.setFocusable(false);

		final GridBagConstraints c = GuiUtils.defaultGbc();
		GuiUtils.addSeparator(settingsPanel, GuiUtils.leftAlignedLabel("Computation Settings:", true), true, c);
		c.fill = GridBagConstraints.BOTH; // avoid collapsing of panel on resizing
		++c.gridy;
		settingsPanel.add(sp, c);

		final JPopupMenu pMenu = new JPopupMenu();
		JMenuItem mi = new JMenuItem("Copy", IconFactory.getMenuIcon(GLYPH.COPY));
		mi.addActionListener(e -> {
			settingsArea.selectAll();
			settingsArea.copy();
			settingsArea.setCaretPosition(0);
		});
		pMenu.add(mi);
		mi = new JMenuItem("Refresh", IconFactory.getMenuIcon(GLYPH.REDO));
		mi.addActionListener(e -> refresh());
		pMenu.add(mi);
		settingsArea.setComponentPopupMenu(pMenu);

		return settingsPanel;
	}

	private JPanel statusPanel() {
		final JPanel statusPanel = new JPanel();
		statusPanel.setLayout(new BorderLayout());
		statusText = new JLabel("Loading SNT...");
		statusText.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createBevelBorder(BevelBorder.LOWERED),
				BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN, MARGIN)));
		statusPanel.add(statusText, BorderLayout.CENTER);
		statusPanel.add(statusButtonPanel(), BorderLayout.SOUTH);
		statusPanel.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN, MARGIN * MARGIN, MARGIN));
		return statusPanel;
	}

	private JPanel secondaryDataPanel() {

		secLayerActivateCheckbox = new JCheckBox(hotKeyLabel("Trace/Fill on Secondary Layer", "L"));
		GuiUtils.addTooltip(secLayerActivateCheckbox,
				"Whether auto-tracing should be computed on a filtered flavor of current image");
		secLayerActivateCheckbox.addActionListener(listener);

		// Major options: built-in filters vs external image
		secLayerBuiltinRadioButton = new JRadioButton("Built-in Filter  ", true); // default
		secLayerExternalRadioButton = new JRadioButton("External Image");
		secLayerBuiltinRadioButton.addActionListener(listener);
		secLayerExternalRadioButton.addActionListener(listener);
		final ButtonGroup group = new ButtonGroup();
		group.add(secLayerBuiltinRadioButton);
		group.add(secLayerExternalRadioButton);


		// Built-in filters:
		secLayerGenerate =  GuiUtils.smallButton("Choose...");
		secLayerGenerate.addActionListener(event -> {
			if (plugin.isSecondaryDataAvailable()
					&& !guiUtils.getConfirmation("An image is already loaded. Unload it?", "Discard Existing Image?")) {
				return;
			}
			plugin.flushSecondaryData();
			if (plugin.getStats().max == 0) {
				// FIXME: Frangi relies on stackMax, if this isn't computed yet
				//  the filter prompt won't work
				plugin.invalidStatsError(false);
				return;
			}
			if (plugin.accessToValidImageData()) {
				(new DynamicCmdRunner(ComputeSecondaryImg.class, null, RUNNING_CMD)).run();
			} else {
				noValidImageDataError();
			}
		});
		final JPanel builtinFilterPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		builtinFilterPanel.add(secLayerBuiltinRadioButton);
		builtinFilterPanel.add(secLayerGenerate);


		// Options for builtinFilterPanel
		final JPopupMenu builtinFilterOptionsMenu = new JPopupMenu();
		builtinFilterOptionsMenu.add(GuiUtils.leftAlignedLabel("Utilities:", false));
		final JMenuItem thicknessCmdItem = new JMenuItem("Estimate Radii (Local Thickness)...");
		thicknessCmdItem.setToolTipText("Computes the distribution of the radii of all the structures across the image");
		//thicknessCmdItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.DOTCIRCLE));
		builtinFilterOptionsMenu.add(thicknessCmdItem);
		thicknessCmdItem.addActionListener(e -> {
			(new DynamicCmdRunner(LocalThicknessCmd.class, null, RUNNING_CMD)).run();
		});

		final JButton builtinFilterOptionsButton = optionsButton(builtinFilterOptionsMenu);
		GuiUtils.addTooltip(builtinFilterOptionsButton, "Image processing utilities");

		// Options for builtinFilterPanel
		final JPopupMenu externalImgOptionsMenu = new JPopupMenu();
		secLayerExternalImgOptionsButton = optionsButton(externalImgOptionsMenu);
		GuiUtils.addTooltip(secLayerExternalImgOptionsButton, "Controls for handling external images");

		// Assemble options menu
		secLayerExternalImgLoadFlushMenuItem = new JMenuItem("Choose File...");
		secLayerExternalImgLoadFlushMenuItem.addActionListener(e -> {
			// toggle menuitem: label is updated before menu is shown as per #optionsButton
			if (plugin.isSecondaryDataAvailable()) {
				if (!guiUtils.getConfirmation("Disable access to secondary image?", "Unload Image?"))
					return;
				plugin.flushSecondaryData();
			} else {
				final File proposedFile = (plugin.getFilteredImageFile() == null) ? plugin.getPrefs().getRecentDir() : plugin.getFilteredImageFile();
				final File file = openFile("Choose Secondary Image", proposedFile);
				if (file == null)
					return;
				loadSecondaryImageFile(file);
			}
		});
		final JMenuItem revealMenuItem = new JMenuItem("Show Folder of Loaded File");
		revealMenuItem.addActionListener(e -> {
			try {
				if (!plugin.isSecondaryDataAvailable() || !plugin.isSecondaryImageFileLoaded()) {
					noSecondaryImgFileAvailableError();
					return;
				}
				if (SNTUtils.fileAvailable(plugin.getFilteredImageFile())) {
					Desktop.getDesktop().open(plugin.getFilteredImageFile().getParentFile());
					// TODO: Move to java9
					// Desktop.getDesktop().browseFileDirectory(file);
				} else {
					guiUtils.error("<HTML>Could not access<br>" + plugin.getFilteredImageFile().getAbsolutePath());
				}
			} catch (final NullPointerException | IllegalArgumentException | IOException iae) {
				guiUtils.error("An error occured: Image directory not available?");
			}
		});
		externalImgOptionsMenu.add(secLayerExternalImgLoadFlushMenuItem);
		externalImgOptionsMenu.addSeparator();
		externalImgOptionsMenu.add(revealMenuItem);

		secLayerPanel = new JPanel();
		secLayerPanel.setLayout(new GridBagLayout());
		GridBagConstraints c = GuiUtils.defaultGbc();
		c.ipadx = 0;

		// header row
		//addSeparatorWithURL(secondaryImgPanel, "Tracing on Secondary Image:", true, c);
		//c.gridy++;

		// row 0
		c.insets.top = MARGIN * 2; // separator
		secLayerPanel.add(secLayerActivateCheckbox, c);
		c.insets.top = 0;
		c.gridy++;
		c.insets.left = (int) new JCheckBox("").getPreferredSize().getWidth();

		// row 1
		JPanel filePanel = new JPanel(new BorderLayout(0,0));
		filePanel.add(builtinFilterPanel, BorderLayout.CENTER);
		filePanel.add(builtinFilterOptionsButton, BorderLayout.EAST);
		secLayerPanel.add(filePanel, c);
		c.gridy++;

		// row 2
		JPanel row2Panel = new JPanel(new BorderLayout(0,0));
		row2Panel.add(secLayerExternalRadioButton, BorderLayout.CENTER);
		row2Panel.add(secLayerExternalImgOptionsButton, BorderLayout.EAST);
		secLayerPanel.add(row2Panel, c);
		c.gridy++;

		// row 3
		secLayerExternalImgOverlayCSpinner = new CheckboxSpinner(new JCheckBox("Render in overlay at "),
				GuiUtils.integerSpinner(20, 10, 80, 1, true));
		secLayerExternalImgOverlayCSpinner.getSpinner().addChangeListener(e -> {
			secLayerExternalImgOverlayCSpinner.setSelected(false);
		});
		secLayerExternalImgOverlayCSpinner.getCheckBox().addActionListener(e -> {
			if (!plugin.isSecondaryImageFileLoaded()) {
				noSecondaryImgFileAvailableError();
				return;
			}
			plugin.showMIPOverlays(true,
					(secLayerExternalImgOverlayCSpinner.isSelected())
							? (int) secLayerExternalImgOverlayCSpinner.getValue() * 0.01 : 0);
		});
		secLayerExternalImgOverlayCSpinner.appendLabel("% opacity");
		JPanel overlayPanelHolder = new JPanel(new BorderLayout());
		overlayPanelHolder.add(secLayerExternalImgOverlayCSpinner, BorderLayout.CENTER);
		//equalizeButtons(filteredImgOptionsButton, filteredImgBrowseButton);
	//	overlayPanelHolder.add(secondaryImgOptionsButton, BorderLayout.EAST);
		c.insets.left = 2 * c.insets.left;
		secLayerPanel.add(overlayPanelHolder, c);
		c.gridy++;
		return secLayerPanel;
	}

	private void loadSecondaryImageFile(final File imgFile) {
		if (!SNTUtils.fileAvailable(imgFile)) {
			guiUtils.error("Current file path is not valid.");
			return;
		}
		plugin.secondaryImageFile = imgFile;
		loadCachedDataImage(true, plugin.secondaryImageFile);
		setFastMarchSearchEnabled(plugin.tubularGeodesicsTracingEnabled);
	}

	private JButton optionsButton(final JPopupMenu optionsMenu) {
		final JButton optionsButton =  IconFactory.getButton(IconFactory.GLYPH.OPTIONS);
		//final JButton templateButton =  IconFactory.getButton(GLYPH.OPEN_FOLDER);
		//equalizeButtons(optionsButton, templateButton);
		optionsButton.addMouseListener(new MouseAdapter() {

			@Override
			public void mousePressed(final MouseEvent e) {
				// Update menuitem labels in case we ended-up in some weird UI state
				if (plugin.isSecondaryDataAvailable()) {
					secLayerExternalImgLoadFlushMenuItem.setText("Flush Loaded Image...");
					secLayerExternalImgLoadFlushMenuItem.setIcon(IconFactory.getMenuIcon(GLYPH.TRASH));
				} else {
					secLayerExternalImgLoadFlushMenuItem.setText("Choose File...");
					secLayerExternalImgLoadFlushMenuItem.setIcon(IconFactory.getMenuIcon(GLYPH.OPEN_FOLDER));
				}
				if (optionsButton.isEnabled())
					optionsMenu.show(optionsButton, optionsButton.getWidth() / 2, optionsButton.getHeight() / 2);
			}
		});
		return optionsButton;
	}

	@SuppressWarnings("unused")
	private void equalizeButtons(final JButton b1, final JButton b2) {
		if (b1.getWidth() > b2.getWidth() || b1.getHeight() > b2.getHeight()) {
			b2.setSize(b1.getSize());
			b2.setMinimumSize(b1.getMinimumSize());
			b2.setPreferredSize(b1.getPreferredSize());
			b2.setMaximumSize(b1.getMaximumSize());
		}
		else if (b1.getWidth() < b2.getWidth() || b1.getHeight() < b2.getHeight()) {
			b1.setSize(b2.getSize());
			b1.setMinimumSize(b2.getMinimumSize());
			b1.setPreferredSize(b2.getPreferredSize());
			b1.setMaximumSize(b2.getMaximumSize());
		}
	}

	void disableSecondaryLayerComponents() {
		assert SwingUtilities.isEventDispatchThread();

		setSecondaryLayerTracingSelected(false);
		if (plugin.tubularGeodesicsTracingEnabled) {
			setFastMarchSearchEnabled(false);
		}
		if (secLayerExternalImgOverlayCSpinner.getCheckBox().isSelected()) {
			secLayerExternalImgOverlayCSpinner.getCheckBox().setSelected(false);
			plugin.showMIPOverlays(true, 0);
		}
		updateExternalImgWidgets();
	}

	protected File openFile(final String promptMsg, final String extension) {
		final String suggestFilename = (plugin.accessToValidImageData()) ? plugin.getImagePlus().getShortTitle() : "SNT_Data";
		final File suggestedFile = SNTUtils.findClosestPair(new File(plugin.getPrefs().getRecentDir(), suggestFilename), extension);
		return openFile(promptMsg, suggestedFile);
	}

	protected File openFile(final String promptMsg, final File suggestedFile) {
		final boolean focused = hasFocus(); //HACK: On MacOS this seems to help to ensure prompt is displayed as frontmost
		if (focused) toBack();
		final File openedFile = plugin.legacyService.getIJ1Helper().openDialog(promptMsg, suggestedFile);
		if (openedFile != null)
			plugin.getPrefs().setRecentDir(openedFile);
		if (focused) toFront();
		return openedFile;
	}

	protected File saveFile(final String promptMsg, final String suggestedFileName, final String fallbackExtension) {
		final File fFile = new File(plugin.getPrefs().getRecentDir(),
				(suggestedFileName == null) ? "SNT_Data" : suggestedFileName);
		final boolean focused = hasFocus();
		if (focused)
			toBack();
		final File savedFile = plugin.legacyService.getIJ1Helper().saveDialog(promptMsg, fFile, fallbackExtension);
		if (savedFile != null)
			plugin.getPrefs().setRecentDir(savedFile);
		if (focused)
			toFront();
		return savedFile;
	}

	private void loadCachedDataImage(final boolean warnUserOnMemory,
									 final File file) { // FIXME: THIS is likely all outdated now
		if (file == null) {
			throw new IllegalArgumentException("Secondary image File is null");
		}
		if (warnUserOnMemory && plugin.getImagePlus() != null) {
			final int byteDepth = 32 / 8;
			final ImagePlus tracingImp = plugin.getImagePlus();
			final long megaBytesExtra = (((long) tracingImp.getWidth()) * tracingImp.getHeight()
					* tracingImp.getNSlices() * byteDepth * 2) / (1024 * 1024);
			final long maxMemory = Runtime.getRuntime().maxMemory() / (1024 * 1024);
			if (megaBytesExtra > 0.8 * maxMemory && !guiUtils.getConfirmation( //
					"Loading an extra image will likely require " + megaBytesExtra + "MiB of " //
							+ "RAM. Currently only " + maxMemory + " MiB are available. " //
							+ "Consider enabling real-time processing.",
					"Confirm Loading?")) {
				return;
			}
		}
		loadImageData(file);
	}

	private void loadImageData(final File file) {

		showStatus("Loading image. Please wait...", false);
		changeState(CACHING_DATA);
		activeWorker = new ActiveWorker() {

			@Override
			protected String doInBackground() {

				try {
					plugin.loadSecondaryImage(file);
				} catch (final IllegalArgumentException e1) {
					return ("Could not load " + file.getAbsolutePath() + ":<br>"
							+ e1.getMessage());
				} catch (final IOException e2) {
					e2.printStackTrace();
					return ("Loading of image failed. See Console for details.");
				} catch (final OutOfMemoryError e3) {
					e3.printStackTrace();
					return ("It seems there is not enough memory to proceed. See Console for details.");
				}
				return null;
			}

			private void flushData() {
				plugin.flushSecondaryData();
			}

			@Override
			public boolean kill() {
				flushData();
				return cancel(true);
			}

			@Override
			protected void done() {
				try {
					final String errorMsg = (String) get();
					if (errorMsg != null) {
						guiUtils.error(errorMsg);
						flushData();
					}
				} catch (InterruptedException | ExecutionException e) {
					SNTUtils.error("ActiveWorker failure", e);
				}
				updateExternalImgWidgets();
				resetState();
				showStatus(null, false);
			}
		};
		activeWorker.run();
	}

	protected void updateExternalImgWidgets() {
		SwingUtilities.invokeLater(() -> {
			if (!plugin.isAstarEnabled() || plugin.tracingHalted || getState() == SNTUI.SNT_PAUSED) {
				//GuiUtils.enableComponents(secondaryImgPanel, false);
				return;
			}
			//GuiUtils.enableComponents(secondaryImgOverlayCheckbox.getParent(), successfullyLoaded);
			secLayerExternalImgOverlayCSpinner.setEnabled(plugin.isTracingOnSecondaryImageAvailable());
			secLayerExternalImgLoadFlushMenuItem.setText((plugin.isSecondaryDataAvailable()) ? "Choose File..." : "Flush Loaded Image...");
		});
	}

	@SuppressWarnings("deprecation")
	private JMenuBar createMenuBar() {
		final JMenuBar menuBar = new JMenuBar();
		final JMenu fileMenu = new JMenu("File");
		menuBar.add(fileMenu);
		final JMenu importSubmenu = new JMenu("Load Tracings");
		importSubmenu.setToolTipText("Import reconstruction file(s");
		importSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.IMPORT));
		final JMenu exportSubmenu = new JMenu("Save Tracings");
		exportSubmenu.setToolTipText("Save reconstruction(s)");
		exportSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EXPORT));
		final JMenu analysisMenu = new JMenu("Analysis");
		menuBar.add(analysisMenu);
		final JMenu utilitiesMenu = new JMenu("Utilities");
		menuBar.add(utilitiesMenu);
		final ScriptInstaller installer = new ScriptInstaller(plugin.getContext(), this);
		menuBar.add(installer.getScriptsMenu());
		final JMenu viewMenu = new JMenu("View");
		menuBar.add(viewMenu);
		menuBar.add(GuiUtils.helpMenu());

		// Options to replace image data
		final JMenu changeImpMenu = new JMenu("Choose Tracing Image");
		changeImpMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.FILE_IMAGE));
		final JMenuItem fromList = new JMenuItem("From Open Image...");
		fromList.addActionListener(e -> {
			if (plugin.isSecondaryDataAvailable()) {
				flushSecondaryDataPrompt();
			}
			(new DynamicCmdRunner(ChooseDatasetCmd.class, null, LOADING)).run();
		});
		final JMenuItem fromFile = new JMenuItem("From File...");
		fromFile.addActionListener(e -> {
			if (plugin.isSecondaryDataAvailable()) {
				flushSecondaryDataPrompt();
			}
			new ImportAction(ImportAction.IMAGE, null).run();
		});
		changeImpMenu.add(fromFile);
		changeImpMenu.add(fromList);
		fileMenu.add(changeImpMenu);
		fileMenu.addSeparator();
		fileMenu.add(importSubmenu);

		final JMenuItem fromDemo = new JMenuItem("Load Demo Dataset...", IconFactory.getMenuIcon(GLYPH.WIZARD));
		fromDemo.setToolTipText("Load sample images and/or reconstructions");
		fromDemo.addActionListener(e -> {
			if (plugin.isSecondaryDataAvailable()) {
				flushSecondaryDataPrompt();
			}
			new ImportAction(ImportAction.DEMO, null).run();
		});
		loadLabelsMenuItem = new JMenuItem("Load Labels (AmiraMesh)...");
		loadLabelsMenuItem.setToolTipText("Load neuropil labels from an AmiraMesh file");
		loadLabelsMenuItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.TAG));
		loadLabelsMenuItem.addActionListener(listener);
		fileMenu.add(loadLabelsMenuItem);
		fileMenu.add(fromDemo);
		fileMenu.addSeparator();

		fileMenu.add(exportSubmenu);
		final JMenuItem saveTable = GuiUtils.MenuItems.saveTablesAndPlots(GLYPH.TABLE);
		saveTable.addActionListener(e -> {
			(new DynamicCmdRunner(SaveMeasurementsCmd.class, null, getState())).run();
		});
		fileMenu.add(saveTable);

		fileMenu.addSeparator();
		sendToTrakEM2 = new JMenuItem("Send to TrakEM2");
		sendToTrakEM2.addActionListener(e -> plugin.notifyListeners(new SNTEvent(SNTEvent.SEND_TO_TRAKEM2)));
		fileMenu.add(sendToTrakEM2);

		final JMenuItem importGuessingType = new JMenuItem("Guess File Type...", IconFactory.getMenuIcon(GLYPH.MAGIC));
		importSubmenu.add(importGuessingType);
		importGuessingType.addActionListener(e -> {
			new ImportAction(ImportAction.ANY_RECONSTRUCTION, null).run();
		});

		final JMenuItem importJSON = new JMenuItem("JSON...");
		importSubmenu.add(importJSON);
		importJSON.addActionListener(e -> {
			new ImportAction(ImportAction.JSON, null).run();
		});
		loadSWCMenuItem = new JMenuItem("(e)SWC...");
		loadSWCMenuItem.addActionListener(listener);
		importSubmenu.add(loadSWCMenuItem);
		loadTracesMenuItem = new JMenuItem("TRACES...");
		loadTracesMenuItem.addActionListener(listener);
		importSubmenu.add(loadTracesMenuItem);

		final JMenuItem importDirectory = new JMenuItem("Directory of SWCs...", 
				IconFactory.getMenuIcon(IconFactory.GLYPH.FOLDER));
		importSubmenu.add(importDirectory);
		importDirectory.addActionListener(e -> {
			new ImportAction(ImportAction.SWC_DIR, null).run();
		});
		importSubmenu.addSeparator();
		final JMenu remoteSubmenu = new JMenu("Remote Databases");
		remoteSubmenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.DATABASE));
		final JMenuItem importFlyCircuit = new JMenuItem("FlyCircuit...");
		remoteSubmenu.add(importFlyCircuit);
		importFlyCircuit.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("loader", new FlyCircuitLoader());
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(RemoteSWCImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importInsectBrainDb = new JMenuItem("InsectBrain...");
		remoteSubmenu.add(importInsectBrainDb);
		importInsectBrainDb.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(InsectBrainImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importMouselight = new JMenuItem("MouseLight...");
		remoteSubmenu.add(importMouselight);
		importMouselight.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(MLImporterCmd.class, inputs, LOADING)).run();
		});
		final JMenuItem importNeuroMorpho = new JMenuItem("NeuroMorpho...");
		remoteSubmenu.add(importNeuroMorpho);
		importNeuroMorpho.addActionListener(e -> {
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("loader", new NeuroMorphoLoader());
			inputs.put("recViewer", null);
			(new DynamicCmdRunner(RemoteSWCImporterCmd.class, inputs, LOADING)).run();
		});
		importSubmenu.add(remoteSubmenu);

		saveMenuItem = new JMenuItem("TRACES...");
		saveMenuItem.addActionListener(listener);
		exportSubmenu.add(saveMenuItem);
		exportAllSWCMenuItem = new JMenuItem("SWC...");
		exportAllSWCMenuItem.addActionListener(listener);
		exportSubmenu.add(exportAllSWCMenuItem);

		final JMenuItem restartMenuItem = new JMenuItem("Reset and Restart...", IconFactory.getMenuIcon(IconFactory.GLYPH.RECYCLE));
		restartMenuItem.setToolTipText("Reset all preferences and restart SNT");
		restartMenuItem.addActionListener( e -> {
			CommandService cmdService = plugin.getContext().getService(CommandService.class);
			exitRequested();
			if (SNTUtils.getPluginInstance() == null) { // exit successful
				final PrefsCmd prefs = new PrefsCmd();
				prefs.setContext(plugin.getContext());
				prefs.clearAll();
				cmdService.run(SNTLoaderCmd.class, true);
			} else {
				cmdService = null;
			}
		});

		fileMenu.addSeparator();
		fileMenu.add(restartMenuItem);
		fileMenu.addSeparator();

		quitMenuItem = new JMenuItem("Quit", IconFactory.getMenuIcon(IconFactory.GLYPH.QUIT));
		quitMenuItem.addActionListener(listener);
		fileMenu.add(quitMenuItem);

		final JMenuItem measureMenuItem = GuiUtils.MenuItems.measureQuick();
		measureMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			try {
				final TreeAnalyzer ta = new TreeAnalyzer(tree);
				ta.setContext(plugin.getContext());
				if (ta.getParsedTree().isEmpty()) {
					guiUtils.error("None of the selected paths could be measured.");
					return;
				}
				ta.setTable(pmUI.getTable(), PathManagerUI.TABLE_TITLE);
				ta.run();
			}
			catch (final IllegalArgumentException ignored) {
				getPathManager().quickMeasurementsCmdError(guiUtils);
			}
		});
		final JMenuItem plotMenuItem = new JMenuItem("Reconstruction Plotter...", IconFactory.getMenuIcon(IconFactory.GLYPH.DRAFT));
		plotMenuItem.setToolTipText("Renders traced paths as vector graphics (2D)");
		plotMenuItem.addActionListener( e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final Map<String, Object> input = new HashMap<>();
			input.put("tree", tree);
			final CommandService cmdService = plugin.getContext().getService(CommandService.class);
			cmdService.run(PlotterCmd.class, true, input);
		});

		final JMenuItem convexHullMenuItem = GuiUtils.MenuItems.convexHull();
		convexHullMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			inputs.put("table", getPathManager().getTable());
			(new CmdRunner(ConvexHullCmd.class, inputs, getState())).execute();
		});
		analysisMenu.add(convexHullMenuItem);
		analysisMenu.addSeparator();

		final JMenuItem pathOrderAnalysis = new JMenuItem("Path Order Analysis",
				IconFactory.getMenuIcon(IconFactory.GLYPH.BRANCH_CODE));
		pathOrderAnalysis.setToolTipText("Horton-Strahler-like analysis based on paths rather than branches");
		pathOrderAnalysis.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final PathOrderAnalysisCmd pa = new PathOrderAnalysisCmd(tree);
			pa.setContext(plugin.getContext());
			pa.setTable(new SNTTable(), "SNT: Path Order Analysis");
			pa.run();
		});
		analysisMenu.add(pathOrderAnalysis);
		exportCSVMenuItem = new JMenuItem("Path Properties: Export CSV...", IconFactory.getMenuIcon(IconFactory.GLYPH.CSV));
		exportCSVMenuItem.setToolTipText("Export details (metrics, relationships, ...) of existing paths as tabular data");
		exportCSVMenuItem.addActionListener(listener);
		analysisMenu.add(exportCSVMenuItem);
		analysisMenu.addSeparator();
		final JMenuItem shollMenuItem = GuiUtils.MenuItems.shollAnalysis();
		shollMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getMultipleTreesInASingleContainer();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			inputs.put("snt", plugin);
			(new DynamicCmdRunner(ShollAnalysisTreeCmd.class, inputs, getState())).run();
		});
		analysisMenu.add(shollMenuItem);
		analysisMenu.add(shollAnalysisHelpMenuItem());
		final JMenuItem strahlerMenuItem = GuiUtils.MenuItems.strahlerAnalysis();
		strahlerMenuItem.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			(new CmdRunner(StrahlerCmd.class, inputs, getState())).execute();
		});
		analysisMenu.add(strahlerMenuItem);
		analysisMenu.addSeparator();

		// Measuring options : All Paths
		final JMenuItem measureWithPrompt = GuiUtils.MenuItems.measureOptions();
		measureWithPrompt.addActionListener(e -> {
			if (noPathsError()) return;
			final Collection<Tree> trees = getPathManager().getMultipleTrees();
			if (trees == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("trees", trees);
			inputs.put("calledFromPathManagerUI", true);
			(new DynamicCmdRunner(AnalyzerCmd.class, inputs)).run();
		});
		analysisMenu.add(measureWithPrompt);
		analysisMenu.add(measureMenuItem);

		// Utilities
		utilitiesMenu.add(plotMenuItem);
		final JMenuItem compareFiles = new JMenuItem("Compare Reconstructions/Cell Groups...");
		compareFiles.setToolTipText("Statistical comparisons between cell groups or individual files");
		compareFiles.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.BINOCULARS));
		utilitiesMenu.add(compareFiles);
		compareFiles.addActionListener(e -> {
			final String[] choices = { "Compare two files", "Compare groups of cells (two or more)" };
			final String defChoice = plugin.getPrefs().getTemp("compare", choices[1]);
			final String choice = guiUtils.getChoice("Which kind of comparison would you like to perform?",
					"Single or Group Comparison?", choices, defChoice);
			if (choice == null) return;
			plugin.getPrefs().setTemp("compare", choice);
			if (choices[0].equals(choice))
				(new CmdRunner(CompareFilesCmd.class)).execute();
			else {
				(new DynamicCmdRunner(GroupAnalyzerCmd.class, null)).run();
			}
		});
		utilitiesMenu.addSeparator();
		final JMenuItem graphGenerator = GuiUtils.MenuItems.createDendrogram();
		utilitiesMenu.add(graphGenerator);
		graphGenerator.addActionListener(e -> {
			if (noPathsError()) return;
			final Tree tree = getPathManager().getSingleTree();
			if (tree == null) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			inputs.put("tree", tree);
			(new DynamicCmdRunner(GraphGeneratorCmd.class, inputs)).run();
		});
		utilitiesMenu.addSeparator();
		final JMenuItem skeletonConverter = new JMenuItem("Extract Paths from Segmented Image...",
				IconFactory.getMenuIcon(IconFactory.GLYPH.TREE));
		skeletonConverter.setToolTipText("Runs automated tracing on a thresholded/binary image");
		utilitiesMenu.add(skeletonConverter);
		skeletonConverter.addActionListener(e -> {
			(new DynamicCmdRunner(SkeletonConverterCmd.class, null)).run();
		});
		utilitiesMenu.addSeparator();
		final JMenu scriptUtilsMenu = installer.getBatchScriptsMenu();
		scriptUtilsMenu.setText("Batch Scripts");
		scriptUtilsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.PLUS));
		utilitiesMenu.add(scriptUtilsMenu);

		// View menu
		final JMenuItem arrangeWindowsMenuItem = new JMenuItem("Arrange Tracing Views");
		arrangeWindowsMenuItem.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.WINDOWS));
		arrangeWindowsMenuItem.addActionListener(e -> arrangeCanvases(true));
		viewMenu.add(arrangeWindowsMenuItem);
		final JMenuItem showImpMenuItem = new JMenuItem("Display Secondary Image");
		showImpMenuItem.addActionListener(e -> {
			if (!plugin.isSecondaryDataAvailable()) {
				noSecondaryDataAvailableError();
				return;
			}
			final ImagePlus imp = plugin.getSecondaryDataAsImp();
			if (imp == null) {
				guiUtils.error("Somehow image could not be created.", "Secondary Image Unavailable?");
			} else {
				imp.show();
			}
		});
		viewMenu.add(showImpMenuItem);
		viewMenu.addSeparator();
		final JMenu hideViewsMenu = new JMenu("Hide Tracing Canvas");
		hideViewsMenu.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.EYE_SLASH));
		final JCheckBoxMenuItem xyCanvasMenuItem = new JCheckBoxMenuItem("Hide XY View");
		xyCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.XY_PLANE, xyCanvasMenuItem));
		hideViewsMenu.add(xyCanvasMenuItem);
		final JCheckBoxMenuItem zyCanvasMenuItem = new JCheckBoxMenuItem("Hide ZY View");
		zyCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.ZY_PLANE, zyCanvasMenuItem));
		hideViewsMenu.add(zyCanvasMenuItem);
		final JCheckBoxMenuItem xzCanvasMenuItem = new JCheckBoxMenuItem("Hide XZ View");
		xzCanvasMenuItem.addActionListener(e -> toggleWindowVisibility(MultiDThreePanes.XZ_PLANE, xzCanvasMenuItem));
		hideViewsMenu.add(xzCanvasMenuItem);
		final JCheckBoxMenuItem threeDViewerMenuItem = new JCheckBoxMenuItem("Hide Legacy 3D View");
		threeDViewerMenuItem.addItemListener(e -> {
			if (plugin.get3DUniverse() == null || !plugin.use3DViewer) {
				guiUtils.error("Legacy 3D Viewer is not active.");
				return;
			}
			plugin.get3DUniverse().getWindow().setVisible(e.getStateChange() == ItemEvent.DESELECTED);
		});
		hideViewsMenu.add(threeDViewerMenuItem);
		viewMenu.add(hideViewsMenu);
		return menuBar;
	}

	private JPanel renderingPanel() {

		showPathsSelected = new JCheckBox(hotKeyLabel("1. Only selected paths (hide deselected)", "1"),
				plugin.showOnlySelectedPaths);
		showPathsSelected.addItemListener(listener);

		final JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		row1.add(showPathsSelected);

		partsNearbyCSpinner = new CheckboxSpinner(new JCheckBox(hotKeyLabel("2. Only nodes within ", "2")),
				GuiUtils.integerSpinner(1, 1, 80, 1, true));
		partsNearbyCSpinner.appendLabel("nearby Z-slices");
		partsNearbyCSpinner.setToolTipText("See Options pane for display settings of out-of-plane nodes");
		partsNearbyCSpinner.getCheckBox().addItemListener(e -> {
			plugin.justDisplayNearSlices(partsNearbyCSpinner.isSelected(),
					(int) partsNearbyCSpinner.getValue());
		});
		partsNearbyCSpinner.getSpinner().addChangeListener(e -> {
			plugin.justDisplayNearSlices(true, (int) partsNearbyCSpinner.getValue());
		});

		final JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
		onlyActiveCTposition = new JCheckBox(hotKeyLabel("3. Only paths from active channel/frame", "3"));
		row3.add(onlyActiveCTposition);
		onlyActiveCTposition.addItemListener(listener);

		final JPanel panel = new JPanel();
		panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
		panel.add(row1);
		panel.add(partsNearbyCSpinner);
		panel.add(row3);
		return panel;
	}

	private JPanel colorOptionsPanel() {
		final JPanel colorOptionsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints cop_f = GuiUtils.defaultGbc();
		final JPanel colorButtonPanel = new JPanel(new GridLayout(1, 2));
		final ColorChooserButton colorChooser1 = new ColorChooserButton(plugin.selectedColor, "Selected");
		colorChooser1.setName("Color for Selected Paths");
		colorChooser1.addColorChangedListener(newColor -> plugin.setSelectedColor(newColor));
		final ColorChooserButton colorChooser2 = new ColorChooserButton(plugin.deselectedColor, "Deselected");
		colorChooser2.setName("Color for Deselected Paths");
		colorChooser2.addColorChangedListener(newColor -> plugin.setDeselectedColor(newColor));
		colorButtonPanel.add(colorChooser1);
		colorButtonPanel.add(colorChooser2);
		++cop_f.gridy;
		colorOptionsPanel.add(colorButtonPanel, cop_f);
		++cop_f.gridy;
		final JCheckBox jcheckbox = new JCheckBox("Enforce default colors (ignore color tags)");
		GuiUtils.addTooltip(jcheckbox,
				"Whether default colors above should be used even when color tags have been applied in the Path Manager");
		jcheckbox.addActionListener(e -> {
			plugin.displayCustomPathColors = !jcheckbox.isSelected();
			// colorChooser1.setEnabled(!plugin.displayCustomPathColors);
			// colorChooser2.setEnabled(!plugin.displayCustomPathColors);
			plugin.updateTracingViewers(true);
		});
		colorOptionsPanel.add(jcheckbox, cop_f);

		return colorOptionsPanel;
	}

	private JPanel snappingPanel() {

		final JPanel tracingOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		useSnapWindow = new JCheckBox(hotKeyLabel("Enable Snapping within XY", "S"), plugin.snapCursor);
		useSnapWindow.addItemListener(listener);
		tracingOptionsPanel.add(useSnapWindow);

		snapWindowXYsizeSpinner = GuiUtils.integerSpinner(plugin.cursorSnapWindowXY * 2,
				SNT.MIN_SNAP_CURSOR_WINDOW_XY, SNT.MAX_SNAP_CURSOR_WINDOW_XY * 2, 2, false);
		snapWindowXYsizeSpinner
				.addChangeListener(e -> plugin.cursorSnapWindowXY = (int) snapWindowXYsizeSpinner.getValue() / 2);
		tracingOptionsPanel.add(snapWindowXYsizeSpinner);

		final JLabel z_spinner_label = GuiUtils.leftAlignedLabel("  Z ", true);
		z_spinner_label.setBorder(new EmptyBorder(0, 2, 0, 0));
		tracingOptionsPanel.add(z_spinner_label);
		snapWindowZsizeSpinner = GuiUtils.integerSpinner(plugin.cursorSnapWindowZ * 2,
				SNT.MIN_SNAP_CURSOR_WINDOW_Z, SNT.MAX_SNAP_CURSOR_WINDOW_Z * 2, 2, false);
		snapWindowZsizeSpinner.setEnabled(isStackAvailable());
		snapWindowZsizeSpinner
				.addChangeListener(e -> plugin.cursorSnapWindowZ = (int) snapWindowZsizeSpinner.getValue() / 2);
		tracingOptionsPanel.add(snapWindowZsizeSpinner);
		GuiUtils.addTooltip(tracingOptionsPanel, "Whether the mouse pointer should snap to the brightest voxel "
				+ "searched within the specified neighborhood (in pixels). When Z=0 snapping occurs in 2D.");
		// ensure same alignment of all other panels using defaultGbc
		final JPanel container = new JPanel(new GridBagLayout());
		container.add(tracingOptionsPanel, GuiUtils.defaultGbc());
		return container;
	}

	@SuppressWarnings("unchecked")
	private JPanel aStarPanel() {
		aStarCheckBox = new JCheckBox("Enable ", plugin.isAstarEnabled());
		aStarCheckBox.addActionListener(e -> {
			boolean enable = aStarCheckBox.isSelected();
			if (!enable && askUserConfirmation
					&& !guiUtils.getConfirmation(
							"Disable computation of paths? All segmentation tasks will be disabled.",
							"Enable Manual Tracing?")) {
				aStarCheckBox.setSelected(true);
				return;
			}
			if (enable && !plugin.accessToValidImageData()) {
				aStarCheckBox.setSelected(false);
				noValidImageDataError();
				enable = false;
			} else if (enable && !plugin.inputImageLoaded()) {
				loadImagefromGUI(plugin.channel, plugin.frame);
			}
			plugin.enableAstar(enable);
		});

		searchAlgoChoice = new JComboBox<String>();
		searchAlgoChoice.addItem("A* search");
		searchAlgoChoice.addItem("NBA* search");
		searchAlgoChoice.addItem("Fast marching");
		//TODO: ensure choice reflects the current state of plugin when assembling GUI
		searchAlgoChoice.addItemListener(new ItemListener() {

			Object previousSelection = null;

			@Override
			public void itemStateChanged(ItemEvent e) {
				// This is called twice during a selection change, so handle each state accordingly
				if( e.getStateChange() == ItemEvent.DESELECTED ) {
					previousSelection = e.getItem();

				} else if ( e.getStateChange() == ItemEvent.SELECTED ) {
					final int idx = ((JComboBox<String>) e.getSource()).getSelectedIndex();
					if (idx == 0) {
						enableTracerThread();
						setFastMarchSearchEnabled(false);
					} else if (idx == 1) {
						enableNBAStar();
						setFastMarchSearchEnabled(false);
					} else if (idx == 2) {
						if (!setFastMarchSearchEnabled(true)) {
							searchAlgoChoice.setSelectedItem(previousSelection);
						}
					}
					updateSettingsString();
				}
			}
		});

		final JPanel checkboxPanel = new JPanel(new FlowLayout(FlowLayout.LEADING, 0, 0));
		checkboxPanel.add(aStarCheckBox);
		checkboxPanel.add(searchAlgoChoice);
		checkboxPanel.add(GuiUtils.leftAlignedLabel(" algorithm", true));

		final JPopupMenu optionsMenu = new JPopupMenu();
		final JButton optionsButton = optionsButton(optionsMenu);
		GuiUtils.addTooltip(optionsButton, "Algorithm settings");
		optionsMenu.add(GuiUtils.leftAlignedLabel("Data Structure:", false));
		final ButtonGroup dataStructureButtonGroup = new ButtonGroup();

		final Map<String, SNT.SearchImageType> searchMap = new LinkedHashMap<>();
		searchMap.put("Map (Lightweight)", SNT.SearchImageType.MAP);
		searchMap.put("Array (Fast)", SNT.SearchImageType.ARRAY);
		searchMap.forEach((lbl, type) -> {
			final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(lbl);
			dataStructureButtonGroup.add(rbmi);
			optionsMenu.add(rbmi);
			rbmi.setSelected(plugin.searchImageType == type);
			rbmi.addActionListener(e -> {
				plugin.searchImageType = type;
				showStatus("Active data structure: " + lbl, true);
				updateSettingsString();
			});
		});
		optionsMenu.addSeparator();

		optionsMenu.add(GuiUtils.leftAlignedLabel("Cost Function:", false));
		final ButtonGroup costFunctionButtonGroup = new ButtonGroup();
		final Map<String, SNT.CostType> costMap = new  LinkedHashMap<>();
		costMap.put("Reciprocal of Intensity (Default)", SNT.CostType.RECIPROCAL);
		costMap.put("Difference of Intensity", SNT.CostType.DIFFERENCE);
		costMap.put("Difference of Intensity Squared", SNT.CostType.DIFFERENCE_SQUARED);
		costMap.put("Probability of Intensity", SNT.CostType.PROBABILITY);
		costMap.forEach((lbl, type) -> {
			final JRadioButtonMenuItem rbmi = new JRadioButtonMenuItem(lbl);
			rbmi.setToolTipText(type.getDescription());
			costFunctionButtonGroup.add(rbmi);
			optionsMenu.add(rbmi);
			rbmi.setActionCommand(String.valueOf(type));
			rbmi.setSelected(plugin.getCostType() == type);
			rbmi.addActionListener(e -> {
				plugin.setCostType(type);
				updateSettingsString();
				showStatus("Cost function: " + lbl, true);
				SNTUtils.log("Cost function: Now using " + plugin.getCostType());
				if (type == SNT.CostType.PROBABILITY) {
					plugin.setUseSubVolumeStats(true);
				}
			});
		});

		optionsMenu.addSeparator();

		optionsMenu.add(GuiUtils.leftAlignedLabel("Image Statistics:", false));
		final ButtonGroup minMaxButtonGroup = new ButtonGroup();
		JRadioButtonMenuItem autoRbmi = new JRadioButtonMenuItem("Compute Real-Time", plugin.getUseSubVolumeStats());
		minMaxButtonGroup.add(autoRbmi);
		optionsMenu.add(autoRbmi);
		autoRbmi.addActionListener(e -> plugin.setUseSubVolumeStats(autoRbmi.isSelected()));
		final JRadioButtonMenuItem manRbmi = new JRadioButtonMenuItem("Specify Manually...", !plugin.getUseSubVolumeStats());
		minMaxButtonGroup.add(manRbmi);
		optionsMenu.add(manRbmi);
		manRbmi.addActionListener(e -> {
			final boolean prevStatsState = plugin.getUseSubVolumeStats();
			final boolean successfullySet = setMinMaxFromUser();
			final boolean newStatsState = (successfullySet) ? false : prevStatsState;
			plugin.setUseSubVolumeStats(newStatsState);
			manRbmi.setSelected(!newStatsState);
			autoRbmi.setSelected(newStatsState);
		});
		aStarPanel = new JPanel(new BorderLayout());
		aStarPanel.add(checkboxPanel, BorderLayout.CENTER);
		aStarPanel.add(optionsButton, BorderLayout.EAST);
		return aStarPanel;
	}

	private void enableTracerThread() {
		plugin.setSearchType(SNT.SearchType.ASTAR);
	}

	private void enableNBAStar() {
		plugin.setSearchType(SNT.SearchType.NBASTAR);
	}

	private JPanel hideWindowsPanel() {
		showOrHidePathList = new JButton("Show Path Manager");
		showOrHidePathList.addActionListener(listener);
		showOrHideFillList = new JButton("Show Fill Manager");
		showOrHideFillList.addActionListener(listener);
		final JPanel hideWindowsPanel = new JPanel(new GridBagLayout());
		final GridBagConstraints gdb = new GridBagConstraints();
		gdb.fill = GridBagConstraints.HORIZONTAL;
		gdb.weightx = 0.5;
		hideWindowsPanel.add(showOrHidePathList, gdb);
		gdb.gridx = 1;
		hideWindowsPanel.add(showOrHideFillList, gdb);
		return hideWindowsPanel;
	}

	private JPanel statusBar() {
		final JPanel statusBar = new JPanel();
		statusBar.setLayout(new BoxLayout(statusBar, BoxLayout.X_AXIS));
		statusBarText = GuiUtils.leftAlignedLabel("Ready to trace...", true);
		statusBarText.setBorder(BorderFactory.createEmptyBorder(0, MARGIN, MARGIN / 2, 0));
		statusBar.add(statusBarText);
		refreshStatus();
		return statusBar;
	}

	boolean setFastMarchSearchEnabled(boolean enable) {
		if (enable && isFastMarchSearchAvailable()) {
			plugin.tubularGeodesicsTracingEnabled = true;
			searchAlgoChoice.setSelectedIndex(2);
			return true;
		} else {
			plugin.tubularGeodesicsTracingEnabled = false;
			if (plugin.tubularGeodesicsThread != null) {
				plugin.tubularGeodesicsThread.requestStop();
				plugin.tubularGeodesicsThread = null;
			}
			return false;
		}
	}

	private boolean isFastMarchSearchAvailable() {
		final boolean tgInstalled = Types.load("FijiITKInterface.TubularGeodesics") != null;
		final boolean tgAvailable = plugin.tubularGeodesicsTracingEnabled;
		if (!tgAvailable || !tgInstalled) {
			final StringBuilder msg = new StringBuilder(
					"Fast marching requires the <i>TubularGeodesics</i> plugin to be installed ")
							.append("and an <i>oof.tif</i> secondary image to be loaded. Currently, ");
			if (!tgInstalled && !tgAvailable) {
				msg.append("neither conditions are fullfilled.");
			} else if (!tgInstalled) {
				msg.append("the plugin is not installed.");
			} else {
				msg.append("the secondary image does not seem to be valid.");
			}
			guiUtils.error(msg.toString(), "Error", "https://imagej.net/plugins/snt/tubular-geodesics");
		}
		return tgInstalled && tgAvailable;
	}

	private void refreshStatus() {
		showStatus(null, false);
	}

	/**
	 * Updates the status bar.
	 *
	 * @param msg       the text to displayed. Set it to null (or empty String) to
	 *                  reset the status bar.
	 * @param temporary if true and {@code msg} is valid, text is displayed
	 *                  transiently for a couple of seconds
	 */
	public void showStatus(final String msg, final boolean temporary) {
		SwingUtilities.invokeLater(() -> {
			final boolean validMsg = !(msg == null || msg.isEmpty());
			if (validMsg && !temporary) {
				statusBarText.setText(msg);
				return;
			}

			final String defaultText;
			if (!plugin.accessToValidImageData()) {
				defaultText = "Image data unavailable...";
			} else {
				defaultText = "Tracing " + StringUtils.abbreviate(plugin.getImagePlus().getShortTitle(), 25) + ", C=" + plugin.channel + ", T="
						+ plugin.frame;
			}

			if (!validMsg) {
				statusBarText.setText(defaultText);
				return;
			}

			final Timer timer = new Timer(3000, e -> statusBarText.setText(defaultText));
			timer.setRepeats(false);
			timer.start();
			statusBarText.setText(msg);
		});
	}

	public void setLookAndFeel(final String lookAndFeelName) {
		final ArrayList<Component> components = new ArrayList<>();
		components.add(SNTUI.this);
		components.add(getPathManager());
		components.add(getPathManager());
		components.add(getFillManager());
		if (plugin.getXYCanvas() != null)
			plugin.getXYCanvas().setLookAndFeel(lookAndFeelName);
		if (plugin.getXZCanvas() != null)
			plugin.getXZCanvas().setLookAndFeel(lookAndFeelName);
		if (plugin.getZYCanvas() != null)
			plugin.getZYCanvas().setLookAndFeel(lookAndFeelName);
		final Viewer3D recViewer = getReconstructionViewer(false);
		if (recViewer != null)
			recViewer.setLookAndFeel(lookAndFeelName);
		GuiUtils.setLookAndFeel(lookAndFeelName, false, components.toArray(new Component[0]));
	}

	private JPanel getTab() {
		final JPanel tab = new JPanel();
		tab.setBorder(BorderFactory.createEmptyBorder(MARGIN, MARGIN / 2, MARGIN / 2, MARGIN));
		tab.setLayout(new GridBagLayout());
		return tab;
	}

	protected void displayOnStarting() {
		SwingUtilities.invokeLater(() -> {
			if (plugin.getPrefs().isSaveWinLocations())
				arrangeDialogs();
			arrangeCanvases(false);
			resetState();
			updateSettingsString();
			pack();
			pathAndFillManager.resetListeners(null, true); // update Path lists
			setPathListVisible(true, false);
			setFillListVisible(false);
			setVisible(true);
			SNTUtils.setIsLoading(false);
			if (plugin.getImagePlus()!=null) plugin.getImagePlus().getWindow().toFront();
		});
	}

	@SuppressWarnings("unused")
	private Double getZFudgeFromUser() {
		final double defaultValue = new OneMinusErf(0,0,0).getZFudge();
		String promptMsg = "Enter multiplier for intensity z-score. "//
				+ "Values < 1 make it easier to numerically distinguish bright voxels. "//
				+ "The current default is "//
				+ SNTUtils.formatDouble(defaultValue, 2) + ".";
		final Double fudge = guiUtils.getDouble(promptMsg, "Z-score fudge", defaultValue);
		if (fudge == null) {
			return null; // user pressed cancel
		}
		if (Double.isNaN(fudge) || fudge < 0) {
			guiUtils.error("Fudge must be a positive number.", "Invalid Input");
			return null;
		}
		return fudge;
	}

	/* returns true if min/max successfully set by user */
	private boolean setMinMaxFromUser() {
		final String choice = getPrimarySecondaryImgChoice("Adjust range for which image?");
		if (choice == null) return false;

		final boolean useSecondary = "Secondary".equalsIgnoreCase(choice);
		final float[] defaultValues = new float[2];
		defaultValues[0] = useSecondary ? (float)plugin.getStatsSecondary().min : (float)plugin.getStats().min;
		defaultValues[1] = useSecondary ? (float)plugin.getStatsSecondary().max : (float)plugin.getStats().max;
		String promptMsg = "Enter the min-max range for the A* search";
		if (useSecondary)
			promptMsg += " for secondary image";
		promptMsg +=  ". Values less than or equal to <i>Min</i> maximize the A* cost function. "
				+ "Values greater than or equal to <i>Max</i> minimize the A* cost function. "//
				+ "The current min-max range is " + defaultValues[0] + "-" + defaultValues[1];
		// FIXME: scientific notation is parsed incorrectly
		final float[] minMax = guiUtils.getRange(promptMsg, "Setting Min-Max Range",
				defaultValues);
		if (minMax == null) {
			return false; // user pressed cancel
		}
		if (Double.isNaN(minMax[0]) || Double.isNaN(minMax[1])) {
			guiUtils.error("Invalid range. Please specify two valid numbers separated by a single hyphen.");
			return false;
		}
		if (useSecondary) {
			plugin.getStatsSecondary().min = minMax[0];
			plugin.getStatsSecondary().max = minMax[1];
		} else {
			plugin.getStats().min = minMax[0];
			plugin.getStats().max = minMax[1];
		}
		updateSettingsString();
		return true;
	}

	private String getPrimarySecondaryImgChoice(final String promptMsg) {
		if (plugin.isTracingOnSecondaryImageAvailable()) {
			final String[] choices = new String[] { "Primary (Main)", "Secondary" };
			final String defChoice = plugin.getPrefs().getTemp("pschoice", choices[0]);
			final String choice = guiUtils.getChoice(promptMsg, "Which Image?", choices, defChoice);
			if (choice != null) {
				plugin.getPrefs().setTemp("pschoice", choice);
				secLayerActivateCheckbox.setSelected(choices[1].equals(choice));
			}
			return choice;
		}
		return "primary";
	}

	private void arrangeDialogs() {
		Point loc = plugin.getPrefs().getPathWindowLocation();
		if (loc != null)
			pmUI.setLocation(loc);
		loc = plugin.getPrefs().getFillWindowLocation();
		if (loc != null)
			fmUI.setLocation(loc);
		// final GraphicsDevice activeScreen =
		// getGraphicsConfiguration().getDevice();
		// final int screenWidth = activeScreen.getDisplayMode().getWidth();
		// final int screenHeight = activeScreen.getDisplayMode().getHeight();
		// final Rectangle bounds =
		// activeScreen.getDefaultConfiguration().getBounds();
		//
		// setLocation(bounds.x, bounds.y);
		// pw.setLocation(screenWidth - pw.getWidth(), bounds.y);
		// fw.setLocation(bounds.x + getWidth(), screenHeight - fw.getHeight());
	}

	private void arrangeCanvases(final boolean displayErrorOnFailure) {

		final ImageWindow xy_window = (plugin.getImagePlus()==null) ? null : plugin.getImagePlus().getWindow();
		if (xy_window == null) {
			if (displayErrorOnFailure)
				guiUtils.error("XY view is not available");
			return;
		}

		// Adjust and uniformize zoom levels
		final boolean zoomSyncStatus = plugin.isZoomAllPanesDisabled();
		plugin.disableZoomAllPanes(false);
		double zoom = plugin.getImagePlus().getCanvas().getMagnification();
		if (plugin.getImagePlus().getWidth() < 500d && plugin.getImagePlus().getCanvas().getMagnification() == 1) {
			// if the image is rather small (typically a display canvas), zoom it to more manageable dimensions
			zoom = ImageCanvas.getLowerZoomLevel(500d/plugin.getImagePlus().getWidth() * Math.min(1.5, Prefs.getGuiScale()));
		}
		plugin.zoomAllPanes(zoom);
		plugin.disableZoomAllPanes(zoomSyncStatus);

		final GraphicsConfiguration xy_config = xy_window.getGraphicsConfiguration();
		final GraphicsDevice xy_screen = xy_config.getDevice();
		final Rectangle xy_screen_bounds = xy_screen.getDefaultConfiguration().getBounds();

		// Center the main tracing canvas on the screen it was found
		final int x = (xy_screen_bounds.width / 2) - (xy_window.getWidth() / 2) + xy_screen_bounds.x;
		final int y = (xy_screen_bounds.height / 2) - (xy_window.getHeight() / 2) + xy_screen_bounds.y;
		xy_window.setLocation(x, y);

		final ImagePlus zy = plugin.getImagePlus(MultiDThreePanes.ZY_PLANE);
		if (zy != null && zy.getWindow() != null) {
			zy.getWindow().setLocation(x + xy_window.getWidth() + 5, y);
			zy.getWindow().toFront();
		}
		final ImagePlus xz = plugin.getImagePlus(MultiDThreePanes.XZ_PLANE);
		if (xz != null && xz.getWindow() != null) {
			xz.getWindow().setLocation(x, y + xy_window.getHeight() + 2);
			xz.getWindow().toFront();
		}
		xy_window.toFront();
	}

	private void toggleWindowVisibility(final int pane, final JCheckBoxMenuItem mItem) {
		final ImagePlus imp = plugin.getImagePlus(pane);
		if (imp == null) {
			String msg;
			if (pane == MultiDThreePanes.XY_PLANE) {
				msg = "XY view is not available.";
			} else if (plugin.getSinglePane()) {
				msg = "View does not exist. To generate ZY/XZ " + "views run \"Display ZY/XZ views\".";
			} else {
				msg = "View is no longer accessible. " + "You can (re)build it using \"Rebuild ZY/XZ views\".";
			}
			guiUtils.error(msg);
			mItem.setSelected(false);
			return;
		}
		// NB: WindowManager list won't be notified
		imp.getWindow().setVisible(!mItem.isSelected());
	}

	private boolean noPathsError() {
		final boolean noPaths = pathAndFillManager.size() == 0;
		if (noPaths)
			guiUtils.error("There are no traced paths.");
		return noPaths;
	}

	private void setPathListVisible(final boolean makeVisible, final boolean toFront) {
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			pmUI.setVisible(true);
			if (toFront)
				pmUI.toFront();
			if (showOrHidePathList != null)
				showOrHidePathList.setText("  Hide Path Manager");
		} else {
			if (showOrHidePathList != null)
				showOrHidePathList.setText("Show Path Manager");
			pmUI.setVisible(false);
		}
	}

	private void togglePathListVisibility() {
		assert SwingUtilities.isEventDispatchThread();
		synchronized (pmUI) {
			setPathListVisible(!pmUI.isVisible(), true);
		}
	}

	protected void setFillListVisible(final boolean makeVisible) {
		assert SwingUtilities.isEventDispatchThread();
		if (makeVisible) {
			fmUI.setVisible(true);
			if (showOrHideFillList != null)
				showOrHideFillList.setText("  Hide Fill Manager");
			fmUI.toFront();
		} else {
			if (showOrHideFillList != null)
				showOrHideFillList.setText("Show Fill Manager");
			fmUI.setVisible(false);
		}
	}

	private void toggleFillListVisibility() {
		assert SwingUtilities.isEventDispatchThread();
		if (!plugin.accessToValidImageData()) {
			guiUtils.error("Paths can only be filled when valid image data is available.");
		} else {
			synchronized (fmUI) {
				setFillListVisible(!fmUI.isVisible());
			}
		}
	}

	protected boolean nearbySlices() {
		assert SwingUtilities.isEventDispatchThread();
		return partsNearbyCSpinner.isSelected();
	}

	private JMenuItem shollAnalysisHelpMenuItem() {
		final JMenuItem mi = new JMenuItem("Sholl Analysis (by Focal Point)...");
		mi.setToolTipText("Instructions on how to perform Sholl from a specific node");
		mi.setIcon(IconFactory.getMenuIcon(IconFactory.GLYPH.DOTCIRCLE));
		mi.addActionListener(e -> {
			final Thread newThread = new Thread(() -> {
				if (noPathsError())
					return;
				final String modKey = "Alt+Shift";
				final String url1 = ShollUtils.URL + "#Analysis_of_Traced_Cells";
				final String url2 = "https://imagej.net/plugins/snt/analysis#Sholl_Analysis";
				final StringBuilder sb = new StringBuilder();
				sb.append("<html>");
				sb.append("<div WIDTH=500>");
				sb.append("To initiate <a href='").append(ShollUtils.URL).append("'>Sholl Analysis</a>, ");
				sb.append("you must select a focal point. You can do it coarsely by ");
				sb.append("right-clicking near a node and choosing <i>Sholl Analysis at Nearest ");
				sb.append("Node</i> from the contextual menu (Shortcut: \"").append(modKey).append("+A\").");
				sb.append("<p>Alternatively, for precise positioning of the center of analysis:</p>");
				sb.append("<ol>");
				sb.append("<li>Mouse over the path of interest. Press \"G\" to activate it</li>");
				sb.append("<li>Press \"").append(modKey).append("\" to select a node along the path</li>");
				sb.append("<li>Press \"").append(modKey).append("+A\" to start analysis</li>");
				sb.append("</ol>");
				sb.append("A walkthrough of this procedure is <a href='").append(url2)
						.append("'>available online</a>. ");
				sb.append("For batch processing, run <a href='").append(url1)
						.append("'>Analyze>Sholl>Sholl Analysis (From Tracings)...</a>. ");
				GuiUtils.showHTMLDialog(sb.toString(), "Sholl Analysis How-to");
			});
			newThread.start();
		});
		return mi;
	}

	public void setSigmaPaletteListener(final SigmaPaletteListener listener) {
		sigmaPaletteListener = listener;
	}

	/**
	 * Gets the Path Manager dialog.
	 *
	 * @return the {@link PathManagerUI} associated with this UI
	 */
	public PathManagerUI getPathManager() {
		return pmUI;
	}

	/**
	 * Gets the Fill Manager dialog.
	 *
	 * @return the {@link FillManagerUI} associated with this UI
	 */
	public FillManagerUI getFillManager() {
		return fmUI;
	}

	/**
	 * Gets the Reconstruction Viewer.
	 *
	 * @param initializeIfNull it true, initializes the Viewer if it has not yet
	 *                         been initialized
	 * @return the reconstruction viewer
	 */
	public Viewer3D getReconstructionViewer(final boolean initializeIfNull) {
		if (initializeIfNull && recViewer == null) {
			recViewer = new SNTViewer3D();
			recViewer.show();
			setReconstructionViewer(recViewer);
		}
		return recViewer;
	}

	/**
	 * Gets the active getSciViewSNT (SciView<>SNT bridge) instance.
	 *
	 * @param initializeIfNull it true, initializes SciView if it has not yet
	 *                         been initialized
	 * @return the SciView viewer
	 */
	public SciViewSNT getSciViewSNT(final boolean initializeIfNull) {
		if (initializeIfNull && sciViewSNT == null) {
			openSciView.doClick();
		}
		return (sciViewSNT == null) ? null : sciViewSNT;
	}

	protected void setReconstructionViewer(final Viewer3D recViewer) {
		this.recViewer = recViewer;
		openRecViewer.setEnabled(recViewer == null);
	}

	protected void setSciViewSNT(final SciViewSNT sciViewSNT) {
		this.sciViewSNT = sciViewSNT;
		SwingUtilities.invokeLater(() -> {
			openingSciView = openingSciView && this.sciViewSNT != null;
			openSciView.setEnabled(this.sciViewSNT == null);
			svSyncPathManager.setEnabled(this.sciViewSNT != null);
		});
	}

	protected void reset() {
		abortCurrentOperation();
		resetState();
		showStatus("Resetting", true);
	}

	protected void inputImageChanged() {
		final ImagePlus imp = plugin.getImagePlus();
		partsNearbyCSpinner.setSpinnerMinMax(1, plugin.getDepth());
		partsNearbyCSpinner.setEnabled(imp != null && !plugin.is2D());
		plugin.justDisplayNearSlices(partsNearbyCSpinner.isSelected(),
				(int) partsNearbyCSpinner.getValue(), false);
		final JPanel newSourcePanel = sourcePanel(imp);
		final GridBagLayout layout = (GridBagLayout) newSourcePanel.getLayout();
		for (int i = 0; i < sourcePanel.getComponentCount(); i++) {
			sourcePanel.remove(i);
			final Component component = newSourcePanel.getComponent(i);
			sourcePanel.add(component, layout.getConstraints(component));
		}
		revalidate();
		repaint();
		final boolean validImage = plugin.accessToValidImageData();
		plugin.enableAstar(validImage);
		plugin.enableSnapCursor(validImage);
		resetState();
		arrangeCanvases(false);
	}

	protected void abortCurrentOperation() {// FIXME: MOVE TO SNT?
		if (commandFinder != null)
			commandFinder.setVisible(false);
		switch (currentState) {
		case (SEARCHING):
			updateStatusText("Cancelling path search...", true);
			plugin.cancelSearch(false);
			break;
		case (CACHING_DATA):
			updateStatusText("Unloading cached data", true);
			break;
		case (RUNNING_CMD):
			updateStatusText("Requesting command cancellation", true);
			break;
		case (CALCULATING_HESSIAN_I):
		case (CALCULATING_HESSIAN_II):
			updateStatusText("Cancelling Hessian generation...", true);
			break;
		case (WAITING_FOR_SIGMA_POINT_I):
			if (sigmaPalette != null) sigmaPalette.dismiss();
			showStatus("Sigma adjustment cancelled...", true);
			break;
		case (PARTIAL_PATH):
			showStatus("Last temporary path cancelled...", true);
			plugin.cancelTemporary();
			if (plugin.currentPath != null)
				plugin.cancelPath();
			break;
		case (QUERY_KEEP):
			showStatus("Last segment cancelled...", true);
			if (plugin.temporaryPath != null)
				plugin.cancelTemporary();
			plugin.cancelPath();
			break;
		case (FILLING_PATHS):
			showStatus("Filling out cancelled...", true);
			plugin.stopFilling(); // will change UI state
			plugin.discardFill();
			return;
		case (FITTING_PATHS):
			showStatus("Fitting cancelled...", true);
			pmUI.cancelFit(true); // will change UI state
			return;
		case (SNT_PAUSED):
			showStatus("SNT is now active...", true);
			if (plugin.getImagePlus() != null)
				plugin.getImagePlus().unlock();
			plugin.pause(false, false); // will change UI state
			return;
		case (TRACING_PAUSED):
			if (!plugin.accessToValidImageData()) {
				showStatus("All tasks terminated", true);
				return;
			}
			showStatus("Tracing is now active...", true);
			plugin.pauseTracing(false, false); // will change UI state
			return;
		case (EDITING):
			showStatus("Exited from 'Edit Mode'...", true);
			plugin.enableEditMode(false); // will change UI state
			return;
		case (WAITING_FOR_SIGMA_CHOICE):
			showStatus("Close the sigma palette to abort sigma input...", true);
			return; // do nothing: Currently we have no control over the sigma
					// palette window
		case (WAITING_TO_START_PATH):
			// If user is aborting something in this state, something
			// went awry!?. Try to abort all possible lingering tasks
			pmUI.cancelFit(true);
			plugin.cancelSearch(true);
			if (plugin.currentPath != null)
				plugin.cancelPath();
			if (plugin.temporaryPath != null)
				plugin.cancelTemporary();
			showStatus("All tasks terminated", true);
			return;
		default:
			break;
		}
		if (activeWorker != null && !activeWorker.isDone()) activeWorker.kill();
		changeState(WAITING_TO_START_PATH);
	}

	protected void launchSigmaPaletteAround(final int x, final int y) {

		final int either_side_xy = 40;
		final int either_side_z = 15;
		final int z = plugin.getImagePlus().getZ();
		int x_min = x - either_side_xy;
		int x_max = x + either_side_xy;
		int y_min = y - either_side_xy;
		int y_max = y + either_side_xy;
		int z_min = z - either_side_z; // 1-based index
		int z_max = z + either_side_z; // 1-based index

		final int originalWidth = plugin.getImagePlus().getWidth();
		final int originalHeight = plugin.getImagePlus().getHeight();
		final int originalDepth = plugin.getImagePlus().getNSlices();

		if (x_min < 0) x_min = 0;
		if (y_min < 0) y_min = 0;
		if (z_min < 1) z_min = 1;
		if (x_max >= originalWidth) x_max = originalWidth - 1;
		if (y_max >= originalHeight) y_max = originalHeight - 1;
		if (z_max > originalDepth) z_max = originalDepth;

		final double[] sigmas = new double[16];
		for (int i = 0; i < sigmas.length; ++i) {
			sigmas[i] = ((i + 1) * plugin.getMinimumSeparation()) / 2;
		}

		sigmaPalette = new SigmaPalette(plugin);
		sigmaPalette.makePalette(x_min, x_max, y_min, y_max, z_min, z_max, sigmas, 4, 4, z);
		sigmaPalette.addListener(sigmaPaletteListener);
		if (sigmaPaletteListener != null) sigmaPalette.setParent(sigmaPaletteListener.getParent());
		updateStatusText("Adjusting \u03C3 and max visually...");
	}

	private String getState(final int state) {
		switch (state) {
		case READY:
			return "READY";
		case PARTIAL_PATH:
			return "PARTIAL_PATH";
		case SEARCHING:
			return "SEARCHING";
		case QUERY_KEEP:
			return "QUERY_KEEP";
		case CACHING_DATA:
			return "CACHING_DATA";
		case RUNNING_CMD:
			return "RUNNING_CMD";
		case FILLING_PATHS:
			return "FILLING_PATHS";
		case CALCULATING_HESSIAN_I:
			return "CALCULATING_HESSIAN_I";
		case CALCULATING_HESSIAN_II:
			return "CALCULATING_HESSIAN_II";
		case WAITING_FOR_SIGMA_POINT_I:
			return "WAITING_FOR_SIGMA_POINT_I";
		case WAITING_FOR_SIGMA_CHOICE:
			return "WAITING_FOR_SIGMA_CHOICE";
		case SAVING:
			return "SAVING";
		case LOADING:
			return "LOADING";
		case FITTING_PATHS:
			return "FITTING_PATHS";
		case EDITING:
			return "EDITING_MODE";
		case SNT_PAUSED:
			return "PAUSED";
		case TRACING_PAUSED:
			return "ANALYSIS_MODE";
		default:
			return "UNKNOWN";
		}
	}

	protected void togglePathsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		showPathsSelected.setSelected(!showPathsSelected.isSelected());
	}

	protected void setSecondaryLayerTracingSelected(final boolean enable) {
		assert SwingUtilities.isEventDispatchThread();
		secLayerActivateCheckbox.setSelected(enable);
		updateSettingsString();
	}

	private void noSecondaryImgFileAvailableError() {
		guiUtils.error("No external secondary image has been loaded. Please load it first.", "External Image Unavailable");
		secLayerExternalImgOverlayCSpinner.getCheckBox().setSelected(false);
		secLayerExternalRadioButton.setSelected(false);
	}

	void noSecondaryDataAvailableError() {
		guiUtils.error("No secondary image has been defined. Please create or load one first.", "Secondary Image Unavailable");
		plugin.enableSecondaryLayerTracing(false);
	}

	protected void toggleSecondaryLayerTracing() {
		assert SwingUtilities.isEventDispatchThread();
		if (secLayerActivateCheckbox.isEnabled()) plugin.enableSecondaryLayerTracing(!secLayerActivateCheckbox.isSelected());
	}

	protected void toggleSecondaryLayerBuiltin() {
		assert SwingUtilities.isEventDispatchThread();
		if (secLayerBuiltinRadioButton.isEnabled()) enableSecondaryLayerBuiltin(!secLayerBuiltinRadioButton.isSelected());
	}

	protected void toggleSecondaryLayerExternal() {
		assert SwingUtilities.isEventDispatchThread();
		if (secLayerExternalRadioButton.isEnabled()) enableSecondaryLayerExternal(!secLayerExternalRadioButton.isSelected());
	}

	protected void enableSecondaryLayerBuiltin(final boolean enable) {
		assert SwingUtilities.isEventDispatchThread();

		if (!secLayerActivateCheckbox.isEnabled()) {
			// Do nothing if we are not allowed to enable FilteredImgTracing
			showStatus("Ignored: Operation not available", true);
			return;
		}
		secLayerBuiltinRadioButton.setSelected(enable); // will not trigger ActionEvent
		updateSettingsString();
		showStatus("Hessian " + ((enable) ? "enabled" : "disabled"), true);
	}

	protected void enableSecondaryLayerExternal(final boolean enable) {
		assert SwingUtilities.isEventDispatchThread();

		if (!secLayerActivateCheckbox.isEnabled()) {
			// Do nothing if we are not allowed to enable FilteredImgTracing
			showStatus("Ignored: Operation not available", true);
			return;
		}
		secLayerExternalRadioButton.setSelected(enable); // will not trigger ActionEvent
		updateSettingsString();
		showStatus("External Image " + ((enable) ? "enabled" : "disabled"), true);
	}

	/** Should only be called by {@link SNT#enableAstar(boolean)} */
	protected void enableAStarGUI(final boolean enable) {
		SwingUtilities.invokeLater(() -> {
			aStarCheckBox.setSelected(enable);
			setEnableAutoTracingComponents(enable, true);
			showStatus("A* " + ((enable) ? "enabled" : "disabled"), true);
		});
	}

	protected void togglePartsChoice() {
		assert SwingUtilities.isEventDispatchThread();
		partsNearbyCSpinner.getCheckBox().setSelected(!partsNearbyCSpinner.getCheckBox().isSelected());
	}

	protected void toggleChannelAndFrameChoice() {
		assert SwingUtilities.isEventDispatchThread();
		onlyActiveCTposition.setSelected(!onlyActiveCTposition.isSelected());
	}

	private String hotKeyLabel(final String text, final String key) {
		final String label = text.replaceFirst(key, "<u><b>" + key + "</b></u>");
		return (text.startsWith("<HTML>")) ? label : "<HTML>" + label;
	}

	protected void noValidImageDataError() {
		guiUtils.error("This option requires valid image data to be loaded.");
	}

	@SuppressWarnings("unused")
	private Boolean userPreferstoRunWizard(final String noButtonLabel) {
		if (askUserConfirmation && sigmaPalette == null) {
			final Boolean decision = guiUtils.getConfirmation2(//
					"You have not yet previewed filtering parameters. It is recommended that you do so "
							+ "at least once to ensure auto-tracing is properly tuned. Would you like to "
							+ "preview paramaters now by clicking on a representative region of the image?",
					"Adjust Parameters Visually?", "Yes. Adjust Visually...", noButtonLabel);
				if (decision != null && decision)
					changeState(WAITING_FOR_SIGMA_POINT_I);
			return decision;
		}
		return false;
	}

	private class GuiListener
			implements ActionListener, ItemListener, ImageListener {

		public GuiListener() {
			ImagePlus.addImageListener(this);
		}

		/* ImageListener */
		@Override
		public void imageClosed(final ImagePlus imp) {
			if (imp != plugin.getMainImagePlusWithoutChecks())
				return;
			if (!plugin.isDisplayCanvas(imp)) {
				// the image being closed contained valid data 
				plugin.pauseTracing(true, false);
			}
			SwingUtilities.invokeLater(() -> updateRebuildCanvasButton());
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see ij.ImageListener#imageOpened(ij.ImagePlus)
		 */
		@Override
		public void imageOpened(final ImagePlus imp) {
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see ij.ImageListener#imageUpdated(ij.ImagePlus)
		 */
		@Override
		public void imageUpdated(final ImagePlus imp) {
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see java.awt.event.ItemListener#itemStateChanged(java.awt.event.ItemEvent)
		 */
		@Override
		public void itemStateChanged(final ItemEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == useSnapWindow) {
				plugin.enableSnapCursor(useSnapWindow.isSelected());
			} else if (source == showPathsSelected) {
				plugin.setShowOnlySelectedPaths(showPathsSelected.isSelected());
			} else if (source == onlyActiveCTposition) {
				plugin.setShowOnlyActiveCTposPaths(onlyActiveCTposition.isSelected(), true);
			}
		}

		/*
		 * (non-Javadoc)
		 * 
		 * @see
		 * java.awt.event.ActionListener#actionPerformed(java.awt.event.ActionEvent)
		 */
		@Override
		public void actionPerformed(final ActionEvent e) {
			assert SwingUtilities.isEventDispatchThread();

			final Object source = e.getSource();

			if (source == secLayerActivateCheckbox) {

				if (secLayerActivateCheckbox.isSelected()) {
					if (!plugin.accessToValidImageData()) {
						plugin.enableSecondaryLayerTracing(false);
						noValidImageDataError();
						return;
					}
					if (secLayerBuiltinRadioButton.isSelected()) {
						if (!plugin.isSecondaryDataAvailable()) {
							noSecondaryDataAvailableError();
							return;
						}
						enableSecondaryLayerBuiltin(true);
					} else if (secLayerExternalRadioButton.isSelected()) {
						if (!plugin.isSecondaryImageFileLoaded()) {
							noSecondaryImgFileAvailableError();
							return;
						}
						enableSecondaryLayerExternal(true);
					}
					plugin.enableSecondaryLayerTracing(true);

				} else {
					plugin.enableSecondaryLayerTracing(false);
				}

			} else if (source == secLayerExternalRadioButton) {
				if (!plugin.isSecondaryImageFileLoaded() ) {
					noSecondaryImgFileAvailableError();
					enableSecondaryLayerExternal(false);
					enableSecondaryLayerBuiltin(true); //FIXME: IS this needed?
				}

			} else if (source == saveMenuItem && !noPathsError()) {

				final File saveFile = saveFile("Save Traces As...", null, ".traces");
				if (saveFile != null) saveToXML(saveFile);

			} else if (source == loadTracesMenuItem) {

				new ImportAction(ImportAction.TRACES, null).run();

			} else if (source == loadSWCMenuItem) {

				new ImportAction(ImportAction.SWC, null).run();

			} else if (source == exportAllSWCMenuItem && !noPathsError()) {

				if (abortOnPutativeDataLoss()) return;
				if (plugin.accessToValidImageData() && pathAndFillManager.usingNonPhysicalUnits() && !guiUtils.getConfirmation(
						"These tracings were obtained from a spatially uncalibrated "
								+ "image but the SWC specification assumes all coordinates to be " + "in "
								+ GuiUtils.micrometer() + ". Do you really want to proceed " + "with the SWC export?",
						"Warning"))
					return;

				final File saveFile = saveFile("Export All Paths as SWC...", null, ".swc");
				if (saveFile == null)
					return; // user pressed cancel
				saveAllPathsToSwc(saveFile.getAbsolutePath());
			} else if (source == exportCSVMenuItem && !noPathsError()) {

				final File saveFile = saveFile("Export All Paths as CSV...", "CSV_Properties.csv", ".csv");
				if (saveFile == null)
					return; // user pressed cancel
				if (saveFile.exists()) {
					if (!guiUtils.getConfirmation("The file " + saveFile.getAbsolutePath() + " already exists. "
							+ "Do you want to replace it?", "Override CSV file?"))
						return;
				}
				final String savePath = saveFile.getAbsolutePath();
				showStatus("Exporting as CSV to " + savePath, false);

				final int preExportingState = currentState;
				changeState(SAVING);
				// Export here...
				try {
					pathAndFillManager.exportToCSV(saveFile);
				} catch (final IOException ioe) {
					showStatus("Exporting failed.", true);
					guiUtils.error("Writing traces to '" + savePath + "' failed. See Console for details.");
					changeState(preExportingState);
					ioe.printStackTrace();
					return;
				}
				showStatus("Export complete.", true);
				changeState(preExportingState);

			} else if (source == loadLabelsMenuItem) {

				final File openFile = openFile("Select Labels File...", "labels");
				if (openFile != null) { // null if user pressed cancel;
					plugin.loadLabelsFile(openFile.getAbsolutePath());
					return;
				}

			} else if (source == keepSegment) {

				plugin.confirmTemporary(true);

			} else if (source == junkSegment) {

				plugin.cancelTemporary();

			} else if (source == completePath) {

				plugin.finishedPath();

			} else if (source == quitMenuItem) {

				exitRequested();

			} else if (source == showOrHidePathList) {

				togglePathListVisibility();

			} else if (source == showOrHideFillList) {

				toggleFillListVisibility();

			}

		}

	}

	/** Dynamic commands don't work well with CmdRunner. Use this class instead to run them */
	class DynamicCmdRunner {

		private final Class<? extends Command> cmd;
		private final int preRunState;
		private final boolean run;
		private HashMap<String, Object> inputs;

		public DynamicCmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs) {
			this(cmd, inputs, getState());
		}

		public DynamicCmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs,
				final int uiStateDuringRun) {
			assert SwingUtilities.isEventDispatchThread();
			this.cmd = cmd;
			this.preRunState = getState();
			this.inputs = inputs;
			run = initialize();
			if (run && preRunState != uiStateDuringRun)
				changeState(uiStateDuringRun);
		}
	
		private boolean initialize() {
			if (preRunState == SNTUI.EDITING) {
				guiUtils.error(
						"Please finish editing " + plugin.getEditingPath().getName() + " before running this command.");
				return false;
			}
			return true;
		}

		public void run() {
			if (!run) return;
			try {
				SNTUtils.log("Running "+ cmd.getName());
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				cmdService.run(cmd, true, inputs);
			} catch (final OutOfMemoryError e) {
				e.printStackTrace();
				guiUtils.error("There is not enough memory to complete command. See Console for details.");
			} finally {
				if (preRunState != getState())
					changeState(preRunState);
			}
		}

	}

	private class CmdRunner extends ActiveWorker {

		private final Class<? extends Command> cmd;
		private final int preRunState;
		private final boolean run;
		private HashMap<String, Object> inputs;

		// Cmd that does not require rebuilding canvas(es) nor changing UI state
		public CmdRunner(final Class<? extends Command> cmd) {
			this(cmd, null, SNTUI.this.getState());
		}

		public CmdRunner(final Class<? extends Command> cmd, final HashMap<String, Object> inputs,
				final int uiStateduringRun) {
			assert SwingUtilities.isEventDispatchThread();
			this.cmd = cmd;
			this.preRunState = SNTUI.this.getState();
			this.inputs = inputs;
			run = initialize();
			if (run && preRunState != uiStateduringRun)
				changeState(uiStateduringRun);
			activeWorker = this;
		}

		private boolean initialize() {
			if (preRunState == SNTUI.EDITING) {
				guiUtils.error(
						"Please finish editing " + plugin.getEditingPath().getName() + " before running this command.");
				return false;
			}
			return true;
		}

		@Override
		public String doInBackground() {
			if (!run) {
				publish("Please finish ongoing task...");
				return "";
			}
			try {
				SNTUtils.log("Running "+ cmd.getName());
				final CommandService cmdService = plugin.getContext().getService(CommandService.class);
				final CommandModule cmdModule = cmdService.run(cmd, true, inputs).get();
				return (cmdModule.isCanceled()) ? cmdModule.getCancelReason() : "Command completed";
			} catch (final NullPointerException | IllegalArgumentException | CancellationException | InterruptedException | ExecutionException e2) {
				// NB: A NPE seems to happen if command is DynamicCommand
				e2.printStackTrace();
				return "Unfortunately an error occured. See console for details.";
			}
		}

		@Override
		protected void process(final List<Object> chunks) {
			final String msg = (String) chunks.get(0);
			guiUtils.error(msg);
		}
	
		@Override
		protected void done() {
			showStatus("Command terminated...", false);
			if (run && preRunState != SNTUI.this.getState())
				changeState(preRunState);
		}
	}

	private static class InitViewer3DSystemProperties extends Viewer3D {
		static void init() {
			workaroundIntelGraphicsBug();
		}
	}

	private class SNTViewer3D extends Viewer3D {
		SNTViewer3D() {
			super(SNTUI.this.plugin);
			super.setDefaultColor(new ColorRGB(plugin.deselectedColor.getRed(),
					plugin.deselectedColor.getGreen(), plugin.deselectedColor.getBlue()));
		}

		@Override
		public Frame show() {
			final Frame frame = super.show();
			frame.addWindowListener(new WindowAdapter() {

				@Override
				public void windowClosing(final WindowEvent e) {
					openRecViewer.setEnabled(true);
					recViewer = null;
					recViewerFrame = null;
				}
			});
			return frame;
		}
	}

	private class ActiveWorker extends SwingWorker<Object, Object> {

		@Override
		protected Object doInBackground() throws Exception {
			return null;
		}

		public boolean kill() {
			return cancel(true);
		}
	}

	private void addFileDrop(final Component component, final GuiUtils guiUtils) {
		new FileDrop(component, new FileDrop.Listener() {

			@Override
			public void filesDropped(final File[] files) {
				if (files.length == 0) { // Is this even possible?
					guiUtils.error("Dropped file(s) not recognized.");
					return;
				}
				if (files.length > 1) {
					guiUtils.error("Ony a single file (or directory) can be imported using drag-and-drop.");
					return;
				}
				final int type = getType(files[0]);
				if (type == -1) {
					guiUtils.error(files[0].getName() + " cannot be imported using drag-and-drop.");
					return;
				}
				new ImportAction(type, files[0]).run();
			}

			private int getType(final File file) {
				if (file.isDirectory()) return ImportAction.SWC_DIR;
				final String filename = file.getName().toLowerCase();
				if (filename.endsWith(".traces")) return ImportAction.TRACES;
				if (filename.endsWith("swc")) return ImportAction.SWC;
				if (filename.endsWith(".json")) return ImportAction.JSON;
				if (filename.endsWith(".tif") || filename.endsWith(".tiff")) return ImportAction.IMAGE;
				return -1;
			}
		});
	}

	protected boolean saveToXML(final File file) {
		showStatus("Saving traces to " + file.getAbsolutePath(), false);

		final int preSavingState = currentState;
		changeState(SAVING);
		try {
			pathAndFillManager.writeXML(file.getAbsolutePath(), plugin.getPrefs().isSaveCompressedTraces());
		} catch (final IOException ioe) {
			showStatus("Saving failed.", true);
			guiUtils.error(
					"Writing traces to '" + file.getAbsolutePath() + "' failed. See Console for details.");
			changeState(preSavingState);
			ioe.printStackTrace();
			return false;
		}
		changeState(preSavingState);
		showStatus("Saving completed.", true);

		plugin.unsavedPaths = false;
		return true;
	}

	protected boolean saveAllPathsToSwc(final String filePath) {
		final Path[] primaryPaths = pathAndFillManager.getPathsStructured();
		final int n = primaryPaths.length;
		final String prefix = SNTUtils.stripExtension(filePath);
		final StringBuilder errorMessage = new StringBuilder();
		for (int i = 0; i < n; ++i) {
			final File swcFile = pathAndFillManager.getSWCFileForIndex(prefix, i);
			if (swcFile.exists())
				errorMessage.append(swcFile.getAbsolutePath()).append("<br>");
		}
		if (errorMessage.length() > 0) {
			errorMessage.insert(0, "The following files would be overwritten:<br>");
			errorMessage.append("<b>Overwrite these files?</b>");
			if (!guiUtils.getConfirmation(errorMessage.toString(), "Overwrite SWC files?"))
				return false;
		}
		SNTUtils.log("Exporting paths... " + prefix);
		final boolean success = pathAndFillManager.exportAllPathsAsSWC(primaryPaths, prefix);
		plugin.unsavedPaths = !success;
		return success;
	}

	private boolean abortOnPutativeDataLoss() {
		boolean nag = plugin.getPrefs().getTemp("dataloss-nag", true);
		if (nag) {
			final Boolean prompt = guiUtils.getPersistentWarning(//
					"The following data can only be saved in a <i>TRACES</i> file and will not be stored in SWC files:"
					+ "<ul>" //
					+ "  <li>Image details</li>"//
					+ "  <li>Fits and Fills</li>"//
					+ "  <li>Path metadata (tags, colors, traced channel and frame)</li>"//
					+ "  <li>Spine/varicosity counts</li>"//
					+ "</ul>", "Warning: Possible Data Loss");
			if (prompt == null)
				return true; // user dimissed prompt
			plugin.getPrefs().setTemp("dataloss-nag", !prompt.booleanValue());
		}
		return false;
	}

	private void registerCommandFinder(final JMenuBar menubar) {
		final JMenuItem cFinder = GuiUtils.menubarButton(IconFactory.GLYPH.SEARCH, menubar);
		cFinder.setToolTipText("Search for commands");
		cFinder.addActionListener(e -> {
			commandFinder.setLocationRelativeTo(cFinder);
			commandFinder.toggleVisibility();
		});
	}

	private class ImportAction {

		private static final int TRACES = 0;
		private static final int SWC = 1;
		private static final int SWC_DIR = 2;
		private static final int JSON = 3;
		private static final int IMAGE = 4;
		private static final int ANY_RECONSTRUCTION = 5;
		public static final int DEMO = 6;

		private final int type;
		private File file;

		private ImportAction(final int type, final File file) {
			this.type = type;
			this.file = file;
		}

		private void run() {
			if (getState() != READY && getState() != TRACING_PAUSED) {
				guiUtils.blinkingError(statusText, "Please exit current state before importing file(s).");
				return;
			}
			if (!proceed()) return;
			final HashMap<String, Object> inputs = new HashMap<>();
			final int priorState = currentState;
			switch (type) {
			case DEMO:
				changeState(LOADING);
				showStatus("Retrieving demo data. Please wait...", false);
				final String[] choices = new String[5];
				choices[0] = "Drosophila ddaC neuron (581K, 2D, binary)";
				choices[1] = "Drosophila OP neuron (15MB, 3D, grayscale, w/ tracings)";
				choices[2] = "Hippocampal neuron (2.5MB, 2D, multichannel)";
				choices[3] = "Hippocampal neuron (52MB, timelapse, w/ tracings)";
				choices[4] = "L-systems fractal (23K, 2D, binary, w/ tracings & markers)";
				final String defChoice = plugin.getPrefs().getTemp("demo", choices[4]);
				final String choice = guiUtils.getChoice("Which dataset?<br>(NB: Remote data may take a while to download)", "Load Demo Dataset", choices, defChoice);
				if (choice == null) {
					changeState(priorState);
					showStatus(null, true);
					return;
				}
				try {
					plugin.getPrefs().setTemp("demo", choice);
					final SNTService sntService = plugin.getContext().getService(SNTService.class);
					final ImagePlus imp = sntService.demoImage(choice);
					if (imp == null) {
						error("Image could not be retrieved. Perhaps and internet connection is required but you are offline?");
						changeState(priorState);
						return;
					}
					plugin.initialize(imp);
					if (pathAndFillManager.size() > 0
							&& guiUtils.getConfirmation("Clear Existing Path(s)?", "Delete All Paths")) {
						pathAndFillManager.clear();
					}
					if (choices[4].equals(choice)) {
						plugin.getPathAndFillManager().addTree(sntService.demoTree("fractal"));
						plugin.getPathAndFillManager().assignSpatialSettings(imp);
					} else if (choices[3].equals(choice)) {
						sntService.loadTracings(
							"https://raw.githubusercontent.com/morphonets/SNTmanuscript/9b4b933a742244505f0544c29211e596c85a5da7/Fig01/traces/701.traces");
					} else if (choices[1].equals(choice)) {
						sntService.loadTracings(
								"https://raw.githubusercontent.com/morphonets/SNT/0b3451b8e62464a270c9aab372b4f651c4cf9af7/src/test/resources/OP_1-gs.swc");
					}
					plugin.updateAllViewers();
				} catch (final Throwable ex) {
					error("Loading of image failed (" + ex.getMessage() + " error). See Console for details.");
					ex.printStackTrace();
				} finally {
					changeState(priorState);
					showStatus(null, true);
				}
				return;
			case IMAGE:
				if (file != null) inputs.put("file", file);
				(new DynamicCmdRunner(OpenDatasetCmd.class, inputs, LOADING)).run();
				return;
			case JSON:
				if (file != null) inputs.put("file", file);
				(new DynamicCmdRunner(JSONImporterCmd.class, inputs, LOADING)).run();
				return;
			case SWC_DIR:
				if (file != null) inputs.put("dir", file);
				(new DynamicCmdRunner(MultiSWCImporterCmd.class, inputs, LOADING)).run();
				return;
			case TRACES:
			case SWC:
			case ANY_RECONSTRUCTION:
				changeState(LOADING);
				if (type == SWC) {
					plugin.loadSWCFile(file);
				} else if (type == TRACES){
					plugin.loadTracesFile(file);
				} else if (type == ANY_RECONSTRUCTION){
					if (file == null) file = openFile("Open Reconstruction File (Guessing Type)...", "swc");
					if (file != null) plugin.loadTracings(file);
				}
				validateImgDimensions();
				changeState(priorState);
				return;
			default:
				throw new IllegalArgumentException("Unknown action");
			}
		}

		private boolean proceed() {
			return !plugin.isChangesUnsaved() || (plugin.isChangesUnsaved() && guiUtils.getConfirmation(
					"There are unsaved paths. Do you really want to load new data?", "Proceed with Import?"));
		}
	}

	private void flushSecondaryDataPrompt() {
		final String[] choices = new String[] { "Flush. I'll load new data manually", "Do nothing. Leave as is" };
		final String choice = guiUtils.getChoice("What should be done with the secondary image currently cached?",
				"Flush Filtered Data?", choices, choices[0]);
		if (choice != null) {
			if (choice.startsWith("Flush"))
			{
				plugin.flushSecondaryData();
			}
		}
	}

}
