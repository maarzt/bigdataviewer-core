/*
 * #%L
 * BigDataViewer core classes with minimal dependencies
 * %%
 * Copyright (C) 2012 - 2016 Tobias Pietzsch, Stephan Saalfeld, Stephan Preibisch,
 * Jean-Yves Tinevez, HongKee Moon, Johannes Schindelin, Curtis Rueden, John Bogovic
 * %%
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 * 
 * 1. Redistributions of source code must retain the above copyright notice,
 *    this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 * 
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDERS OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 * #L%
 */
package bdv.viewer.render;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.stream.DoubleStream;

import bdv.cache.CacheControl;
import bdv.img.cache.VolatileCachedCellImg;
import bdv.viewer.Interpolation;
import bdv.viewer.Source;
import bdv.viewer.render.MipmapOrdering.Level;
import bdv.viewer.render.MipmapOrdering.MipmapHints;
import bdv.viewer.state.SourceState;
import bdv.viewer.state.ViewerState;
import net.imglib2.Dimensions;
import net.imglib2.RandomAccess;
import net.imglib2.RandomAccessible;
import net.imglib2.RandomAccessibleInterval;
import net.imglib2.RealRandomAccessible;
import net.imglib2.Volatile;
import net.imglib2.cache.iotiming.CacheIoTiming;
import net.imglib2.cache.volatiles.CacheHints;
import net.imglib2.cache.volatiles.LoadingStrategy;
import net.imglib2.converter.Converter;
import net.imglib2.img.Img;
import net.imglib2.img.array.ArrayImg;
import net.imglib2.img.array.ArrayImgs;
import net.imglib2.img.basictypeaccess.array.IntArray;
import net.imglib2.realtransform.AffineTransform3D;
import net.imglib2.realtransform.RealViews;
import net.imglib2.type.numeric.ARGBType;
import net.imglib2.type.numeric.integer.ByteType;
import net.imglib2.ui.PainterThread;
import net.imglib2.ui.RenderTarget;
import net.imglib2.ui.Renderer;
import net.imglib2.ui.SimpleInterruptibleProjector;
import net.imglib2.util.Intervals;

/**
 * A {@link Renderer} that uses a coarse-to-fine rendering scheme. First, a
 * small {@link BufferedImage} at a fraction of the canvas resolution is
 * rendered. Then, increasingly larger images are rendered, until the full
 * canvas resolution is reached.
 * <p>
 * When drawing the low-resolution {@link BufferedImage} to the screen, they
 * will be scaled up by Java2D to the full canvas size, which is relatively
 * fast. Rendering the small, low-resolution images is usually very fast, such
 * that the display is very interactive while the user changes the viewing
 * transformation for example. When the transformation remains fixed for a
 * longer period, higher-resolution details are filled in successively.
 * <p>
 * The renderer allocates a {@link BufferedImage} for each of a predefined set
 * of <em>screen scales</em> (a screen scale of 1 means that 1 pixel in the
 * screen image is displayed as 1 pixel on the canvas, a screen scale of 0.5
 * means 1 pixel in the screen image is displayed as 2 pixel on the canvas,
 * etc.)
 * <p>
 * At any time, one of these screen scales is selected as the
 * <em>highest screen scale</em>. Rendering starts with this highest screen
 * scale and then proceeds to lower screen scales (higher resolution images).
 * Unless the highest screen scale is currently rendering,
 * {@link #requestRepaint() repaint request} will cancel rendering, such that
 * display remains interactive.
 * <p>
 * The renderer tries to maintain a per-frame rendering time close to a desired
 * number of <code>targetRenderNanos</code> nanoseconds. If the rendering time
 * (in nanoseconds) for the (currently) highest scaled screen image is above
 * this threshold, a coarser screen scale is chosen as the highest screen scale
 * to use. Similarly, if the rendering time for the (currently) second-highest
 * scaled screen image is below this threshold, this finer screen scale chosen
 * as the highest screen scale to use.
 * <p>
 * The renderer uses multiple threads (if desired) and double-buffering (if
 * desired).
 * <p>
 * Double buffering means that three {@link BufferedImage BufferedImages} are
 * created for every screen scale. After rendering the first one of them and
 * setting it to the {@link RenderTarget}, next time, rendering goes to the
 * second one, then to the third. The {@link RenderTarget} will always have a
 * complete image, which is not rendered to while it is potentially drawn to the
 * screen. When setting an image to the {@link RenderTarget}, the
 * {@link RenderTarget} will release one of the previously set images to be
 * rendered again. Thus, rendering will not interfere with painting the
 * {@link BufferedImage} to the canvas.
 * <p>
 * The renderer supports rendering of {@link Volatile} sources. In each
 * rendering pass, all currently valid data for the best fitting mipmap level
 * and all coarser levels is rendered to a {@link ScreenScale#renderImages temporary image}
 * for each visible source. Then the temporary images are combined to the final
 * image for display. The number of passes required until all data is valid
 * might differ between visible sources.
 * <p>
 * Rendering timing is tied to a {@link CacheControl} control for IO budgeting, etc.
 *
 * @author Tobias Pietzsch &lt;tobias.pietzsch@gmail.com&gt;
 */
