import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.gui.DialogListener;
import ij.gui.GenericDialog;
import ij.gui.NewImage;
import ij.gui.Plot;
import ij.gui.PlotWindow;
import ij.gui.Roi;
import ij.gui.PolygonRoi;
import ij.io.OpenDialog;
import ij.io.SaveDialog;
import ij.measure.CurveFitter;
import ij.plugin.ChannelSplitter;
import ij.plugin.PlugIn;
import ij.plugin.frame.RoiManager;
import ij.process.ImageProcessor;
import ij.plugin.RGBStackConverter;


import java.awt.AWTEvent;
import java.awt.Checkbox;
import java.awt.Color;
import java.awt.Rectangle;
import java.awt.TextField;


import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.util.Vector;
import java.util.Map;
import java.util.Collection;
import java.util.Iterator;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;

public class CalibrateDirectory implements PlugIn{

  private boolean useQR = false;
  private String qrDir = "";
  private String qrFilename = "";
  private String qrPath = "";
  private String qrCamera = "";

  private String cameraType = "";
  private RGBPhoto qrPhoto = null;
  private RGBPhoto qrScaled = null;

  private String inputDir = "";
  private String outputDir = "";

  private File fileInputDir = null;
  private File fileOutputDir = null;
  private File[] imagesToCalibrate = null;
  private List<File> jpgToCalibrate = new ArrayList<File>();
  private List<File> tifToCalibrate = new ArrayList<File>();

  private EXIFTool exif = null;


  private final double[] COEFF_RED = {-0.31238818, 2.35239490};
  private final double[] COEFF_GREEN = {-0.32874756, 5.44419416};
  private final double[] COEFF_BLUE = {-0.40351347, 1.58893643};
  private final double[] COEFF_NDVI = {-0321163012, 1.81411080, -0.09248552, 3.05593169};
  private final double[] COEFF_NIR = {-1.18032326, 10.92737546};

  private String OS = System.getProperty("os.name");
  private String WorkingDirectory = IJ.getDirectory("imagej");
  private String PATH_TO_VALUES = WorkingDirectory+"Calibration\\values.csv";
  private String PATH_TO_EXIFTOOL = WorkingDirectory+"Survey2\\EXIFTool\\exiftool.exe";



