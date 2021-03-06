package edu.ilab.covid_id.ir;

import android.graphics.Bitmap;
import android.util.Log;

import com.flir.thermalsdk.androidsdk.image.BitmapAndroid;
import com.flir.thermalsdk.image.Rectangle;
import com.flir.thermalsdk.image.TemperatureUnit;
import com.flir.thermalsdk.image.ThermalImage;
import com.flir.thermalsdk.image.fusion.FusionMode;
import com.flir.thermalsdk.live.Camera;
import com.flir.thermalsdk.live.CommunicationInterface;
import com.flir.thermalsdk.live.ConnectParameters;
import com.flir.thermalsdk.live.Identity;
import com.flir.thermalsdk.live.connectivity.ConnectionStatusListener;
import com.flir.thermalsdk.live.discovery.DiscoveryEventListener;
import com.flir.thermalsdk.live.discovery.DiscoveryFactory;
import com.flir.thermalsdk.live.streaming.ThermalImageStreamListener;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

/**
 * Encapsulates the handling of a FLIR ONE camera or built in emulator, discovery, connecting and start receiving images.
 * All listeners are called from Thermal SDK on a non-ui thread
 * <p/>
 * Usage:
 * <pre>
 * Start discovery of FLIR FLIR ONE cameras or built in FLIR ONE cameras emulators
 * {@linkplain #startDiscovery(DiscoveryEventListener, DiscoveryStatus)}
 * Use a discovered Camera {@linkplain Identity} and connect to the Camera
 * (note that calling connect is blocking and it is mandatory to call this function from a background thread):
 * {@linkplain #connect(Identity, ConnectionStatusListener)}
 * Once connected to a camera
 * {@linkplain #startStream(StreamDataListener)}
 * </pre>
 * <p/>
 * You don't *have* to specify your application to listen or USB intents but it might be beneficial for you application,
 * we are enumerating the USB devices during the discovery process which eliminates the need to listen for USB intents.
 * See the Android documentation about USB Host mode for more information
 * <p/>
 * Please note, this is <b>NOT</b> production quality code, error handling has been kept to a minimum to keep the code as clear and concise as possible
 */
class CameraHandler {

    private static final String TAG = "CameraHandler";

    private StreamDataListener streamDataListener;

    public interface StreamDataListener {
        void images(FrameDataHolder dataHolder);
       // void images(Bitmap thermalBitmap, Bitmap dcBitmap);
        void images(Bitmap thermalBitmap, Bitmap dcBitmap, double[][] tempArray);
    }


    //Discovered FLIR cameras
    LinkedList<Identity> foundCameraIdentities = new LinkedList<>();

    //A FLIR Camera
    private Camera camera;


    public interface DiscoveryStatus {
        void started();
        void stopped();
    }

    public CameraHandler() {
    }