public class MultiResolutionRendererGeneric<T>
{
	/**
	 * Receiver for the {@link BufferedImage BufferedImages} that we render.
	 */
	private final TransformAwareRenderTargetGeneric<T> display;

	/**
	 * Thread that triggers repainting of the display.
	 * Requests for repainting are send there.
	 */
	private final PainterThread painterThread;

	/**
	 * Currently active projector, used to re-paint the display. It maps the
	 * source data to {@link ScreenScale#screenImages}.
	 */
	private VolatileProjector projector;

	/**
	 * The index of the screen scale of the {@link #projector current projector}.
	 */
	private int currentScreenScaleIndex;

	/**
	 * Whether double buffering is used.
	 */
	private final boolean doubleBuffered;

	/**
	 * Double-buffer index of next {@link ScreenScale#screenImages image} to render.
	 */
	private final ArrayDeque< Integer > renderIdQueue;

	/**
	 * Maps from data store to double-buffer index. Needed for double-buffering.
	 */
	private final HashMap<T, Integer> bufferedImageToRenderId;

	/**
	 * Storage for mask images of {@link VolatileHierarchyProjector}.
	 * One array per visible source. (First) index is index in list of visible sources.
	 */
	private byte[][] renderMaskArrays;


	/**
	 * List of scale factors and associate image buffers
	 */
	private final ScreenScale< T >[] screenScales;

	/**
	 * Scale factor and associated image buffers and transformation.
	 */
	private static class ScreenScale< T > {

		/**
		 * Scale factors from the {@link #display viewer canvas} to the
		 * {@link #screenImages}.
		 *
		 * A scale factor of 1 means 1 pixel in the screen image is displayed as 1
		 * pixel on the canvas, a scale factor of 0.5 means 1 pixel in the screen
		 * image is displayed as 2 pixel on the canvas, etc.
		 */
		private double scaleFactor;

		/**
		 * Used to render an individual source. One image per visible source
		 * Index is index in list of visible sources.
		 */
		// this
		private List< ArrayImg< ARGBType, IntArray > > renderImages = new ArrayList<>();

		/**
		 * Used to render the image for display. Three images if double buffering is
		 * enabled.
		 */
		// this
		private List< RenderOutputImage< T > > screenImages = new ArrayList<>( Collections.nCopies( 3, null ) );

		/**
		 * {@link RenderOutputImage< T >}s wrapping the data in the {@link #screenImages}.
		 * Three images if double buffering is enabled.
		 */
		// this
		private List< RenderOutputImage< T > > bufferedImages = new ArrayList<>( Collections.nCopies( 3, null ) );

		/**
		 * The scale transformation from viewer to {@link #screenImages screen
		 * image}.
		 */
		// this
		private AffineTransform3D screenScaleTransforms = new AffineTransform3D();

		private ScreenScale(double scaleFactor) {
			this.scaleFactor = scaleFactor;
		}
	}

	/**
	 * If the rendering time (in nanoseconds) for the (currently) highest scaled
	 * screen image is above this threshold, increase the
	 * {@link #maxScreenScaleIndex index} of the highest screen scale to use.
	 * Similarly, if the rendering time for the (currently) second-highest
	 * scaled screen image is below this threshold, decrease the
	 * {@link #maxScreenScaleIndex index} of the highest screen scale to use.
	 */
	private final long targetRenderNanos;