  public void run(String arg){
    Calibrator calibrator = new Calibrator();
    Roi[] rois = null;
    CalibrationPrompt prompts = new CalibrationPrompt();
    prompts.showFullDialog();
    if( prompts.wasCanceledFullDialog() ){
      IJ.log("Goodbye!");
      return;
    }


    HashMap<String, String> fullDialogValues = prompts.getFullDialogValues();
    HashMap<String, String> qrFileDialogValues = null;
    useQR = Boolean.parseBoolean( fullDialogValues.get(CalibrationPrompt.MAP_USEQR) );
    IJ.log(fullDialogValues.get(CalibrationPrompt.MAP_USEQR));
    IJ.log( String.valueOf(useQR) );
    cameraType = fullDialogValues.get(CalibrationPrompt.MAP_CAMERA);


    if( useQR == true ){
      prompts.showQRFileDialog();
      qrFileDialogValues = prompts.getQRFileDialogValues();
      qrDir = qrFileDialogValues.get(CalibrationPrompt.MAP_IMAGEDIR);
      qrPath = qrFileDialogValues.get(CalibrationPrompt.MAP_IMAGEPATH);
      qrFilename = qrFileDialogValues.get(CalibrationPrompt.MAP_IMAGEFILENAME);

      qrPhoto = new RGBPhoto(qrDir, qrFilename, qrPath, cameraType);
      qrScaled = calibrator.scaleChannels(qrPhoto);

      rois = calibrator.getRois(qrPhoto.getImage());

      if( rois == null ){
        IJ.log("ATTN: QR calibration targets could not be found. I will use default coefficient values.");
      }

    }else{
      // Use base files

    }

    prompts.showImageFileDialog();
    HashMap<String, String> imageFileDialogValues = prompts.getImageFileDialogValues();
    inputDir = imageFileDialogValues.get(CalibrationPrompt.MAP_IMAGEDIR);
    outputDir = inputDir + "\\Calibrated\\";

    // Create output Folder
    fileOutputDir = new File(outputDir);
    if( !fileOutputDir.exists() ){
      fileOutputDir.mkdir();
    }



    IJ.log("I will begin to process the images in ");
    // Get all images to process
    fileInputDir = new File(inputDir);
    imagesToCalibrate = fileInputDir.listFiles();

    for( int i=0; i<imagesToCalibrate.length; i++ ){
      String[] inImageParts = (imagesToCalibrate[i].getName()).split("\\.(?=[^\\.]+$)");
      String inImageExt = null;

      if( inImageParts.length < 2){
        continue;
      }else{
        inImageExt = inImageParts[1];
      }

      if( inImageExt.toUpperCase().equals("JPG") || inImageExt.toUpperCase().equals("TIF") ){
        jpgToCalibrate.add(imagesToCalibrate[i]);
      }
    }

    IJ.log("---------------Images To Calibrate---------------");
    for( int i=0; i<jpgToCalibrate.size(); i++ ){
      IJ.log(jpgToCalibrate.get(i).getName());
    }
    for( int i=0; i<tifToCalibrate.size(); i++ ){
      IJ.log(tifToCalibrate.get(i).getName());
    }

    // Load in photo (only after calibration coefficients have been prepared)
    RGBPhoto photo = null;
    RGBPhoto resultphoto = null;
    Iterator<File> jpgIterator = jpgToCalibrate.iterator();
    Iterator<File> tifIterator = tifToCalibrate.iterator();
    File tmpfile = null;

    //OpenDialog baseFileDialog = new OpenDialog("Select Base File");
    //File bfs = new File(baseFileDialog.getPath());
    File bfs = new File(PATH_TO_VALUES);

    double[][] baseSummary = null;
    double[] coeffs = new double[4];
    double[] tmpcoeff = null;

    while( jpgIterator.hasNext() ){
      tmpfile = jpgIterator.next();

      photo = new RGBPhoto(inputDir, tmpfile.getName(), tmpfile.getPath(), cameraType);
      //photo.show();


      if( cameraType.equals(CalibrationPrompt.SURVEY2_NDVI) ){
        // Use red channel and blue channel
        baseSummary = calibrator.getRefValues(bfs, "660/850");

        if( useQR == true && rois != null ){
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Red");
          coeffs[0] = tmpcoeff[0];
          coeffs[1] = tmpcoeff[1];
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Blue");
          coeffs[2] = tmpcoeff[0];
          coeffs[3] = tmpcoeff[1];
        }else{
          coeffs[0] = COEFF_NDVI[0];
          coeffs[1] = COEFF_NDVI[1];
          coeffs[2] = COEFF_NDVI[2];
          coeffs[3] = COEFF_NDVI[3];
        }


        resultphoto = calibrator.makeNDVI(photo, coeffs);

      }else if( cameraType.equals(CalibrationPrompt.SURVEY2_NIR) ){
        baseSummary = calibrator.getRefValues(bfs, "850");

        if( useQR == true && rois != null ){
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Red");
          coeffs[0] = tmpcoeff[0];
          coeffs[1] = tmpcoeff[1];
        }else{
          coeffs[0] = COEFF_NIR[0];
          coeffs[1] = COEFF_NIR[1];
        }

        resultphoto = calibrator.makeSingle(photo, coeffs);

      }else if( cameraType.equals(CalibrationPrompt.SURVEY2_RED) ){
        baseSummary = calibrator.getRefValues(bfs, "650");


        if( useQR == true && rois != null ){
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Red");
          coeffs[0] = tmpcoeff[0];
          coeffs[1] = tmpcoeff[1];
        }else{
          coeffs[0] = COEFF_RED[0];
          coeffs[1] = COEFF_RED[1];
        }

        resultphoto = calibrator.makeSingle(photo, coeffs);

      }else if( cameraType.equals(CalibrationPrompt.SURVEY2_GREEN) ){
        baseSummary = calibrator.getRefValues(bfs, "548");


        if( useQR == true && rois != null ){
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Green");
          coeffs[0] = tmpcoeff[0];
          coeffs[1] = tmpcoeff[1];
        }else{
          coeffs[0] = COEFF_GREEN[0];
          coeffs[1] = COEFF_GREEN[1];
        }

        resultphoto = calibrator.makeSingle(photo, coeffs);

      }else if( cameraType.equals(CalibrationPrompt.SURVEY2_BLUE) ){
        baseSummary = calibrator.getRefValues(bfs, "450");


        if( useQR == true && rois != null ){
          tmpcoeff = calculateCoefficients(qrScaled, rois, calibrator, baseSummary, "Blue");
          coeffs[0] = tmpcoeff[0];
          coeffs[1] = tmpcoeff[1];
        }else{
          coeffs[0] = COEFF_BLUE[0];
          coeffs[1] = COEFF_BLUE[1];
        }

        resultphoto = calibrator.makeSingle(photo, coeffs);

      }else{
        // IDK
      }

      //resultphoto.show();

      RGBStackConverter.convertToRGB(resultphoto.getImage());
      resultphoto.copyFileData(photo);
      //resultphoto.show();
      //resultphoto.show();
      IJ.log("Saving Image");
      saveToDir(outputDir, resultphoto.getFileName(), resultphoto.getExtension(), resultphoto.getImage());
      IJ.log("Saved");

      // Write EXIF data
      IJ.log("Copying EXIF data");
      /*
      exif = new EXIFTool(tmpfile, new File(outputDir+resultphoto.getFileName()), new File(outputDir+resultphoto.getFileName()+".tmp") );
      exif.copyEXIF();
      */
      //EXIFTool.copyEXIF( (new File(outputDir+resultphoto.getFileName()+"_Calibrated"+"."+resultphoto.getExtension())), tmpfile );
      //CopyEXIFData()
      CopyEXIFData(OS, PATH_TO_EXIFTOOL, tmpfile.getAbsolutePath(), outputDir+resultphoto.getFileName()+"_Calibrated"+"."+resultphoto.getExtension());

    }

  }


