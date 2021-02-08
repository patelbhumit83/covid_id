/*
 * Copyright 2019 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.ilab.covid_id.crowd.CrowdCount;

import android.graphics.Bitmap;
import android.graphics.Bitmap.Config;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Style;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.media.ImageReader.OnImageAvailableListener;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Size;
import android.util.TypedValue;
import android.widget.Toast;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.GeoPoint;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;

import edu.ilab.covid_id.MapsActivity;
import edu.ilab.covid_id.R;
import edu.ilab.covid_id.data.CovidRecord;
import edu.ilab.covid_id.localize.customview.OverlayView;
import edu.ilab.covid_id.localize.customview.OverlayView.DrawCallback;
import edu.ilab.covid_id.localize.env.BorderedText;
import edu.ilab.covid_id.localize.env.ImageUtils;
import edu.ilab.covid_id.localize.env.Logger;
import edu.ilab.covid_id.localize.tflite.Classifier;
import edu.ilab.covid_id.localize.tflite.TFLiteObjectDetectionEfficientDet;
import edu.ilab.covid_id.localize.tracking.MultiBoxTracker;
import edu.ilab.covid_id.storage.FirebaseStorageUtil;

/**
 * An activity that uses a TensorFlowMultiBoxDetector and ObjectTracker to detect and then track
 * objects.
 */
public class DetectorActivity extends CameraActivity implements OnImageAvailableListener {

  // temporary flag for determining when to create/push records to the db
  int flag = 0;

  private static final Logger LOGGER = new Logger();

  // Configuration values for the prepackaged SSD model.
  private static final int TF_OD_API_INPUT_SIZE = 512;    //this is the wxh of square input size to MODEL
  private static final boolean TF_OD_API_IS_QUANTIZED = true;  //if its quantized or not. MUST be whatever the save tflite model is saved as
  private static final String TF_OD_API_MODEL_FILE = "DPDMdetector.tflite";   //name of input file for MODEL must be tflite format
                                                                        //TIP: if creating subclass for say mask detection make your detector
                                                                        //   file called maskdetect.flite and put in assets folder
  private static final String TF_OD_API_LABELS_FILE = "file:///android_asset/DPDMlabelmap.txt";  //LabelMap file listed classes--same order as training
                                                                                            //TIP: if creating subclass for say mask detector then make a
                                                                                            //   file called masklabelmap.txt and put in assets folder



  private static final DetectorMode MODE = DetectorMode.TF_OD_API;   //Using Object Detection API
  // Minimum detection confidence to track a detection.
  private static final float MINIMUM_CONFIDENCE_TF_OD_API = 0.5f;   //a detected prediction must have value > threshold to be displayed
  private static final boolean MAINTAIN_ASPECT = false;  //if you want to keep aspect ration or not --THIS must be same as what is expected in model,done in training
  private static final Size DESIRED_PREVIEW_SIZE = new Size(640, 480); //for display ONLY specific to THIS activity
  private static final boolean SAVE_PREVIEW_BITMAP = false;  //specific to THIS activity
  private static final float TEXT_SIZE_DIP = 10;  //font size for dipsaly of bounding boxes
  OverlayView trackingOverlay;   //boudning box and prediction info is drawn on screen using an OverlayView
  private Integer sensorOrientation;  //this Activity does rotation for different Orientations

  private Classifier detector;  //class variable representing the actual model loaded up
                                // note this is  edu.ilab.covid_id.localize.tflite.Classifier;

  private long lastProcessingTimeMs;   //last time processed a frame
  private Bitmap rgbFrameBitmap = null;  //various bitmap variables used in code below
  private Bitmap croppedBitmap = null;
  private Bitmap cropCopyBitmap = null;

  private boolean computingDetection = false;

  private long timestamp = 0;

  private Matrix frameToCropTransform;
  private Matrix cropToFrameTransform;

  private MultiBoxTracker tracker; // this class assists with tracking bounding boxes - represents results
                                   //note this is instance of edu.ilab.covid_id.localize.tracking.MultiBoxTracker;

  private BorderedText borderedText;

  float riskThresholdHigh_SocDist;
  float riskThresholdCaution_SocDist;