	/**
	 * The index of the (coarsest) screen scale with which to start rendering.
	 * Once this level is painted, rendering proceeds to lower screen scales
	 * until index 0 (full resolution) has been reached. While rendering, the
	 * maxScreenScaleIndex is adapted such that it is the highest index for
	 * which rendering in {@link #targetRenderNanos} nanoseconds is still
	 * possible.
	 */
	private int maxScreenScaleIndex;

	/**
	 * The index of the screen scale which should be rendered next.
	 */
	private int requestedScreenScaleIndex;

	/**
	 * Whether the current rendering operation may be cancelled (to start a
	 * new one). Rendering may be cancelled unless we are rendering at
	 * coarsest screen scale and coarsest mipmap level.
	 */
	private volatile boolean renderingMayBeCancelled;

	/**
	 * How many threads to use for rendering.
	 */
	private final int numRenderingThreads;

	/**
	 * {@link ExecutorService} used for rendering.
	 */
	private final ExecutorService renderingExecutorService;

	/**
	 * TODO
	 */
	private final AccumulateProjectorFactory< ARGBType > accumulateProjectorFactory;

	/**
	 * Controls IO budgeting and fetcher queue.
	 */
	private final CacheControl cacheControl;

	/**
	 * Whether volatile versions of sources should be used if available.
	 */
	private final boolean useVolatileIfAvailable;

	/**
	 * Whether a repaint was {@link #requestRepaint() requested}. This will
	 * cause {@link CacheControl#prepareNextFrame()}.
	 */
	private boolean newFrameRequest;

	/**
	 * The timepoint for which last a projector was
	 * {@link #createProjector(ViewerState, Img)}  created}.
	 */
	private int previousTimepoint;

	// TODO: should be settable
	private long[] iobudget = new long[] { 100l * 1000000l,  10l * 1000000l };

	// TODO: should be settable
	private boolean prefetchCells = true;

	private final RenderOutputImage.Factory<T> makeImage;

	/**
	 * @param display
	 *            The canvas that will display the images we render.
	 * @param painterThread
	 *            Thread that triggers repainting of the display. Requests for
	 *            repainting are send there.
	 * @param screenScales
	 *            Scale factors from the viewer canvas to screen images of
	 *            different resolutions. A scale factor of 1 means 1 pixel in
	 *            the screen image is displayed as 1 pixel on the canvas, a
	 *            scale factor of 0.5 means 1 pixel in the screen image is
	 *            displayed as 2 pixel on the canvas, etc.
	 * @param targetRenderNanos
	 *            Target rendering time in nanoseconds. The rendering time for
	 *            the coarsest rendered scale should be below this threshold.
	 * @param doubleBuffered
	 *            Whether to use double buffered rendering.
	 * @param numRenderingThreads
	 *            How many threads to use for rendering.
	 * @param renderingExecutorService
	 *            if non-null, this is used for rendering. Note, that it is
	 *            still important to supply the numRenderingThreads parameter,
	 *            because that is used to determine into how many sub-tasks
	 *            rendering is split.
	 * @param useVolatileIfAvailable
	 *            whether volatile versions of sources should be used if
	 *            available.
	 * @param accumulateProjectorFactory
	 *            can be used to customize how sources are combined.
	 * @param cacheControl
	 *            the cache controls IO budgeting and fetcher queue.
	 */
	public MultiResolutionRendererGeneric(
			final TransformAwareRenderTargetGeneric< T > display,
			final PainterThread painterThread,
			final double[] screenScales,
			final long targetRenderNanos,
			final boolean doubleBuffered,
			final int numRenderingThreads,
			final ExecutorService renderingExecutorService,
			final boolean useVolatileIfAvailable,
			final AccumulateProjectorFactory< ARGBType > accumulateProjectorFactory,
			final CacheControl cacheControl,
			final RenderOutputImage.Factory< T > makeImage)
	{
		this.display = display;
		this.painterThread = painterThread;
		projector = null;
		currentScreenScaleIndex = -1;
		this.screenScales = DoubleStream.of(screenScales).mapToObj(ScreenScale::new).toArray(ScreenScale[]::new);
		this.doubleBuffered = doubleBuffered;
		renderIdQueue = new ArrayDeque<>();
		bufferedImageToRenderId = new HashMap<>();
		renderMaskArrays = new byte[ 0 ][];
		this.makeImage = makeImage;

		this.targetRenderNanos = targetRenderNanos;

		maxScreenScaleIndex = screenScales.length - 1;
		requestedScreenScaleIndex = maxScreenScaleIndex;
		renderingMayBeCancelled = true;
		this.numRenderingThreads = numRenderingThreads;
		this.renderingExecutorService = renderingExecutorService;
		this.useVolatileIfAvailable = useVolatileIfAvailable;
		this.accumulateProjectorFactory = accumulateProjectorFactory;
		this.cacheControl = cacheControl;
		newFrameRequest = false;
		previousTimepoint = -1;
	}