  public double[] calculateCoefficients(RGBPhoto qrphoto, Roi[] rois, Calibrator calibrator, double[][] baseSummary, String channel){
    //Roi[] rois = null;
    RoiManager manager = null;
    double[] coeff = null;
    List<HashMap<String, String>> bandSummary = null;
    double[] refmeans = new double[3];

    //baseSummary = calibrator.getRefValues(bfs, "660/850"); // <-----Change this!!
    if( qrphoto == null ){

    }else{
      //rois = calibrator.getRois(qrphoto.getImage());
      if( channel.equals("Red") ){
        manager = calibrator.setupROIManager(qrphoto.getRedChannel(), rois);
        bandSummary = calibrator.processRois(qrphoto.getRedChannel(), manager); // <--- This is the key

        refmeans[0] = baseSummary[0][0];
        refmeans[1] = baseSummary[1][0];
        refmeans[2] = baseSummary[2][0];
      }else if( channel.equals("Green") ){
        manager = calibrator.setupROIManager(qrphoto.getGreenChannel(), rois);
        bandSummary = calibrator.processRois(qrphoto.getGreenChannel(), manager); // <--- This is the key

        refmeans[0] = baseSummary[0][1];
        refmeans[1] = baseSummary[1][1];
        refmeans[2] = baseSummary[2][1];
      }else if( channel.equals("Blue") ){
        //qrphoto.getBlueChannel().show();
        manager = calibrator.setupROIManager(qrphoto.getBlueChannel(), rois);
        bandSummary = calibrator.processRois(qrphoto.getBlueChannel(), manager); // <--- This is the key

        refmeans[0] = baseSummary[0][2];
        refmeans[1] = baseSummary[1][2];
        refmeans[2] = baseSummary[2][2];
    }

    //@TODO supply QR CODE channels, not image to calibrate


    double[] means = {Double.parseDouble(bandSummary.get(0).get(Calibrator.MAP_MEAN)),
        Double.parseDouble(bandSummary.get(1).get(Calibrator.MAP_MEAN)),
        Double.parseDouble(bandSummary.get(2).get(Calibrator.MAP_MEAN))};
    //double[] refmeans = {baseSummary[0][0], baseSummary[1][0], baseSummary[2][0]};

    coeff = calibrator.calculateCalibrationCoefficients( means, refmeans );

    manager.reset();
    manager.close();
    }
    return coeff;
  }


  public void saveToDir(String outdir, String filename, String ext, ImagePlus image){
    String NDVIAppend = "_Calibrated";

    IJ.log("Output Directory: " + outdir);
    IJ.log("Filename: " + filename);
    IJ.log("Save as extension: " + ext);



    IJ.save((ImagePlus)image, (String)(String.valueOf(outdir) + filename + NDVIAppend + "." + ext));
    return;
  }

  public void CopyEXIFData(String osType, String exiftoolpath, String refimg, String targimg ){
    String console = null;
    String c_arg = null;
    String command = null;
    ProcessBuilder bob = null;
    Process proc = null;
  	if ( osType.contains("Windows") ) {
      console = "cmd";
      c_arg = "/c";
      try{
        command = exiftoolpath + " -overwrite_original -tagsfromfile " + "\""+refimg+"\"" + " " + "\""+targimg+"\"";
        IJ.log("Executing command: " + command);
        bob = new ProcessBuilder(console, c_arg, command);
        bob.redirectErrorStream(false);
        proc = bob.start();
        proc.waitFor();

      }catch( IOException e){
        e.printStackTrace();
      }catch( InterruptedException i ){
        i.printStackTrace();
      }

  	} else {
      console = "sh";
      c_arg = "-c";

      try{
        // directory spaces
        command = exiftoolpath + " -overwrite_original -tagsfromfile " + "\'"+refimg+"\'" + " " + "\'"+targimg+"\'";
        IJ.log("Executing command: " + command);
        bob = new ProcessBuilder(console, c_arg, command);
        bob.redirectErrorStream(true);
        proc = bob.start();
        proc.waitFor();

      }catch( IOException e){
        e.printStackTrace();
      }catch( InterruptedException i ){
        i.printStackTrace();
      }

    }

    IJ.log("Finished writing EXIF data");

  }


}
