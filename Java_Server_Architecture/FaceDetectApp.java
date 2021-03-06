package com.google.cloud.vision.samples;

/**
 * @author Aditya Prerepa
 * (C) Aditya Prerepa
 * Face Detection App - this class identifies faces.
 * Uses default credentials - Download your API key .json file
 * from google cloud platform.
 * Set an environment variable for :
 * GOOGLE_APPLICATION_CREDENTIALS = /path/to/your/credentials/
 *
 */

import com.google.api.client.googleapis.auth.oauth2.GoogleCredential;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.vision.v1.Vision;
import com.google.api.services.vision.v1.VisionScopes;
import com.google.api.services.vision.v1.model.AnnotateImageRequest;
import com.google.api.services.vision.v1.model.AnnotateImageResponse;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesRequest;
import com.google.api.services.vision.v1.model.BatchAnnotateImagesResponse;
import com.google.api.services.vision.v1.model.FaceAnnotation;
import com.google.api.services.vision.v1.model.Feature;
import com.google.api.services.vision.v1.model.Image;
import com.google.api.services.vision.v1.model.Vertex;
import com.google.common.collect.ImmutableList;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.List;
import javax.imageio.ImageIO;


public class FaceDetectApp {

  /**
   * A bunch of constants
   */
  private static final String APPLICATION_NAME = "Aditya-WebCamFaceDetect/1.1";

  private static final int MAX_RESULTS = 4;
  private static String url = "jdbc:mysql://localhost/WebCamImages";

  private static String user = "adi";
  private static String password = "adityapc";
  private static File file = new File("/home/aditya/pic/img.jpg");
  private static File outPutFile = new File("/home/aditya/pic/out.jpg");
  private static BufferedImage img;
  private static BufferedImage outImage;

  private static String webSiteAddress = "10.0.1.1";
  private static int webSitePort = 55555;

  public static void main(String[] args) {

    /**
     * Set up communications on local server hosted on port 6078. Tested on localhost.
     */
    Server server = new Server(6078);

    /**
     * Server is in a push/pull architecture.
     * This machine is the server for accepting the image from the
     * client with the webcam, but is the client for the sending of images.
     * All of this happens in a while true loop.
     * @// FIXME: 2/15/19 Make two servers hosted on 2 different ports to get rid of push/pull
     */
    while (true) {

      /**
       * Get a Buffered Image from the client
       */
      img = server.acceptCommunicationWithWebcamClient(file);

      /**
       * Commit the file (raw) to a MySQL database using the jdbc driver,
       * Yeah, I put my user and password out, but I disallowed remote connections.
       * All the initializing for the database is done here. I had a plan to make the
       * initialization a separate method, but for some reason that throws a
       * SQLException.
       * @// FIXME: 2/15/19 Make an INIT() function for the database.
       */
      server.commitToDatabase(file, url, user , password);

      /**
       * This is where the google API is accessed. The faces are retrieved from the cloud,
       * and are written to files as the finished product. All methods in this class are called
       * from here.
       */
      writeFacesToFiles();

      /**
       * Sends image to another client (in this case a website with a java wrapper)
       * The first image sent is the original image, and the second image sent is
       * the image with the faces on them, or maybe not. If faces are not detected in
       * a picture if they were at a certain point before, the same image sends.
       * @// FIXME: 2/15/19 Write a function to compare the outImages, if they are the same, send a null statement.
       */
      server.sendImageToWebsite(webSiteAddress, webSitePort, img);
      server.sendImageToWebsite(webSiteAddress, webSitePort, outImage);
    }

  }

  /**
   * Connects to the Vision API using Application Default Credentials.
   * Connect using the environment variable
   */
  private static Vision getVisionService() throws IOException, GeneralSecurityException {
    GoogleCredential credential =
        GoogleCredential.getApplicationDefault().createScoped(VisionScopes.all());
    JsonFactory jsonFactory = JacksonFactory.getDefaultInstance();
    return new Vision.Builder(GoogleNetHttpTransport.newTrustedTransport(), jsonFactory, credential)
            .setApplicationName(APPLICATION_NAME)
            .build();
  }

  private final Vision vision;