  @Override
  protected void onCreate(final Bundle savedInstanceState) {
    this.riskThresholdHigh_SocDist =  getApplicationContext().getResources().getInteger(R.integer.riskThresholdHigh_SocDist);
    this.riskThresholdCaution_SocDist = getApplicationContext().getResources().getInteger(R.integer.riskThresholdCaution_SocDist);

    //safety check hardcoded defaults if out of range
    if (riskThresholdCaution_SocDist >= riskThresholdHigh_SocDist || riskThresholdCaution_SocDist < 0 || riskThresholdHigh_SocDist < 0 || riskThresholdCaution_SocDist > 100 || riskThresholdHigh_SocDist > 100) {
      riskThresholdHigh_SocDist = 100;
      riskThresholdCaution_SocDist = 70;
    }
    super.onCreate(savedInstanceState);

  }

  /**
   * The PARENT class of this class CameraActivity is responsible for connecting to camera on Device
   * it has a callback method when the camera is ready that will call this method.
   * This method creaets the tracker (to store bounding box info), and the detector (the actual model
   * loaded from a tflite used for detection) and sets up various GUI elements and bitmaps for displaying results.
   * THis is really just a SETUP method
   **/
  @Override
  public void onPreviewSizeChosen(final Size size, final int rotation) {
    final float textSizePx =
        TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, TEXT_SIZE_DIP, getResources().getDisplayMetrics());
    borderedText = new BorderedText(textSizePx);
    borderedText.setTypeface(Typeface.MONOSPACE);


    //class to contain detection results with bounding box information
    tracker = new MultiBoxTracker(this);

    //specifying the size you want as input to your model...which will be used later in image processing of input images to resize them.
    int cropSize = TF_OD_API_INPUT_SIZE;

    //load up the detector based on the specified parameters include the tflite file in the assets folder, etc.
    try {
      detector =
              TFLiteObjectDetectionEfficientDet.create(
              getAssets(),
              TF_OD_API_MODEL_FILE,
              TF_OD_API_LABELS_FILE,
              TF_OD_API_INPUT_SIZE,
              TF_OD_API_IS_QUANTIZED);
      cropSize = TF_OD_API_INPUT_SIZE;
    } catch (final IOException e) {
      e.printStackTrace();
      LOGGER.e(e, "Exception initializing classifier!");
      Toast toast =
          Toast.makeText(
              getApplicationContext(), "Classifier could not be initialized", Toast.LENGTH_SHORT);
      toast.show();
      finish();
    }

    //display size
    previewWidth = size.getWidth();
    previewHeight = size.getHeight();



    sensorOrientation =  rotation - getScreenOrientation();   //sensorOreintation will be 0 for horizontal and 90 for portrait


    LOGGER.i("Camera orientation relative to screen canvas: %d", sensorOrientation);

    LOGGER.i("Initializing at size %dx%d", previewWidth, previewHeight);

    //seting up the bitmap input image  based on grabing it from the preview display of it.
    rgbFrameBitmap = Bitmap.createBitmap(previewWidth, previewHeight, Config.ARGB_8888);
    //setting up the bitmap to store the resized input image to the size that the model expects
    croppedBitmap = Bitmap.createBitmap(cropSize, cropSize, Config.ARGB_8888);

    //create a transformation that will be used to convert the input image to the right size and orientation expected by the model
    //   involves resizing (to cropsizexcropsize) from the original previewWidthxpreviewHeight
    //   involves rotation based on sensorOrientation
    //   invovles if you want aspect to be maintained
    frameToCropTransform =
        ImageUtils.getTransformationMatrix(
            previewWidth, previewHeight,
            cropSize, cropSize,
            sensorOrientation, MAINTAIN_ASPECT);  //TIP: if you want no rotation than sensorOreination should be 0

    cropToFrameTransform = new Matrix();  //identity matrix initially
    frameToCropTransform.invert(cropToFrameTransform);  //calculating the cropToFrameTransform as the inversion of the frameToCropTransform


    //grabbing a handle to the tracking_overlay which lets us draw bounding boxes inside of and this is a fragment
    // that sits on top of the ImageView where the image is displayed.
    trackingOverlay = (OverlayView) findViewById(R.id.tracking_overlay);
    trackingOverlay.addCallback(
        new DrawCallback() {
          @Override
          public void drawCallback(final Canvas canvas) {
            tracker.draw(canvas);
            if (isDebug()) {
              tracker.drawDebug(canvas);
            }
          }
        });

