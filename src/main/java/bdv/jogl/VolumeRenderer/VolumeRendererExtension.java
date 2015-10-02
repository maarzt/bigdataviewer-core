package bdv.jogl.VolumeRenderer;

import static bdv.jogl.VolumeRenderer.utils.GeometryUtils.getAABBOfTransformedBox;

import java.awt.Color;
import java.awt.Point;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import javax.swing.Action;
import javax.swing.JMenu;
import javax.swing.JMenuBar;

import java.awt.event.KeyAdapter;
import java.util.Collection;
import java.util.List;

import com.jogamp.opengl.math.geom.AABBox;

import bdv.BigDataViewer;
import bdv.jogl.VolumeRenderer.Scene.InteraktionAnimator;
import bdv.jogl.VolumeRenderer.Scene.VolumeDataScene;
import bdv.jogl.VolumeRenderer.ShaderPrograms.IMultiVolumeRendererListener;
import bdv.jogl.VolumeRenderer.ShaderPrograms.MultiVolumeRenderer;
import bdv.jogl.VolumeRenderer.ShaderPrograms.ShaderSources.functions.accumulator.AbstractVolumeAccumulator;
import bdv.jogl.VolumeRenderer.TransferFunctions.TransferFunction1D;
import bdv.jogl.VolumeRenderer.TransferFunctions.TransferFunctionAdapter;
import bdv.jogl.VolumeRenderer.gui.SceneControlsWindow;
import bdv.jogl.VolumeRenderer.gui.GLWindow.GLWindow;
import bdv.jogl.VolumeRenderer.gui.VDataAggregationPanel.AggregatorManager;
import bdv.jogl.VolumeRenderer.gui.VDataAggregationPanel.IVolumeAggregationListener;
import bdv.jogl.VolumeRenderer.gui.VolumeRendereActions.OpenVolumeRendererAction;
import bdv.jogl.VolumeRenderer.utils.VolumeDataBlock;
import bdv.jogl.VolumeRenderer.utils.VolumeDataManager;
import bdv.jogl.VolumeRenderer.utils.VolumeDataManagerAdapter;

/**
 * The context class of the bdv 3D volume extension
 * @author michael
 *
 */
public class VolumeRendererExtension {

	private final BigDataViewer bdv;

	private final GLWindow glWindow;

	private final VolumeDataManager dataManager; 

	private final VolumeDataScene dataScene;

	private final static String preferedMenuName = "Tools";

	private final static String actionName = "3D Volume";

	private final MultiVolumeRenderer volumeRenderer;

	private final TransferFunction1D transferFunction = new TransferFunction1D();

	private final AggregatorManager aggManager = new AggregatorManager();

	private final BigDataViewerDataSelector selector;
	
	private AABBox hullVolume = null;
	
	private SceneControlsWindow controls;
	
	private final InteraktionAnimator animator;

	private void createControlWindow(){
		controls =new SceneControlsWindow(transferFunction,aggManager, dataManager, volumeRenderer,glWindow,dataScene);
	}

	
	public VolumeRendererExtension(final BigDataViewer bdv){
		if(bdv == null){
			throw new NullPointerException("The extension needs a valid big data viewer instance");
		}

		this.bdv = bdv;

		dataManager = new VolumeDataManager(bdv);
		Color bgColor = Color.BLACK;
		volumeRenderer = new MultiVolumeRenderer(transferFunction, dataManager);
		dataScene = new VolumeDataScene( dataManager,volumeRenderer);
		volumeRenderer.addMultiVolumeListener(new IMultiVolumeRendererListener() {
			
			@Override
			public void drawRectChanged(AABBox drawRect) {
				if(drawRect.equals(hullVolume)){
					return;
				}
				dataScene.getCamera().centerOnBox(drawRect);
				
			}
		});
		
		
		glWindow = new GLWindow(dataScene);
	
		volumeRenderer.setBackgroundColor(bgColor);
		dataScene.setBackgroundColor(bgColor);
		animator = new InteraktionAnimator(volumeRenderer, glWindow, dataManager);
		
		createControlWindow();
		selector = new BigDataViewerDataSelector(bdv);
		createActionInToolBar();
		createListeners();
	
	}

	private void createListeners() {
		//source changes 
		volumeRenderer.setAccumulator(aggManager.getAccumulator( aggManager.getActiveAccumulator()));
		aggManager.addListener(new IVolumeAggregationListener() {

			@Override
			public void aggregationChanged(AbstractVolumeAccumulator acc) {
				volumeRenderer.setAccumulator(acc);
				glWindow.getGlCanvas().repaint();
			}
		});
		

		transferFunction.addTransferFunctionListener( new TransferFunctionAdapter() {

			@Override
			public void colorChanged(final TransferFunction1D function) {

				//trigger scene update
				glWindow.getGlCanvas().repaint();
			}

			@Override
			public void samplerChanged(TransferFunction1D transferFunction1D) {
				glWindow.getGlCanvas().repaint();
			}
		});
		
		//close listener
		this.bdv.getViewerFrame().addWindowListener(new WindowAdapter() {

			@Override
			public void windowClosing(WindowEvent e) {
				glWindow.dispose();
				controls.dispose();
			}
		});
		BigDataViewerAdapter.connect(this.bdv, dataManager);
		
		this.bdv.getViewer().addTransformListener(new SceneGlobalTransformationListener(volumeRenderer,glWindow));
		
		this.dataManager.addVolumeDataManagerListener(new VolumeDataManagerAdapter(){
			
			@Override
			public void addedData(Integer i) {
				hullVolume = null;
			}
		});
		
		//stop all animations
		this.glWindow.getGlCanvas().addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				if(e.getKeyCode() == KeyEvent.VK_ESCAPE){
					animator.stopAllAnimations();
				}
			}
		
		}); 
		
	
		//on reset go to full view
		controls.getResetButton().addActionListener(new ActionListener() {

			@Override
			public void actionPerformed(ActionEvent e) {
				volumeRenderer.setUseSparseVolumes(false);
				dataManager.resetVolumeData();
				animator.startInitAnimation();
			}
		});
		
		//listener on new selected data
		selector.addBigDataViewerDataSelectorListener(new IBigDataViewerDataSelectorListener() {
			
			@Override
			public void selectedDataAvailable(AABBox hullVolume,
					List<VolumeDataBlock> partialVolumesInHullVolume, int time) {			
				
				volumeRenderer.setUseSparseVolumes(false);
				dataManager.resetVolumeData();
				animator.startMoveToSelectionAnimation(hullVolume, partialVolumesInHullVolume, time);
				controls.setVisible(true);
				glWindow.setVisible(true);
				
				
			}
		});
	}

	/**
	 * creates an action for the volume renderer in the bdv toolbar
	 * @param parent The BigDataViewer to connect to
	 * 
	 */
	private void createActionInToolBar(){
		JMenuBar menuBar = bdv.getViewerFrame().getJMenuBar();

		JMenu preferedMenu = null;

		//find Tools menu
		for(int i = 0; i < menuBar.getMenuCount(); i++){
			JMenu currentMenu = menuBar.getMenu(i);
			if(currentMenu.getText().equals(preferedMenuName)){
				preferedMenu = currentMenu;
			}
		}

		//create if not exists
		if(preferedMenu == null){
			preferedMenu = new JMenu(preferedMenuName);
		}

		Action open3DViewAction = new OpenVolumeRendererAction(actionName, glWindow, controls, animator);
		preferedMenu.add(open3DViewAction);
		preferedMenu.updateUI();
	}

	/**
	 * clears the context
	 */
	public void delete(){
		glWindow.dispose();
		controls.destroyTFWindow();
	}
}
