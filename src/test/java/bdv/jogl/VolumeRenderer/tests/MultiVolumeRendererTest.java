package bdv.jogl.VolumeRenderer.tests;

import static org.junit.Assert.*;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.swing.JFrame;

import org.junit.Before;
import org.junit.Test;

import com.jogamp.opengl.GL;
import com.jogamp.opengl.GL4;
import com.jogamp.opengl.GLAutoDrawable;
import com.jogamp.opengl.GLEventListener;
import com.jogamp.opengl.awt.GLCanvas;
import com.jogamp.opengl.math.Matrix4;
import com.jogamp.opengl.math.VectorUtil;
import com.jogamp.opengl.math.geom.AABBox;

import Jama.Matrix;
import bdv.jogl.VolumeRenderer.Camera;
import bdv.jogl.VolumeRenderer.FrameBufferRedirector;
import bdv.jogl.VolumeRenderer.GLErrorHandler;
import bdv.jogl.VolumeRenderer.Scene.SimpleScene;
import bdv.jogl.VolumeRenderer.ShaderPrograms.MultiVolumeRenderer;
import bdv.jogl.VolumeRenderer.TransferFunctions.TransferFunction1D;
import bdv.jogl.VolumeRenderer.utils.VolumeDataBlock;
import bdv.jogl.VolumeRenderer.utils.VolumeDataManager;
import static bdv.jogl.VolumeRenderer.utils.MatrixUtils.*;
import static bdv.jogl.VolumeRenderer.utils.VolumeDataUtils.*;

public class MultiVolumeRendererTest {

	private FrameBufferRedirector redirector = new FrameBufferRedirector(); 

	private long[] testdim = {3,2,1};

	private float[] testEye = {0,0,3};

	private float[] testCenter = {0,0,0};

	private float[][][] result;

	private SimpleScene testScene = new SimpleScene();

	private VolumeDataManager dataManager = new VolumeDataManager(null);

	private BlockingQueue<Boolean> sync = new ArrayBlockingQueue<Boolean>(1);

	private List<VolumeDataBlock> sampleData = new ArrayList<VolumeDataBlock>(); 

	private GLEventListener renderListener =  new GLEventListener() {

		@Override
		public void reshape(GLAutoDrawable drawable, int x, int y, int width,
				int height) {
			GLErrorHandler.assertGL(drawable.getGL());

		}

		@Override
		public void init(GLAutoDrawable drawable) {
			GL gl = drawable.getGL();
			GL4 gl2 = gl.getGL4();

			redirector.setHeight(drawable.getSurfaceHeight());
			redirector.setWidth(drawable.getSurfaceWidth());
			redirector.init(gl2);		

			GLErrorHandler.assertGL(gl2);
		}

		@Override
		public void dispose(GLAutoDrawable drawable) {
			GL gl = drawable.getGL();
			GL4 gl2 = gl.getGL4();

			result = redirector.getFrameBufferContent(gl2);

			GLErrorHandler.assertGL(gl2);
			try {
				sync.put(true);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}
		}

		@Override
		public void display(GLAutoDrawable drawable) {
			GL gl = drawable.getGL();
			GL4 gl2 = gl.getGL4();

			redirector.render(gl2);

			GLErrorHandler.assertGL(gl2);
			try {
				sync.put(true);
			} catch (InterruptedException e) {
				// TODO Auto-generated catch block
				e.printStackTrace();
			}

		}
	};

	private JFrame testWindow = new JFrame("Test window");

	private GLCanvas renderCanvas = new GLCanvas();
	private MultiVolumeRenderer classUnderTest;
	private float [][] volumeDataArrays = {{0,1,2,
		3,4,5},
		{6,5,4,
			3,2,1}};

	@Before
	public void setUp(){
		dataManager = new VolumeDataManager(null);
		classUnderTest = new MultiVolumeRenderer(new TransferFunction1D(), dataManager);
	}



