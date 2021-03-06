package spim.controller;

import ij.process.ImageProcessor;
import org.apache.commons.math3.geometry.euclidean.threed.Vector3D;
import spim.acquisition.Params;
import spim.acquisition.Row;
import spim.algorithm.AntiDrift;
import spim.gui.calibration.AntiDriftAdjustWindow;
import spim.algorithm.DefaultAntiDrift;

import java.io.File;

/**
 * The Anti-drift controller consists of AntiDrift logic and GUI.
 */
public class AntiDriftController implements AntiDrift.Callback
{
	final private DefaultAntiDrift antiDrift;
	final private AntiDriftAdjustWindow gui;
	private Vector3D center;
	private double zratio;
	private double zstep;
	private long tp;
	private File outputDir;

	/**
	 * Instantiates a new AntiDriftController using primitive parameters
	 *
	 * @param outputDir the output dir
	 * @param x the x
	 * @param y the y
	 * @param z the z
	 * @param theta the theta
	 * @param zstep the zstep
	 * @param zratio the zratio
	 */
	public AntiDriftController(final File outputDir, double x, double y, double z, double theta, double zstep, double zratio)
	{
		final Vector3D loc = new Vector3D(x, y, z);
		this.zratio = zratio;
		this.zstep = zstep;
		tp = 1;

		antiDrift = new DefaultAntiDrift();
		gui = new AntiDriftAdjustWindow(x, y, z, theta, zratio);

		if(outputDir != null) {
			String xyz = String.format("XYZ%.2fx%.2fx%.2f_Theta%.2f", loc.getX(), loc.getY(), loc.getZ(), theta);
			File saveDir = new File(new File(outputDir, "diffs"), xyz);

			if(!saveDir.exists() && !saveDir.mkdirs()) {
				ij.IJ.log("Couldn't create output directory " + saveDir.getAbsolutePath());
			} else {
				this.outputDir = saveDir;
			}
		}
	}

	/**
	 * Instantiates a new AntiDriftController using MicroManager Parameters
	 *
	 * @param outputDir the output dir
	 * @param acqParams the acq params
	 * @param acqRow the acq row
	 */
	public AntiDriftController(final File outputDir, final Params acqParams, final Row acqRow)
	{
		this( outputDir, acqRow.getX(), acqRow.getY(), acqRow.getZStartPosition(), acqRow.getTheta(),
				acqRow.getZStepSize(), acqRow.getZStepSize() / acqParams.getCore().getPixelSizeUm() );
	}

	/**
	 * Give new instance of AntiDriftController.
	 *
	 * @param outputDir the output dir
	 * @param acqParams the acq params
	 * @param acqRow the acq row
	 * @return the AntiDriftController
	 */
	public static AntiDriftController newInstance(final File outputDir, final Params acqParams, final Row acqRow)
	{
		return new AntiDriftController( outputDir, acqParams, acqRow );
	}

	/**
	 * The interface Factory to instanciate AntiDriftController in lazy binding.
	 */
	public interface Factory
	{
		/**
		 * New instance.
		 *
		 * @param p the p
		 * @param r the r
		 * @return the anti drift controller
		 */
		public AntiDriftController newInstance(Params p, Row r);
	}

	/**
	 * Sets callback.
	 *
	 * @param callback the callback
	 */
	public void setCallback(AntiDrift.Callback callback)
	{
		antiDrift.setCallback( callback );
	}

	/**
	 * Start new stack.
	 */
	public void startNewStack()
	{
		if(gui.isVisible())
		{
			antiDrift.updateOffset( gui.getOffset() );
			gui.setVisible( false );
			gui.dispose();
		}

		antiDrift.startNewStack();
	}

	/**
	 * Add a XY slice.
	 *
	 * @param ip the ip
	 */
	public void addXYSlice(ImageProcessor ip)
	{
		antiDrift.addXYSlice( ip );
	}

	/**
	 * Finish stack.
	 */
	public void finishStack()
	{
		finishStack( antiDrift.getFirst() == null );
	}

	/**
	 * Finish stack.
	 *
	 * @param initial the initial
	 */
	public void finishStack(boolean initial)
	{
		if(initial)
			antiDrift.setFirst( antiDrift.getLatest() );

		center = antiDrift.getLastCorrection().add( antiDrift.getLatest().getCenter() );

		// Before processing anti-drift
		if(null != outputDir)
			antiDrift.writeDiff( getOutFile("initial"), antiDrift.getLastCorrection(), zratio, center );

		// Process anti-drift
		antiDrift.finishStack();

		// After the anti-drift processing
		if(null != outputDir)
			antiDrift.writeDiff( getOutFile("suggested"), antiDrift.getLastCorrection(), zratio, center );

		// Invoke GUI for fine-tuning
		gui.setVisible( true );

		gui.setCallback( this );
		gui.setZratio( zratio );
		gui.updateScale( antiDrift.getLatest().largestDimension() * 2 );
		gui.setOffset( antiDrift.getLastCorrection() );
		gui.setCenter( center );
		gui.setBefore( antiDrift.getFirst() );
		gui.setAfter( antiDrift.getLatest() );

		gui.updateDiff();

		// first = latest
		antiDrift.setFirst( antiDrift.getLatest() );

		// Increase the file index number
		++tp;
	}

	private File getOutFile(String tag) {
		return new File(outputDir, String.format("diff_TL%02d_%s.tiff", tp, tag));
	}

	public void applyOffset( Vector3D offset )
	{
		antiDrift.updateOffset( offset );

		// Callback function for ProgrammaticAcquisitor class
		// refer spim/progacq/ProgrammaticAcquisitor.java:366
		antiDrift.invokeCallback(new Vector3D(-offset.getX(), -offset.getY(), -offset.getZ() * zstep));

		if(null != outputDir)
			antiDrift.writeDiff( getOutFile("final"), antiDrift.getLastCorrection(), zratio, center );
	}
}