	/**
	 * Check whether the size of the display component was changed and
	 * recreate {@link ScreenScale#screenImages} and {@link ScreenScale#screenScaleTransforms} accordingly.
	 *
	 * @return whether the size was changed.
	 */
	private synchronized boolean checkResize()
	{
		final int componentW = display.getWidth();
		final int componentH = display.getHeight();
		if ( screenScales[ 0 ].screenImages.get( 0 ) == null
				|| screenScales[ 0 ].screenImages.get( 0 ).width() != ( int ) ( componentW * screenScales[ 0 ].scaleFactor )
				|| screenScales[ 0 ].screenImages.get( 0 ).height() != ( int ) ( componentH  * screenScales[ 0 ].scaleFactor ) )
		{
			renderIdQueue.clear();
			renderIdQueue.addAll( Arrays.asList( 0, 1, 2 ) );
			bufferedImageToRenderId.clear();
			for ( int i = 0; i < screenScales.length; ++i )
			{
				final double screenToViewerScale = screenScales[ i ].scaleFactor;
				final int w = ( int ) ( screenToViewerScale * componentW );
				final int h = ( int ) ( screenToViewerScale * componentH );
				if ( doubleBuffered )
				{
					for ( int b = 0; b < 3; ++b )
					{
						// reuse storage arrays of level 0 (highest resolution)
						final RenderOutputImage< T > screenImage = ( i == 0 ) ?
								makeImage.create( w, h ) :
								makeImage.create( w, h, screenScales[0].screenImages.get( b ) );
						screenScales[ i ].screenImages.set( b, screenImage );
						final RenderOutputImage< T > bi = screenScales[ i ].screenImages.get( b );
						screenScales[ i ].bufferedImages.set( b, bi);
						bufferedImageToRenderId.put( bi.unwrap(), b );
					}
				}
				else
				{
					screenScales[ i ].screenImages.set( 0, makeImage.create( w, h ) );
					screenScales[ i ].bufferedImages.set( 0, screenScales[ i ].screenImages.get( 0 ) );
				}
				final AffineTransform3D scale = new AffineTransform3D();
				final double xScale = ( double ) w / componentW;
				final double yScale = ( double ) h / componentH;
				scale.set( xScale, 0, 0 );
				scale.set( yScale, 1, 1 );
				scale.set( 0.5 * xScale - 0.5, 0, 3 );
				scale.set( 0.5 * yScale - 0.5, 1, 3 );
				screenScales[ i ].screenScaleTransforms = scale;
			}

			return true;
		}
		return false;
	}

	private boolean checkRenewRenderImages( final int numVisibleSources )
	{
		final int n = numVisibleSources > 1 ? numVisibleSources : 0;
		if ( n != screenScales[ 0 ].renderImages.size() ||
				( n != 0 &&
					( screenScales[ 0 ].renderImages.get( 0 ).dimension( 0 ) != screenScales[ 0 ].screenImages.get( 0 ).width() ||
					  screenScales[ 0 ].renderImages.get( 0 ).dimension( 1 ) != screenScales[ 0 ].screenImages.get( 0 ).height() ) ) )
		{
			for ( int i = 0; i < screenScales.length; ++i )
			{
				screenScales[ i ].renderImages = new ArrayList<>( Collections.nCopies( n, null) );
				final int w = ( int ) screenScales[ i ].screenImages.get( 0 ).width();
				final int h = ( int ) screenScales[ i ].screenImages.get( 0 ).height();
				for ( int j = 0; j < n; ++j )
				{
					final ArrayImg<ARGBType, IntArray> renderImage = (i == 0) ?
							ArrayImgs.argbs( w, h ) :
							ArrayImgs.argbs( screenScales[0].renderImages.get(j).update(null), w, h );
					screenScales[ i ].renderImages.set( j, renderImage );
				}
			}
			return true;
		}
		return false;
	}