	private void initTestWindow(){

		renderCanvas.addGLEventListener(renderListener);

		testWindow.setSize(100, 100);
		testWindow.getContentPane().add(renderCanvas);


		testScene.getCamera().setEyePoint(testEye);
		testScene.getCamera().setLookAtPoint(testCenter);
		testScene.addSceneElement(classUnderTest);
		testScene.setBackgroundColor(Color.RED);

		redirector.setScene(testScene);
		redirector.setHeight(100);
		redirector.setWidth(100);
	}



	@Test
	public void renderSomethingTest(){
		initTestWindow();
		VolumeDataBlock[] blocks = {new VolumeDataBlock(),new VolumeDataBlock()};

		Matrix4 loc1 = new Matrix4();
		loc1.rotate(45, 0, 0, 1);
		Matrix4 loc2 = new Matrix4();
		loc2.rotate(-45, 0, 0, 1);
		blocks[0].data = volumeDataArrays[0];
		blocks[0].setLocalTransformation(loc1);
		blocks[0].dimensions = testdim.clone();

		blocks[1].data = volumeDataArrays[1];
		blocks[1].dimensions = testdim.clone();
		blocks[1].setLocalTransformation(loc2);


		dataManager.setVolume(0,0, blocks[0]);
		dataManager.setVolume(1,0, blocks[1]);

		testWindow.setVisible(true);

		Boolean syncValue = null;
		try {
			syncValue=sync.poll(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}


		renderCanvas.destroy();
		testWindow.dispose();
		assertEquals(true, syncValue);
		try {
			syncValue = sync.poll(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}
		assertEquals(true, syncValue);

		Boolean isSomethingDrawn = false;
		for(int x = 0; x< result.length; x++){
			for(int y = 0; y < result[x].length; y++){
				Color testColor = new Color(result[x][y][0],result[x][y][1],result[x][y][2],result[x][y][3]);
				if(!(testColor.equals( testScene.getBackgroundColor()))){
					isSomethingDrawn = true;
				}
			}

		}
		assertTrue(isSomethingDrawn);
	}

	@Before
	public void fillSampleData(){
		//first assumed volume 
		VolumeDataBlock data1 = new VolumeDataBlock();
		data1.dimensions[0]=2000;
		data1.dimensions[1]=1000;
		data1.dimensions[2]=500;
		Matrix4 trans1 = getNewIdentityMatrix();
		data1.setLocalTransformation(trans1);
		sampleData.add(data1);

		//second assumed volume 
		VolumeDataBlock data2 = new VolumeDataBlock();
		data2.dimensions[0]=2000;
		data2.dimensions[1]=1000;
		data2.dimensions[2]=500;
		Matrix4 trans2 = getNewIdentityMatrix();
		trans2.translate(0, 1000,0);
		trans2.rotate((float)Math.toRadians(-66), 0, 1, 0);
		data2.setLocalTransformation(trans2);
		sampleData.add(data2);
	}
	
	@Test
	public void correctTextureAdressing(){
		//glsl like texture coordinate normalization assuming volume coordinate system as it should be done 
		
		//test all samples 
		for(VolumeDataBlock data : sampleData){
			float textureNormalizationFactors[] = new float[3];
			float textureNormalizationOffset[] = new float[3];
			for(int d = 0;  d < 3; d++){
				textureNormalizationFactors[d] = (float)(data.dimensions[d]-1)/((float)(data.dimensions[d]*data.dimensions[d]));
				textureNormalizationOffset[d] = 1f/(2f*(float) data.dimensions[d]);
			}
			
			//test in iteration
			float texCoord[] = new float[3];
			for(int z = 0; z <  data.dimensions[2]; z+=100 ){
				texCoord[2] = (float)z*textureNormalizationFactors[2]+ textureNormalizationOffset[2];
				for(int y = 0; y <  data.dimensions[1]; y+=100 ){
					texCoord[1] = (float)z*textureNormalizationFactors[1]+ textureNormalizationOffset[1];
					for(int x = 0; x <  data.dimensions[0]; x+=100 ){
						texCoord[0] = (float)z*textureNormalizationFactors[0]+ textureNormalizationOffset[0];
						
						//text in borders
						for(int d = 0; d < 3; d++){
							assertTrue(texCoord[d]>0f);
							assertTrue(texCoord[d]<1f);
						}
						
						//border check low
						if(x == 0 && y==0 && z==0){
							for(int d = 0; d < 3; d++){
								assertEquals(textureNormalizationOffset[d], texCoord[d], 0.01);
						
							}
						}
					}
				}
			}
			
			//high border test
			for( int d =0; d< 3; d++){
				float textureOrdinate = (float)(data.dimensions[d]-1)* textureNormalizationFactors[d] +textureNormalizationOffset[d];
				assertEquals(1f-textureNormalizationOffset[d], textureOrdinate, 0.01);
			}
			
			
		}
	} 
	
	@Test
	public void coordinateSpaceTransformationTest(){
		//one main raycasting step as it should be done in multivolumerenderer  to suppress ray drifting   
		
		int testSamples = 3000;
		Camera c = new Camera();
		c.setZnear(1);
		c.init();
		float checkPoints[][] = new float[][]{{0,0,0,1},{0,0,1,1},{0,1,0,1},{0,1,1,1},{1,0,0,1},{1,0,1,1},{1,1,0,1},{1,1,1,1}};

		//get volume box
		List<Matrix4> localTrans = new ArrayList<Matrix4>();
		List<Matrix4> scaledLocalTrans = new ArrayList<Matrix4>();
		List<Matrix4> fromGlobalToText = new ArrayList<Matrix4>();
		for(VolumeDataBlock data: sampleData){
			//localTrans.add(calcVolumeTransformation(data));
			localTrans.add(fromVolumeToGlobalSpace(data));
			fromGlobalToText.add(fromCubeToVolumeSpace(data));
			scaledLocalTrans.add(calcScaledVolumeTransformation(data));
			//fromGlobalToText.add( fromCubeToNormalizedTextureSpace(data));
		}
		
		
		AABBox volumeBox =  calculateCloseFittingBox(scaledLocalTrans);
		Matrix4 rectTrans = getTransformationRepresentAABBox(volumeBox);
		
		c.centerOnBox(volumeBox);

		//test of fitting
		for(float[] point: checkPoints){	
			for(Matrix4 trans :localTrans){
				float transformed[] = new float[4];
				trans.multVec(point, transformed);
				assertTrue("box: "+ volumeBox+" does not contain the point: "+transformed, volumeBox.contains(transformed[0]/transformed[3], transformed[1]/transformed[3], transformed[2]/transformed[3]));
			}
		}

		//calc eyes
		List<float[]> eyesInTexture = new ArrayList<float[]>();
		for(int v = 0; v < localTrans.size(); v++){
			Matrix4 trans = localTrans.get(v);
			Matrix4 modelView = getNewIdentityMatrix();
			modelView.multMatrix(c.getViewMatrix());
			modelView.multMatrix(trans);
			//modelView.scale(1.f/(float)sampleData.get(v).dimensions[0], 1.f/(float)sampleData.get(v).dimensions[1], 1.f/(float)sampleData.get(v).dimensions[2]);
			//modelView.multMatrix(rectTrans);
			//modelView.multMatrix(fromGlobalToText.get(v));
			eyesInTexture.add(getEyeInCurrentSpace(modelView));
		}

		//arbitrary start point(center of render cube)
		List<float[]> rayStartPointsTextSpace= new ArrayList<float[]>();
		List<float[]> rayDirectionsTextSpace= new ArrayList<float[]>();
		List<Float> sampleSizesInTextSpace = new ArrayList<Float>();
		float diagonalGlobalSpace[] = new float[]{volumeBox.getWidth(),volumeBox.getHeight(), volumeBox.getDepth(),0};
		for(int i = 0; i < eyesInTexture.size(); i++){
			float center[] = new float[]{volumeBox.getCenter()[0],volumeBox.getCenter()[1],volumeBox.getCenter()[2],1};
			float centerIntext[] = new float[4]; 

			fromGlobalToText.get(i).multVec(center, centerIntext);
			for(int j = 0; j < 3; j++){
				centerIntext[j]/=centerIntext[3];
			}
			rayStartPointsTextSpace.add(new float[]{centerIntext[0],centerIntext[1],centerIntext[2],1});
			float dir[] = new float[3];
			for(int j = 0; j < dir.length; j++){
				dir[j] = rayStartPointsTextSpace.get(i)[j] - eyesInTexture.get(i)[j];
			}

			//normalize
			float l = VectorUtil.normVec3(dir);
			for(int j = 0; j < dir.length; j++){
				dir[j]/=l;
			}
			rayDirectionsTextSpace.add(dir);

			//samples
			float transformedDiagonal[]  =  new float[4];
			fromGlobalToText.get(i).multVec(diagonalGlobalSpace, transformedDiagonal);
			sampleSizesInTextSpace.add(VectorUtil.normVec3(transformedDiagonal)/(float)testSamples);
		}

		//test ray parameters
		float rayDirInGloabal[][] = new float[rayStartPointsTextSpace.size()][4];
		float rayStartInGlobal[][] = new float[rayStartPointsTextSpace.size()][4];
		for(int i = 0; i < rayDirectionsTextSpace.size(); i++){
			float rayDirtToTransform[] = new float[]{rayDirectionsTextSpace.get(i)[0],rayDirectionsTextSpace.get(i)[1],rayDirectionsTextSpace.get(i)[2],0};
			float rayStartToTransform[] = new float[]{rayStartPointsTextSpace.get(i)[0],rayStartPointsTextSpace.get(i)[1],rayStartPointsTextSpace.get(i)[2],1};

			localTrans.get(i).multVec(rayDirtToTransform, rayDirInGloabal[i]);
			localTrans.get(i).multVec(rayStartToTransform, rayStartInGlobal[i]);

			float l = VectorUtil.normVec3(rayDirInGloabal[i]);
			for(int j = 0; j < 3; j++){
				rayDirInGloabal[i][j]/=l;
				rayStartInGlobal[i][j]/=rayStartInGlobal[i][3];
			}
		}

		//all cross tests all transformed correct
		for(int i = 0; i < rayDirectionsTextSpace.size(); i++){
			for(int j = i+1; j < rayDirectionsTextSpace.size(); j++){
				assertEquals(1.0f, VectorUtil.dotVec3(rayDirInGloabal[j], rayDirInGloabal[i]),0.01);
				assertArrayEquals(rayStartInGlobal[i], rayStartInGlobal[j],0.01f);
			}	
		}

		//test drift of rays try mono ray cast
		List<float[]> currentRayPos = new ArrayList<float[]>();
		for(float[] startpos : rayStartPointsTextSpace){
			currentRayPos.add(startpos);
		}

		//raycast
		for(int d = 0; d < testSamples; d++){
			for(int r = 0; r < currentRayPos.size(); r++){
				for(int n = 0; n < 3; n++){
					currentRayPos.get(r)[n] += rayDirectionsTextSpace.get(r)[n] * sampleSizesInTextSpace.get(r);
				}
			}

			//test current pos
			for(int r1 = 0; r1 < currentRayPos.size(); r1++){
				float globalSpacePos1[] = new float[4];
				localTrans.get(r1).multVec(currentRayPos.get(r1), globalSpacePos1);

				//wclip
				for(int n = 0; n < 3; n++ ){
					globalSpacePos1[n] /= globalSpacePos1[3]; 
				}

				for(int r2 = r1+1; r2 < currentRayPos.size(); r2++){
					float globalSpacePos2[] = new float[4];
					localTrans.get(r2).multVec(currentRayPos.get(r2), globalSpacePos2);

					//wclip
					for(int n = 0; n < 3; n++ ){
						globalSpacePos2[n] /= globalSpacePos2[3]; 
					}

					assertArrayEquals("rays "+r1+ " and "+ r2+" differ after "+(d+1)+" iterations!" ,globalSpacePos1,globalSpacePos2 , 0.1f);
				}
			}
		}
	}
}