    /**
     * Start discovery of USB and Emulators
     */
    public void startDiscovery(DiscoveryEventListener cameraDiscoveryListener, DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().scan(cameraDiscoveryListener, CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.started();
    }

    /**
     * Stop discovery of USB and Emulators
     */
    public void stopDiscovery(DiscoveryStatus discoveryStatus) {
        DiscoveryFactory.getInstance().stop(CommunicationInterface.EMULATOR, CommunicationInterface.USB);
        discoveryStatus.stopped();
    }

    public void connect(Identity identity, ConnectionStatusListener connectionStatusListener) throws IOException {
        Log.d(TAG, "Hi Lynne3");
        camera = new Camera();
        camera.connect(identity, connectionStatusListener,  new ConnectParameters());
    }

    public void disconnect() {
        if (camera == null) {
            return;
        }
        if (camera.isGrabbing()) {
            camera.unsubscribeAllStreams();
        }
        camera.disconnect();
    }

    /**
     *
     * @param heatmap is thermal image which contains all the thermal data
     * @return double 2d array of temperature per pixel
     */
    private double[][] getTemp(ThermalImage heatmap){
/*
        ThermalValue maxTemp= heatmap.getStatistics().max.asCelsius();
*/
        heatmap.setTemperatureUnit(TemperatureUnit.CELSIUS);
        // long startTime = System.nanoTime();

        int width=heatmap.getWidth();
        int height=heatmap.getHeight();
        double[][] temperature = new double[width][height];
        Rectangle rect = new Rectangle(0,0,width,height);
        double[] rectTemp = heatmap.getValues(rect);

        for(int i=0; i<width;i++)
            for(int j=0;j<height;j++)
                temperature[i][j] = rectTemp[(j*width) + i]; //row*number_col+col

        /*Log.d(TAG, "rectTemp: "+rectTemp[479]);
        Point pt = new Point(479, 0);
        double temp = heatmap.getValueAt(pt);
        Log.d(TAG, "getTemp: at point 0,639:"+temp);
        Log.d(TAG, "rectTemp: "+rectTemp[959]);
        Point pt1 = new Point(479, 1);
        double temp1 = heatmap.getValueAt(pt);
        Log.d(TAG, "getTemp: at point 1,639:"+temp1);*/

        /*long endTime = System.nanoTime();

        long duration = (endTime - startTime)/1000000;
        Log.d(TAG, "getTemp: duration of rect:"+duration);*/
        return temperature;
    }

    /**
     * Start a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void startStream(StreamDataListener listener) {
        this.streamDataListener = listener;
        camera.subscribeStream(thermalImageStreamListener);
    }

    /**
     * Stop a stream of {@link ThermalImage}s images from a FLIR ONE or emulator
     */
    public void stopStream(ThermalImageStreamListener listener) {
        camera.unsubscribeStream(listener);
    }

    /**
     * Add a found camera to the list of known cameras
     */
    public void add(Identity identity) {
        foundCameraIdentities.add(identity);
    }

    @Nullable
    public Identity get(int i) {
        return foundCameraIdentities.get(i);
    }

    /**
     * Get a read only list of all found cameras
     */
    @Nullable
    public List<Identity> getCameraList() {
        return Collections.unmodifiableList(foundCameraIdentities);
    }

    /**
     * Clear all known network cameras
     */
    public void clear() {
        foundCameraIdentities.clear();
    }

    @Nullable
    public Identity getCppEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("C++ Emulator")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    @Nullable
    public Identity getFlirOneEmulator() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            if (foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE")) {
                return foundCameraIdentity;
            }
        }
        return null;
    }

    /**
     * This method return instance of flir one mobile sdk class which will represent flir one device uniquly which identify flir one device
     * @return
     */
    @Nullable
    public Identity getFlirOne() {
        for (Identity foundCameraIdentity : foundCameraIdentities) {
            boolean isFlirOneEmulator = foundCameraIdentity.deviceId.contains("EMULATED FLIR ONE");
            boolean isCppEmulator = foundCameraIdentity.deviceId.contains("C++ Emulator");
            if (!isFlirOneEmulator && !isCppEmulator) {
                return foundCameraIdentity;
            }
        }

        return null;
    }
    private void withImage(ThermalImageStreamListener listener, Camera.Consumer<ThermalImage> functionToRun) {
        camera.withImage(listener, functionToRun);
    }

    /**
     * Called whenever there is a new Thermal Image available, should be used in conjunction with {@link Camera.Consumer}
     */
    private final ThermalImageStreamListener thermalImageStreamListener = new ThermalImageStreamListener() {

        @Override
        public void onImageReceived() {
            //Will be called on a non-ui thread
            Log.d(TAG, "onImageReceived(), we got another ThermalImage");
            withImage(this, handleIncomingImage);
            //stopStream(thermalImageStreamListener);
        }
    };

    /**
     * Function to process a Thermal Image and update UI
     */
    private final Camera.Consumer<ThermalImage> handleIncomingImage = new Camera.Consumer<ThermalImage>() {
        @Override
        public void accept(ThermalImage thermalImage) {
            Runtime runtime;
            long maxMemory;
            long usedMemory;
            double availableMemoryPercentage = 1.0;
            final double MIN_AVAILABLE_MEMORY_PERCENTAGE = 0.2;
            final int DELAY_TIME = 5 * 1000;

            runtime = Runtime.getRuntime();

            maxMemory = runtime.maxMemory();

            usedMemory = runtime.totalMemory() - runtime.freeMemory();

            availableMemoryPercentage = 1 - (double) usedMemory / maxMemory;

            if (availableMemoryPercentage < MIN_AVAILABLE_MEMORY_PERCENTAGE) {
                try {
                    Thread.sleep(DELAY_TIME);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }
            Log.d(TAG, "accept() called with: thermalImage = [" + thermalImage.getDescription() + "]");
            //Will be called on a non-ui thread,
            // extract information on the background thread and send the specific information to the UI thread

            //Get a bitmap with only IR data
            double[][] tempArray = getTemp(thermalImage); //gives temperature array of whole image

            //Bitmap thermalBitmap;
            Bitmap zeroMsxBitmap;
            {
                thermalImage.getFusion().setFusionMode(FusionMode.THERMAL_ONLY);
                //thermalBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();

                thermalImage.getFusion().setMsx(0);
                zeroMsxBitmap = BitmapAndroid.createBitmap(thermalImage.getImage()).getBitMap();
            }

            //Get a bitmap with the visual image, it might have different dimensions then the bitmap from THERMAL_ONLY
            Bitmap dcBitmap = BitmapAndroid.createBitmap(thermalImage.getFusion().getPhoto()).getBitMap();

            Log.d(TAG,"adding images to cache");
            //streamDataListener.images(zeroMsxBitmap,dcBitmap);
            streamDataListener.images(zeroMsxBitmap,dcBitmap, tempArray);
        }
    };


    /**
     * to return instance of Camera associated with the CameraHandler
     * @return Camera instance
     */
    public Camera getCamera() {
        return this.camera;
    }
}