	private boolean checkRenewMaskArrays( final int numVisibleSources )
	{
		final int size = screenScales[ 0 ].screenImages.get(0).width() * screenScales[ 0 ].screenImages.get(0).height();
		if ( numVisibleSources != renderMaskArrays.length ||
				( numVisibleSources != 0 &&	( renderMaskArrays[ 0 ].length < size ) ) )
		{
			renderMaskArrays = new byte[ numVisibleSources ][];
			for ( int j = 0; j < numVisibleSources; ++j )
				renderMaskArrays[ j ] = new byte[ size ];
			return true;
		}
		return false;
	}

	private final AffineTransform3D currentProjectorTransform = new AffineTransform3D();

	private int[] getImageSize(final RenderOutputImage< T > image)
	{
		return new int[] {image.width(), image.height()};
	}

	/**
	 * Render image at the {@link #requestedScreenScaleIndex requested screen
	 * scale}.
	 */
	public boolean paint( final ViewerState state )
	{
		if ( display.getWidth() <= 0 || display.getHeight() <= 0 )
			return false;

		final boolean resized = checkResize();

		// the BufferedImage that is rendered to (to paint to the canvas)
		final RenderOutputImage< T > bufferedImage;

		// the projector that paints to the screenImage.
		final VolatileProjector p;

		final boolean clearQueue;

		final boolean createProjector;

		synchronized ( this )
		{
			// Rendering may be cancelled unless we are rendering at coarsest
			// screen scale and coarsest mipmap level.
			renderingMayBeCancelled = ( requestedScreenScaleIndex < maxScreenScaleIndex );

			clearQueue = newFrameRequest;
			if ( clearQueue )
				cacheControl.prepareNextFrame();
			createProjector = newFrameRequest || resized || ( requestedScreenScaleIndex != currentScreenScaleIndex );
			newFrameRequest = false;

			if ( createProjector )
			{
				final int renderId = renderIdQueue.peek();
				currentScreenScaleIndex = requestedScreenScaleIndex;
				bufferedImage = screenScales[ currentScreenScaleIndex ].bufferedImages.get( renderId );
				final RenderOutputImage< T > screenImage = screenScales[ currentScreenScaleIndex ].screenImages.get( renderId );
				synchronized ( state )
				{
					final int numVisibleSources = state.getVisibleSourceIndices().size();
					checkRenewRenderImages( numVisibleSources );
					checkRenewMaskArrays( numVisibleSources );
					p = createProjector( state, screenImage.asArrayImg() );
				}
				projector = p;
			}
			else
			{
				bufferedImage = null;
				p = projector;
			}

			requestedScreenScaleIndex = 0;
		}

		// try rendering
		final boolean success = p.map( createProjector );
		final long rendertime = p.getLastFrameRenderNanoTime();

		synchronized ( this )
		{
			// if rendering was not cancelled...
			if ( success )
			{
				if ( createProjector )
				{
					final T bi = display.setBufferedImageAndTransform(bufferedImage.unwrap(), currentProjectorTransform);
					if ( doubleBuffered )
					{
						renderIdQueue.pop();
						final Integer id = bufferedImageToRenderId.get( bi );
						if ( id != null )
							renderIdQueue.add( id );
					}

					if ( currentScreenScaleIndex == maxScreenScaleIndex )
					{
						if ( rendertime > targetRenderNanos && maxScreenScaleIndex < screenScales.length - 1 )
							maxScreenScaleIndex++;
						else if ( rendertime < targetRenderNanos / 3 && maxScreenScaleIndex > 0 )
							maxScreenScaleIndex--;
					}
					else if ( currentScreenScaleIndex == maxScreenScaleIndex - 1 )
					{
						if ( rendertime < targetRenderNanos && maxScreenScaleIndex > 0 )
							maxScreenScaleIndex--;
					}
//					System.out.println( String.format( "rendering:%4d ms", rendertime / 1000000 ) );
//					System.out.println( "scale = " + currentScreenScaleIndex );
//					System.out.println( "maxScreenScaleIndex = " + maxScreenScaleIndex + "  (" + screenImages[ maxScreenScaleIndex ][ 0 ].dimension( 0 ) + " x " + screenImages[ maxScreenScaleIndex ][ 0 ].dimension( 1 ) + ")" );
				}

				if ( currentScreenScaleIndex > 0 )
					requestRepaint( currentScreenScaleIndex - 1 );
				else if ( !p.isValid() )
				{
					try
					{
						Thread.sleep( 1 );
					}
					catch ( final InterruptedException e )
					{
						// restore interrupted state
						Thread.currentThread().interrupt();
					}
					requestRepaint( currentScreenScaleIndex );
				}
			}
		}

		return success;
	}