  /**
   * Constructs a {@link FaceDetectApp} which connects to the Vision API.
   */
  public FaceDetectApp(Vision vision) {
    this.vision = vision;
  }

  /**
   * Gets up to {@code maxResults} faces for an image stored at {@code path}.
   * Starts detect Request to the google vision api, tries to get the faces.
   */
  private List<FaceAnnotation> detectFaces(Path path, int maxResults) throws IOException {
    byte[] data = Files.readAllBytes(path);

    AnnotateImageRequest request =
        new AnnotateImageRequest()
            .setImage(new Image().encodeContent(data))
            .setFeatures(ImmutableList.of(
                new Feature()
                    .setType("FACE_DETECTION")
                    .setMaxResults(maxResults)));
    Vision.Images.Annotate annotate =
        vision.images()
            .annotate(new BatchAnnotateImagesRequest().setRequests(ImmutableList.of(request)));
    // Due to a bug: requests to Vision API containing large images fail when GZipped.
    annotate.setDisableGZipContent(true);

    BatchAnnotateImagesResponse batchResponse = annotate.execute();
    assert batchResponse.getResponses().size() == 1;
    AnnotateImageResponse response = batchResponse.getResponses().get(0);
    if (response.getFaceAnnotations() == null) {
      throw new IOException(
          response.getError() != null
              ? response.getError().getMessage()
              : "Unknown error getting image annotations");
    }
    return response.getFaceAnnotations();
  }

  /**
   * Reads image {@code inputPath} and writes {@code outputPath} with {@code faces} outlined.
   * Writes all the faces that are given to it through {@param List<FaceAnnotation>} and writes them
   * spontaneously to a file.
   */
  private static void writeWithFaces(Path inputPath, Path outputPath, List<FaceAnnotation> faces) throws IOException {
    BufferedImage img = ImageIO.read(inputPath.toFile());
    annotateWithFaces(img, faces);
    ImageIO.write(img, "jpg", outputPath.toFile());
  }

  /**
   * Annotates an image {@code img} with a polygon around each face in {@code faces}.
   * An iterator for all the faces.
   */
  private static void annotateWithFaces(BufferedImage img, List<FaceAnnotation> faces) {
    for (FaceAnnotation face : faces) {
      annotateWithFace(img, face);
    }
  }

  /**
   * Annotates an image {@code img} with a polygon defined by {@code face}.
   * {@param BufferedImage img} {@param FaceAnnotation face}
   * Draws a blue box around what the google vision API thinks is your face.
   */
  private static void annotateWithFace(BufferedImage img, FaceAnnotation face) {
    Graphics2D gfx = img.createGraphics();
    Polygon poly = new Polygon();
    for (Vertex vertex : face.getFdBoundingPoly().getVertices()) {
      poly.addPoint(vertex.getX(), vertex.getY());
    }
    gfx.setStroke(new BasicStroke(5));
    gfx.setColor(new Color(0x1E008D));
    gfx.draw(poly);
  }

  /**
   * Annotates an image using the Vision API.
   * @apiNote Google Vision
   * Gets the input and output paths for writing to the files -
   * make your own, my paths will create a FileNotFound Exception
   *
   * If there is one of more face, this method prints how many faces were found,
   * and writes the image to a file (change your path)
   *
   * Also makes the OutputPath file contents into a bufferedImage,
   * so the image can be send away somewhere for further use.
   */
  private static void writeFacesToFiles() {
    Path inputPath = Paths.get(file.toString());
    Path outputPath = Paths.get(outPutFile.toString());

    try {
      FaceDetectApp app = new FaceDetectApp(getVisionService());
      List<FaceAnnotation> faces = app.detectFaces(inputPath, MAX_RESULTS);
      System.out.printf("Found %d face%s\n", faces.size(), faces.size() == 1 ? "" : "s");
      System.out.printf("Writing to file %s\n", outputPath);
      app.writeWithFaces(inputPath, outputPath, faces);

      outImage = ImageIO.read(outputPath.toFile());

    } catch (IOException ioException) {
      ioException.printStackTrace();

    } catch (GeneralSecurityException e) {
      e.printStackTrace();

    }

  }

}
