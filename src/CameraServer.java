import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.Executors;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;


public class CameraServer {
  private final static String NOT_FOUND = "Not found.";
  private final static String BOUNDARY = "IMAGE_BOUNDARY_DO_NOT_CROSS";
  
  private volatile byte[] image;
  private volatile long timestamp;

  static void copy(InputStream in, OutputStream out) throws IOException {
    byte[] buffer = new byte[16 * 1024];
    int len;
    while (-1 != (len = in.read(buffer))) {
      out.write(buffer, 0, len);
    }
  }
  
  class CameraServerHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
      URI uri = t.getRequestURI();
      String filename = uri.getPath().substring(8); // the uri starts with /camera
      System.out.println(filename);
      if (filename.isEmpty()) {
        filename = "index.html";
      }

      File file = new File("../assets/" + filename);
      if (file.exists()) {
        FileInputStream fin = new FileInputStream(file);
        t.sendResponseHeaders(200, 0);
        OutputStream os = t.getResponseBody();
        copy(fin, os);
        os.close();
      } else {
        t.sendResponseHeaders(404, NOT_FOUND.length());
        OutputStream os = t.getResponseBody();
        os.write(NOT_FOUND.getBytes());
        os.close();
      }
    }
  }
  
  class UploadHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
      ByteArrayOutputStream os = new ByteArrayOutputStream();
      copy(t.getRequestBody(), os);
      synchronized (CameraServer.this) {
        image = os.toByteArray();
        timestamp = System.currentTimeMillis();
        CameraServer.this.notifyAll();
      }
      t.sendResponseHeaders(200, -1);
      t.close();
    }
  }
  
  class VideoFeedHandler implements HttpHandler {
    @Override
    public void handle(HttpExchange t) throws IOException {
      Headers headers = t.getResponseHeaders();
      headers.add("Content-Type", "multipart/x-mixed-replace;boundary=" + BOUNDARY);
      headers.add("Pragma", "no-cache");
      headers.add("Cache-Control", "no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0");
      headers.add("Connection", "close");
      t.sendResponseHeaders(200, 0);
      OutputStream os = t.getResponseBody();
      os.write(("--" + BOUNDARY + "\r\n").getBytes());
      
      long last = 0;
      while (true) {
        if (last != timestamp) {
          byte[] image;
          synchronized (CameraServer.this) {
            image = CameraServer.this.image;
            last = timestamp;
          }
          os.write("Content-Type: image/jpeg\r\n\r\n".getBytes());
          os.write(image);
          os.write("\r\n".getBytes());
          os.write(("--" + BOUNDARY + "\r\n").getBytes());

          synchronized (CameraServer.this) {
            try {
              CameraServer.this.wait();
            } catch (InterruptedException e) { }
          }
        }
      }
    }
  }
  
  public static void main(String args[]) {
    CameraServer singleton = new CameraServer();
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress(8080), 5);
      server.createContext("/camera", singleton.new CameraServerHandler());
      server.createContext("/camera/upload", singleton.new UploadHandler());
      server.createContext("/videofeed", singleton.new VideoFeedHandler());
      server.setExecutor(Executors.newFixedThreadPool(5));
      server.start();
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
}