	/**
	 * Request a repaint of the display from the painter thread, with maximum
	 * screen scale index and mipmap level.
	 */
	public synchronized void requestRepaint()
	{
		newFrameRequest = true;
		requestRepaint( maxScreenScaleIndex );
	}

	/**
	 * Request a repaint of the display from the painter thread. The painter
	 * thread will trigger a {@link #paint(ViewerState)} as soon as possible (that is,
	 * immediately or after the currently running {@link #paint(ViewerState)} has
	 * completed).
	 */
	public synchronized void requestRepaint( final int screenScaleIndex )
	{
		if ( renderingMayBeCancelled && projector != null )
			projector.cancel();
		if ( screenScaleIndex > requestedScreenScaleIndex )
			requestedScreenScaleIndex = screenScaleIndex;
		painterThread.requestRepaint();
	}

	/**
	 * DON'T USE THIS.
	 * <p>
	 * This is a work around for JDK bug
	 * https://bugs.openjdk.java.net/browse/JDK-8029147 which leads to
	 * ViewerPanel not being garbage-collected when ViewerFrame is closed. So
	 * instead we need to manually let go of resources...
	 */
	public void kill()
	{
		if ( display instanceof TransformAwareBufferedImageOverlayRenderer )
			( ( TransformAwareBufferedImageOverlayRenderer ) display ).kill();
		projector = null;
		renderIdQueue.clear();
		bufferedImageToRenderId.clear();
		for ( int i = 0; i < renderMaskArrays.length; ++i )
			renderMaskArrays[ i ] = null;
		for ( ScreenScale screenScale : screenScales ) {
			screenScale.renderImages = null;
			screenScale.screenImages = null;
			screenScale.bufferedImages = null;
		}
	}

	private VolatileProjector createProjector(
			final ViewerState viewerState,
			final Img<ARGBType> screenImage )
	{
		/*
		 * This shouldn't be necessary, with
		 * CacheHints.LoadingStrategy==VOLATILE
		 */
//		CacheIoTiming.getIoTimeBudget().clear(); // clear time budget such that prefetching doesn't wait for loading blocks.
		final List< SourceState< ? > > sourceStates = viewerState.getSources();
		final List< Integer > visibleSourceIndices = viewerState.getVisibleSourceIndices();
		VolatileProjector projector;
		if ( visibleSourceIndices.isEmpty() )
			projector = new EmptyProjector<>( screenImage );
		else if ( visibleSourceIndices.size() == 1 )
		{
			final int i = visibleSourceIndices.get( 0 );
			projector = createSingleSourceProjector(
					viewerState, sourceStates.get( i ), i, currentScreenScaleIndex,
					screenImage, renderMaskArrays[ 0 ] );
		}
		else
		{
			final ArrayList< VolatileProjector > sourceProjectors = new ArrayList<>();
			final ArrayList<ArrayImg<ARGBType, IntArray>> sourceImages = new ArrayList<>();
			final ArrayList< Source< ? > > sources = new ArrayList<>();
			int j = 0;
			for ( final int i : visibleSourceIndices )
			{
				final ArrayImg<ARGBType, IntArray> renderImage = screenScales[ currentScreenScaleIndex ].renderImages.get( j );
				final byte[] maskArray = renderMaskArrays[ j ];
				++j;
				final VolatileProjector p = createSingleSourceProjector(
						viewerState, sourceStates.get( i ), i, currentScreenScaleIndex,
						renderImage, maskArray );
				sourceProjectors.add( p );
				sources.add( sourceStates.get( i ).getSpimSource() );
				sourceImages.add( renderImage );
			}
			projector = accumulateProjectorFactory.createAccumulateProjector( sourceProjectors, sources, sourceImages, screenImage, numRenderingThreads, renderingExecutorService );
		}
		previousTimepoint = viewerState.getCurrentTimepoint();
		viewerState.getViewerTransform( currentProjectorTransform );
		CacheIoTiming.getIoTimeBudget().reset( iobudget );
		return projector;
	}