    //making sure the overlay fragment is same wxh and orientation as the ImageView and its image displayed inside.
    tracker.setFrameConfiguration(previewWidth, previewHeight, sensorOrientation);
  }


  /**
   * This method is called every time we will to process the CURRENT frame
   * this means the current frame/image will be processed by our this.detector model
   * and results are cycled through (can be more than one deteciton in an image) and displayed
   */
  @Override
  protected void processImage() {
    ++timestamp;
    final long currTimestamp = timestamp;
    trackingOverlay.postInvalidate();

    // No mutex needed as this method is not reentrant.
    if (computingDetection) {
      readyForNextImage();
      return;
    }
    computingDetection = true;
    LOGGER.i("Preparing image " + currTimestamp + " for detection in bg thread.");

    //LOAD the current image --calling getRgbBytes method into the rgbFrameBitmap object
    rgbFrameBitmap.setPixels(getRgbBytes(), 0, previewWidth, 0, 0, previewWidth, previewHeight);

    readyForNextImage();

    //create a drawing canvas that is associated with the image croppedBitmap that will be the transformed input image to the right size and orientation
    final Canvas canvas = new Canvas(croppedBitmap);

    //CROP and transform
    //why working in portrait mode and not horizontal
    //canvas.drawBitmap(rgbFrameBitmap,new Matrix(), null);   //need to only rotate it.
    // canvas.drawBitmap(croppedBitmap, cropToFrameTransform, null); //try this later???
    canvas.drawBitmap(rgbFrameBitmap, frameToCropTransform, null);   ///crop and transform as necessary image
    // For examining the actual TF input.
    if (SAVE_PREVIEW_BITMAP) {
      ImageUtils.saveBitmap(croppedBitmap);
    }

    //Need to run in separate thread ---to process the image --going to call the model to do prediction
    // because of this must run in own thread.
    runInBackground(
        new Runnable() {
          @Override
          public void run() {
            LOGGER.i("Running detection on image " + currTimestamp);
            final long startTime = SystemClock.uptimeMillis();
            final List<Classifier.Recognition> results = detector.recognizeImage(croppedBitmap);  //performing detection on croppedBitmap
            final List<Person> persons = new ArrayList<Person>();

            //STEP 1: create image to display and to save in backend record
            cropCopyBitmap = Bitmap.createBitmap(croppedBitmap);
            final Canvas canvas = new Canvas(cropCopyBitmap);   //create canvas to draw bounding boxes inside of which will be displayed in OverlayView
            final Paint paint = new Paint();
            paint.setColor(Color.RED);
            paint.setStyle(Style.STROKE);
            paint.setStrokeWidth(2.0f);

            //STEP2: copy over recognition results into persons list
            int count =1 ;
            for (final Classifier.Recognition result : results) {
              if (result.getTitle().contains("Person")) {
                persons.add(new Person(count, result));
                count++;

              }
            }


            lastProcessingTimeMs = SystemClock.uptimeMillis() - startTime;
            SocDist mySocdist;


            //specify thresholds for minumumConfidence before store record or show results
            float minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
            switch (MODE) {
              case TF_OD_API:
                minimumConfidence = MINIMUM_CONFIDENCE_TF_OD_API;
                break;
            }

            //mappedREcognitons is a list of recognitions used in display
            final List<Classifier.Recognition> mappedRecognitions =
                    new LinkedList<Classifier.Recognition>();


            //Now cycle through the person and for each unique pair calculate distance
            for (final Person person1 : persons)
              for (final Person person2 : persons) {
                //only process if diff person
                if (person1.different(person2)) {
                  //STEP 1: create SocDist object that measures distance between 2 persons and setups up label, risk, etc.
                  mySocdist =  new SocDist(person1,
                            person2,
                            riskThresholdCaution_SocDist,
                            riskThresholdHigh_SocDist);








                    int saveImageOnceFlag = 1;
                    String imageFileURL = "";
                    if (mySocdist.getConfidence() >= minimumConfidence) { //ONLY display if the result has a confidence > threshold

                      //draw bounding boxes of 2 persons
                      canvas.drawRect(person1.result.getLocation(), paint);  //draw in the canvas the bounding boxes-->
                      canvas.drawRect(person2.result.getLocation(), paint);  //draw in the canvas the bounding boxes-->


                        //##################################################################
                        //Store to Firebase Database  -- if we are ready since last record storage to make a new record
                        if(CovidRecord.readyStoreRecord(MapsActivity.socDistRecordLastStoreTimestamp, MapsActivity.deltaSocDistRecordStoreTimeMS, MapsActivity.socDistRecordLastStoreLocation, MapsActivity.currentLocation, MapsActivity.deltaSocDistRecordStoreLocationM)) {
                          Date d = new Date();
                          ArrayList<Float> angles = new ArrayList<Float>();
                          angles.add(0, 0.0f);
                          angles.add(1, 0.0f);
                          angles.add(2, 0.0f);

                          ArrayList<Float> boundingBox = new ArrayList<Float>();
                          boundingBox.add(0, person1.result.getLocation().left);
                          boundingBox.add(1, person1.result.getLocation().top);
                          boundingBox.add(2, person1.result.getLocation().right);
                          boundingBox.add( 3, person1.result.getLocation().bottom);



                          ArrayList<Float> boundingBox2 = new ArrayList<Float>();
                          boundingBox.add(0, person2.result.getLocation().left);
                          boundingBox.add(1, person2.result.getLocation().top);
                          boundingBox.add(2, person2.result.getLocation().right);
                          boundingBox.add( 3, person2.result.getLocation().bottom);


                          CovidRecord myRecord = new CovidRecord(mySocdist.risk, mySocdist.getConfidence()*100,
                                  new GeoPoint(MapsActivity.currentLocation.getLatitude(), MapsActivity.currentLocation.getLongitude()),
                                  Timestamp.now(), imageFileURL, mySocdist.label,boundingBox, boundingBox2, angles, 0.0f,
                                  MapsActivity.userEmailFirebase, MapsActivity.userIdFirebase, mySocdist.distance, "socDist");


                          FirebaseStorageUtil.storeImageAndCovidRecord(cropCopyBitmap, myRecord, MapsActivity.currentLocation, "covidRecord");
                        }
                        //###############################################

                      /*
                      //follwing code using to add person 1 of this pair to draw in overlay
                        RectF location1 = new RectF(person1.result.getLocation());
                        cropToFrameTransform.mapRect(location1);  //transforms using Matrix the bounding box to the correct transformed coordinates

                        Classifier.Recognition result1 = new Classifier.Recognition(person1.result);
                        result1.setLocation(location1); // reset the newly transformed rectangle (location) representing bounding box inside the result
                        mappedRecognitions.add(result1);  //add the result to a linked list


                        //for drawing person 2
                        RectF location2 = new RectF(person2.result.getLocation());
                        cropToFrameTransform.mapRect(location2);  //transforms using Matrix the bounding box to the correct transformed coordinates

                        Classifier.Recognition result2 = new Classifier.Recognition(person2.result);
                        result2.setLocation(location2); // reset the newly transformed rectangle (location) representing bounding box inside the result
                        mappedRecognitions.add(result2);  //add the result to a linked list

                        */

                        //generate a new BIG bounding box that sourronds both people with the label
                        // mysocDist.label + distance  and its confidence (caustion (10) - 92.3%)

                        RectF location1 = new RectF(person1.result.getLocation());
                        cropToFrameTransform.mapRect(location1);

                        RectF location2 = new RectF(person2.result.getLocation());
                        cropToFrameTransform.mapRect(location2);

                        RectF bigBox = new RectF(location1);
                        bigBox.union(location2);
                        Classifier.Recognition socDistRecognition = new Classifier.Recognition(person1.result);
                        socDistRecognition.setLocation(bigBox);
                        socDistRecognition.setTitle(mySocdist.label + "- (" + mySocdist.confidence + ")");
                        socDistRecognition.setConfidence(mySocdist.confidence);

                        mappedRecognitions.add(socDistRecognition);  //add the result to a linked list





                    }


                  }
                }

            //after cycling through all pairs of persons we are ready to draw all the detected person boxes
            // and their big box soc distancbox w/label (cautionketc) for each pari)
            tracker.trackResults(mappedRecognitions, currTimestamp);  //DOES DRAWING:  OverlayView to dispaly the recognition bounding boxes that have been transformed and stored in LL mappedRecogntions
            trackingOverlay.postInvalidate();

            computingDetection = false;

            runOnUiThread(
                    new Runnable() {
                      @Override
                      public void run() {
                        showFrameInfo(previewWidth + "x" + previewHeight);
                        showCropInfo(cropCopyBitmap.getWidth() + "x" + cropCopyBitmap.getHeight());
                        showInference(lastProcessingTimeMs + "ms");
                      }
                    });




          }
        });







  }

  @Override
  protected int getLayoutId() {
    return R.layout.tfe_od_camera_connection_fragment_tracking;
  }

  @Override
  protected Size getDesiredPreviewFrameSize() {
    return DESIRED_PREVIEW_SIZE;
  }

  // Which detection model to use: by default uses Tensorflow Object Detection API frozen
  // checkpoints.
  public enum DetectorMode {
    TF_OD_API;
  }

  @Override
  protected void setUseNNAPI(final boolean isChecked) {
    runInBackground(() -> detector.setUseNNAPI(isChecked));
  }

  @Override
  protected void setNumThreads(final int numThreads) {
    runInBackground(() -> detector.setNumThreads(numThreads));
  }
}