	private static class SimpleVolatileProjector< A, B > extends SimpleInterruptibleProjector< A, B > implements VolatileProjector
	{
		private boolean valid = false;

		public SimpleVolatileProjector(
				final RandomAccessible< A > source,
				final Converter< ? super A, B > converter,
				final RandomAccessibleInterval< B > target,
				final int numThreads,
				final ExecutorService executorService )
		{
			super( source, converter, target, numThreads, executorService );
		}

		@Override
		public boolean map( final boolean clearUntouchedTargetPixels )
		{
			final boolean success = super.map();
			valid |= success;
			return success;
		}

		@Override
		public boolean isValid()
		{
			return valid;
		}
	}

	private < T > VolatileProjector createSingleSourceProjector(
			final ViewerState viewerState,
			final SourceState< T > source,
			final int sourceIndex,
			final int screenScaleIndex,
			final Img<ARGBType> screenImage,
			final byte[] maskArray )
	{
		if ( useVolatileIfAvailable )
		{
			if ( source.asVolatile() != null )
				return createSingleSourceVolatileProjector( viewerState, source.asVolatile(), sourceIndex, screenScaleIndex, screenImage, maskArray );
			else if ( source.getSpimSource().getType() instanceof Volatile )
			{
				@SuppressWarnings( "unchecked" )
				final SourceState< ? extends Volatile< ? > > vsource = ( SourceState< ? extends Volatile< ? > > ) source;
				return createSingleSourceVolatileProjector( viewerState, vsource, sourceIndex, screenScaleIndex, screenImage, maskArray );
			}
		}

		final AffineTransform3D screenScaleTransform = screenScales[ currentScreenScaleIndex ].screenScaleTransforms;
		final int bestLevel = viewerState.getBestMipMapLevel( screenScaleTransform, sourceIndex );
		return new SimpleVolatileProjector<>(
				getTransformedSource( viewerState, source.getSpimSource(), screenScaleTransform, bestLevel, null ),
				source.getConverter(), screenImage, numRenderingThreads, renderingExecutorService );
	}

	private < T extends Volatile< ? > > VolatileProjector createSingleSourceVolatileProjector(
			final ViewerState viewerState,
			final SourceState< T > source,
			final int sourceIndex,
			final int screenScaleIndex,
			final Img<ARGBType> screenImage,
			final byte[] maskArray )
	{
		final AffineTransform3D screenScaleTransform = screenScales[currentScreenScaleIndex].screenScaleTransforms;
		final ArrayList< RandomAccessible< T > > renderList = new ArrayList<>();
		final Source< T > spimSource = source.getSpimSource();
		final int t = viewerState.getCurrentTimepoint();

		final MipmapOrdering ordering = MipmapOrdering.class.isInstance( spimSource ) ?
			( MipmapOrdering ) spimSource : new DefaultMipmapOrdering( spimSource );

		final AffineTransform3D screenTransform = new AffineTransform3D();
		viewerState.getViewerTransform( screenTransform );
		screenTransform.preConcatenate( screenScaleTransform );
		final MipmapHints hints = ordering.getMipmapHints( screenTransform, t, previousTimepoint );
		final List< Level > levels = hints.getLevels();

		if ( prefetchCells )
		{
			Collections.sort( levels, MipmapOrdering.prefetchOrderComparator );
			for ( final Level l : levels )
			{
				final CacheHints cacheHints = l.getPrefetchCacheHints();
				if ( cacheHints == null || cacheHints.getLoadingStrategy() != LoadingStrategy.DONTLOAD )
					prefetch( viewerState, spimSource, screenScaleTransform, l.getMipmapLevel(), cacheHints, screenImage );
			}
		}

		Collections.sort( levels, MipmapOrdering.renderOrderComparator );
		for ( final Level l : levels )
			renderList.add( getTransformedSource( viewerState, spimSource, screenScaleTransform, l.getMipmapLevel(), l.getRenderCacheHints() ) );

		if ( hints.renewHintsAfterPaintingOnce() )
			newFrameRequest = true;

		Img<ByteType> mask = ArrayImgs.bytes( maskArray, Intervals.dimensionsAsLongArray(screenImage) );
		return new VolatileHierarchyProjector<>( renderList, source.getConverter(), screenImage, mask, numRenderingThreads, renderingExecutorService );
	}

	private static < T > RandomAccessible< T > getTransformedSource(
			final ViewerState viewerState,
			final Source< T > source,
			final AffineTransform3D screenScaleTransform,
			final int mipmapIndex,
			final CacheHints cacheHints )
	{
		final int timepoint = viewerState.getCurrentTimepoint();

		final RandomAccessibleInterval< T > img = source.getSource( timepoint, mipmapIndex );
		if ( VolatileCachedCellImg.class.isInstance( img ) )
			( ( VolatileCachedCellImg< ?, ? > ) img ).setCacheHints( cacheHints );

		final Interpolation interpolation = viewerState.getInterpolation();
		final RealRandomAccessible< T > ipimg = source.getInterpolatedSource( timepoint, mipmapIndex, interpolation );

		final AffineTransform3D sourceToScreen = new AffineTransform3D();
		viewerState.getViewerTransform( sourceToScreen );
		final AffineTransform3D sourceTransform = new AffineTransform3D();
		source.getSourceTransform( timepoint, mipmapIndex, sourceTransform );
		sourceToScreen.concatenate( sourceTransform );
		sourceToScreen.preConcatenate( screenScaleTransform );

		return RealViews.affine( ipimg, sourceToScreen );
	}

	private static < T > void prefetch(
			final ViewerState viewerState,
			final Source< T > source,
			final AffineTransform3D screenScaleTransform,
			final int mipmapIndex,
			final CacheHints prefetchCacheHints,
			final Dimensions screenInterval )
	{
		final int timepoint = viewerState.getCurrentTimepoint();
		final RandomAccessibleInterval< T > img = source.getSource( timepoint, mipmapIndex );
		if ( VolatileCachedCellImg.class.isInstance( img ) )
		{
			final VolatileCachedCellImg< ?, ? > cellImg = ( VolatileCachedCellImg< ?, ? > ) img;

			CacheHints hints = prefetchCacheHints;
			if ( hints == null )
			{
				final CacheHints d = cellImg.getDefaultCacheHints();
				hints = new CacheHints( LoadingStrategy.VOLATILE, d.getQueuePriority(), false );
			}
			cellImg.setCacheHints( hints );
			final int[] cellDimensions = new int[ 3 ];
			cellImg.getCellGrid().cellDimensions( cellDimensions );
			final long[] dimensions = new long[ 3 ];
			cellImg.dimensions( dimensions );
			final RandomAccess< ? > cellsRandomAccess = cellImg.getCells().randomAccess();

			final Interpolation interpolation = viewerState.getInterpolation();

			final AffineTransform3D sourceToScreen = new AffineTransform3D();
			viewerState.getViewerTransform( sourceToScreen );
			final AffineTransform3D sourceTransform = new AffineTransform3D();
			source.getSourceTransform( timepoint, mipmapIndex, sourceTransform );
			sourceToScreen.concatenate( sourceTransform );
			sourceToScreen.preConcatenate( screenScaleTransform );

			Prefetcher.fetchCells( sourceToScreen, cellDimensions, dimensions, screenInterval, interpolation, cellsRandomAccess );
		}
	}
}
