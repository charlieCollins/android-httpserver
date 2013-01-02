package com.totsp.server;

import com.totsp.server.enums.Status;
import com.totsp.server.enums.SupportedFileType;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HTTP server for serving content FROM an Android device (supports very limited GET only). 
 * Supports two types of requests (and again is very basic): 
 * 
 * 1. MEDIA requests, serves files as streaming media, JPGs, 3GPs, etc (usually from external storage, via MediaStore). 
 * See SupportedFileType enum for what is recognized as a supported file (mostly matches what Android supports). 
 * 
 * 2. TEXT requests, receives text input as a way of passing messages to the server and not expecting a 
 * response (this is NOT normal HTTP, it's just message passing TO the server, and response is only ACK). 
 * 
 * NOTE: If request starts with a ? (querystring) OR does not end in a known file extension then it is 
 * treated as TEXT request, otherwise it is treated as MEDIA.  
 *
 * MEDIA FILE REQUEST EXAMPLE: /storage/emulated/Camera/IMG_12345.jpg
 * TEXT REQUEST EXAMPLE: /sometexthere/andmorepath_ornot
 * TEXT WITH QUERYSTRING EXAMPLE: ?foo=sometext&bar=more
 *  
 * 
 * (Supports partial content, HTTP 206, for streaming.)
 * 
 * 
 * @author ccollins
 *
 */
public class HTTPServer {

   private static final String DEFAULT_USER_AGENT = "AndroidHTTPServer";
   private static final SimpleDateFormat INET_DFMT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
   static {
      INET_DFMT.setTimeZone(TimeZone.getTimeZone("GMT"));
   }

   private static final Logger LOG = LoggerFactory.getLogger(HTTPServer.class);

   private ExecutorService executor;
   private String userAgent;
   private int port;
   private int numThreads;
   
   /** 
    * Used only to inform server creator of what request input has been received (first line) for TEXT requests only.
    * It's safe to ignore this callback and use null if you don't care about external notification of text requests.
    *  
    */
   private TextRequestCallback callback;

   private boolean debug;

   /**
    * Create HTTPServer.
    * 
    * @param userAgent
    * @param port
    * @param numThreads
    * @param callback
    */
   public HTTPServer(String userAgent, int port, int numThreads, TextRequestCallback callback) {

      if (port < 1024) {
         throw new IllegalArgumentException("port must not be in reserved range (< 1024)");
      }

      if (numThreads < 0) {
         throw new IllegalArgumentException("numThreads invalid");
      }

      if (userAgent == null) {
         userAgent = DEFAULT_USER_AGENT;
      }

      this.userAgent = userAgent;
      this.port = port;
      this.numThreads = numThreads;
      
      this.callback = callback;

      LOG.info("ANDROID HTTP server created, userAgent:" + userAgent + " port:" + port + " numThreads:" + numThreads);
   }

   public void setDebug(boolean debug) {
      this.debug = debug;
   }

   // params passed to start, so we can specify them in interface
   public void start() {

      if (executor != null) {
         shutdownExecutor();
      }

      executor = Executors.newFixedThreadPool(numThreads);

      try {
         final ServerSocket serverSocket = new ServerSocket(port);

         LOG.info("ANDROID HTTP server started, addr:" + serverSocket.getInetAddress());

         // submit stuff to executor off of current thread (exec will handle each as a thread, but we don't want to block callers that just use "start" here) 
         new Thread() {
            @Override
            public void run() {
               try {
                  while (!executor.isShutdown()) {
                     executor.submit(new RequestHandler(debug, userAgent, serverSocket.accept(), callback));
                  }
               } catch (SocketException e) {
                  LOG.error("ERROR running server executor:" + e.getMessage(), e);
               } catch (IOException e) {
                  LOG.error("ERROR running server executor:" + e.getMessage(), e);
               }
            }
         }.start();

      } catch (IOException e) {
         LOG.error("ERROR creating server socket:" + e.getMessage(), e);
      }
   }

   public void stop() {
      shutdownExecutor();
      LOG.info("ANDROID HTTPD server stopped");
   }

   //
   // priv
   //

   private void shutdownExecutor() {
      executor.shutdown();
      try {
         executor.awaitTermination(5, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
         LOG.error("ERROR stopping server:" + e.getMessage(), e);
      }
      executor.shutdownNow();
   }

   //
   // internal handler class (for each socket.accept)
   //
   
   private static final String ANDROID_BUILD_MODEL = android.os.Build.MODEL;
   private static final String ANDROID_BUILD_VERSION = android.os.Build.VERSION.RELEASE;

   private static final class RequestHandler implements Runnable {

      private static final int BUFFER_SIZE = 4096; // small, yeah, we run this on phones and stuff
      
      private static final Logger LOG = LoggerFactory.getLogger(RequestHandler.class);

      private final boolean debug;
      private final String userAgent;
      private final Socket socket;
      private final TextRequestCallback callback;

      RequestHandler(final boolean debug, final String userAgent, final Socket socket, final TextRequestCallback callback) throws SocketException {
         this.debug = debug;
         this.userAgent = userAgent;
         this.socket = socket;
         this.callback = callback;
      }

      public void run() {
         long start = System.currentTimeMillis();
         try {
            LOG.debug(userAgent + " server handler start - " + start);

            // NOTE HTTP request ends at double newline (each in form of CRLF)
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            // parse lines
            List<String> lines = new ArrayList<String>();
            String line = null;
            while ((line = in.readLine()) != null) {
               if (line.trim().equals("")) {
                  break;
               }
               lines.add(line);
            }

            if (debug) {
               for (String s : lines) {
                  LOG.debug("   *** REQUEST LINE: " + s);
               }
            }

            // user first line to determine request type and handle accordingly
            String request = lines.get(0).trim();
            Matcher get = Pattern.compile("GET /?(\\S*).*").matcher(request);
            if (get.matches()) {
               request = get.group(1);

               if (request.equals("")) {
                  // if request empty, just respond server info
                  createTextResponse(userAgent + " (AndroidModel:" + ANDROID_BUILD_MODEL + " AndroidVersion:"
                           + ANDROID_BUILD_VERSION + ")", Status.OK);
               } else if (request.endsWith("/")) {
                  // if request for directory, just respond with server info (no dir index here)
                  createTextResponse(userAgent + " (AndroidModel:" + ANDROID_BUILD_MODEL + " AndroidVersion:"
                           + ANDROID_BUILD_VERSION + ")", Status.OK);
               } else {

                  request = URLDecoder.decode(request, "UTF-8");
                  
                  SupportedFileType sft = SupportedFileType.getFromString(request);
                  
                  // if queryString, just handle as text
                  if (request.startsWith("?")) {
                     handleNonFileRequestAsText(request);
                     if (debug) {
                        LOG.debug(userAgent + " received request with queryString, handling as text and returning ACK only");
                     }
                  } else if (sft != null) {
                     if (debug) {
                        LOG.debug(userAgent + " serving FILE request, SupportedFileType:" + sft);
                     }
                     handleFileRequest(request, lines);
                  } else {
                     handleNonFileRequestAsText(request);
                     if (debug) {
                        LOG.debug(userAgent + " received non file request, handling as text and returning ACK only");
                     }
                  }
               }
            } else {
               LOG.warn("client made request that was not allowed");
               // don't support anything but GET, return 405
               createTextResponse("not allowed", Status.NOT_ALLOWED);
            }

            // close socket
            socket.close();

            LOG.debug(userAgent + " server handler stop, duration:" + (System.currentTimeMillis() - start));
         } catch (IOException e) {
            LOG.error("ERROR I/O exception", e);
            createTextResponse("ERROR handling request: " + e.getMessage(), Status.ERROR);
         }
      }

      //
      // request handlers
      //   
      
      private void handleNonFileRequestAsText(String request) {
         // so that non-file requests can be used as just an external HTTP messaging system (with no meaningful response), callback is fired
         // NOTE may need to sync on this, multiple runnable/threads may get here at the same time, but only one callback (though this is fast)
         callback.onRequest(request);
         createTextResponse("ACK", Status.OK);         
      }

      private void handleFileRequest(String request, List<String> lines) {
         // make sure it's a file, and make sure we can read it
         File file = new File(request);

         if (!file.isFile()) {
            createTextResponse("resource not a file", Status.NOT_ALLOWED);
            LOG.error("resource is not a file, or is not readable");
            return;
         }

         if (!file.canRead()) {
            createTextResponse("resource not readable", Status.NOT_ALLOWED);
            LOG.error("resource is not a file, or is not readable");
            return;
         }

         if (debug) {
            LOG.debug("   file request, serving it up via path:" + file.getAbsolutePath());
         }
         try {
            createBinaryResponse(file, lines);
         } catch (Exception e) {
            LOG.error("ERROR creating response (normal if client cancels connection) e:" + e.getMessage());
         }
      }

      //
      // response handlers
      //

      private void createTextResponse(final String text, Status status) {
         try {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 " + status.getDesc() + "\r\n");
            sb.append("Server: " + userAgent + "\r\n");
            sb.append("Content-Type: text/plain; charset=utf-8\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Date:" + getDateString(new Date()) + "\r\n");
            sb.append("\r\n");
            sb.append(text);
            sb.append("\r\n\r\n");

            byte[] headerBytes = sb.toString().getBytes();
            OutputStream out = socket.getOutputStream();
            out.write(headerBytes, 0, headerBytes.length);
            out.flush();
            out.close();
         } catch (IOException e) {
            LOG.error("ERROR I/O exception", e);
         }
      }

      private void createBinaryResponse(File source, List<String> requestLines) throws Exception {

         // binary needs all the request lines to check if "range" is present

         // determine if request contains a "range" or not 
         // Support HTTP 1.1 "Partial Content" -- http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
         boolean rangePresent = false;
         boolean rangeValid = false;
         boolean rangeEndAbsent = false;
         boolean keepAliveHeaderPresent = false;

         long rangeStart = 0;
         long rangeEnd = 0;
         String rangeString = null;
         // check ALL request lines for Range
         for (String line : requestLines) {
            if (line.startsWith("Range") || line.startsWith("range")) {
               rangePresent = true;
               if (line.contains("bytes")) {
                  rangeValid = true;
                  if (line.trim().endsWith("-")) {
                     // client sent bytes=0- or bytes=X-
                     rangeEndAbsent = true;
                  }
                  rangeString = line.substring(line.indexOf("bytes=") + 6, line.length());
                  try {
                     rangeStart = Long.valueOf(rangeString.substring(0, rangeString.indexOf("-")));
                     if (!rangeString.endsWith("-")) {
                        rangeEnd =
                                 Long.valueOf(rangeString.substring(rangeString.indexOf("-") + 1, rangeString.length()));
                     }
                  } catch (NumberFormatException e) {
                     LOG.error("ERROR getting partial content range", e);
                     rangeValid = false;
                  }
               }
               break;
            }
            if (!keepAliveHeaderPresent && line.contains("Connection: keep-alive")
                     || line.contains("Connection: Keep-Alive")) {
               keepAliveHeaderPresent = true;
            }
         }

         if (rangeEndAbsent) {
            // client sent "bytes=0-"
            // http://www.w3.org/Protocols/rfc2616/rfc2616-sec14.html#sec14.35
            // "If the last-byte-pos value is absent, or if the value is greater than or equal to the current length of the entity-body, last-byte-pos is taken to be equal to one less than the current length of the entity- body in bytes."
            rangeEnd = source.length() - 1;
         }

         long rangeSize = rangeEnd - rangeStart + 1;

         if ((rangeEnd < rangeStart) || (rangeSize < 1)) {
            rangeValid = false;
         }

         if (rangePresent && rangeValid) {
            if (debug) {
               LOG.debug("      transfer standard via ranged request, range present and valid (Partial-Content)");
            }

            // HEADER           
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 206 Partial Content\r\n");
            sb.append("Server: AndroidHTTPServer\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Content-Type: " + getMimeType(source) + "\r\n");
            sb.append("Date: " + getDateString(new Date()) + "\r\n");
            sb.append("ETag: " + getETag(source) + "\r\n");
            sb.append("Content-Range: bytes " + rangeStart + "-" + rangeEnd + "/" + source.length() + "\r\n");
            sb.append("Content-Length: " + rangeSize + "\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");

            if (debug) {
               LOG.debug("      *** RESPONSE:\n" + sb.toString());
            }

            byte[] headerBytes = sb.toString().getBytes();
            OutputStream dest = socket.getOutputStream();
            dest.write(headerBytes, 0, headerBytes.length);
            dest.flush();

            // BODY           
            int iRangeStart = 0;
            int iRangeSize = 0;
            if (rangeStart < Integer.MAX_VALUE) {
               iRangeStart = (int) rangeStart;
            } else {
               throw new RuntimeException("ERROR: content rangeStart > Integer.MAX_VALUE");
            }
            if (rangeSize < Integer.MAX_VALUE) {
               iRangeSize = (int) rangeSize;
            } else {
               throw new RuntimeException("ERROR: content rangeSize > Integer.MAX_VALUE");
            }

            final int iiRangeSize = iRangeSize;
            FileInputStream fis = new FileInputStream(source) {
               public int available() throws IOException {
                  return iiRangeSize;
               }
            };
            // skip to range start in stream
            fis.skip(iRangeStart);
            if (fis != null) {
               int pending = fis.available(); // this is to support partial content
               byte[] buff = new byte[BUFFER_SIZE];
               while (pending > 0) {
                  int read = fis.read(buff, 0, ((pending > BUFFER_SIZE) ? BUFFER_SIZE : pending));
                  if (read <= 0) {
                     break;
                  }
                  dest.write(buff, 0, read);
                  pending -= read;
               }
            }
            dest.flush();
            dest.close();
            if (fis != null) {
               fis.close();
            }

            // TODO investigate NIO and memory mapped file (also keep file map ref around for next request?)
            // (look at Guava Files.map which returns MappedByteBuffer, ByteBuffer.get, etc)
            // (also potentitally keep the last served file array around, in case re-use?)            

            // old way (did not work with Samsung Player)
            /*
            byte[] serveFileBytes = new byte[iRangeSize];
            ByteStreams.read(Files.newInputStreamSupplier(source).getInput(), serveFileBytes, iRangeStart, iRangeSize);
            dest.write(serveFileBytes);
            dest.flush();
            */
         } else if (rangePresent && !rangeValid) {
            createTextResponse("range supplied is invalid", Status.RANGE_INVALID);
         } else {
            if (debug) {
               LOG.debug("      transfer standard file in one shot, range not present (200)");
            }

            // HEADER         
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 200 OK\r\n");
            sb.append("Server: AndroidHTTPServer\r\n");
            sb.append("Accept-Ranges: bytes\r\n");
            sb.append("Content-Type: " + getMimeType(source) + "\r\n");
            sb.append("Content-Length: " + source.length() + "\r\n");
            sb.append("Date: " + getDateString(new Date()) + "\r\n");
            sb.append("ETag: " + getETag(source) + "\r\n");
            sb.append("Connection: close\r\n");
            sb.append("\r\n");

            if (debug) {
               LOG.debug("      *** RESPONSE:\n" + sb.toString());
            }

            byte[] headerBytes = sb.toString().getBytes();
            OutputStream dest = socket.getOutputStream();
            dest.write(headerBytes, 0, headerBytes.length);
            dest.flush();

            // BODY
            FileInputStream fis = new FileInputStream(source);
            byte[] data = new byte[4 * BUFFER_SIZE];
            for (int read; (read = fis.read(data)) > -1;) {
               dest.write(data, 0, read);
            }
            dest.flush();
            dest.close();

            try {
               fis.close();
            } catch (IOException e) {
               LOG.error("Error closing fis", e);
            }
         }
      }

      //
      // priv helpers
      //

      private String getMimeType(File file) {
         String mimeType = URLConnection.guessContentTypeFromName(file.getName());
         // change borked "m4v" file extension to mp4 mime - what's up with this?
         if (mimeType != null && mimeType.endsWith("m4v")) {
            mimeType = "video/mp4";
         }
         return mimeType;
      }

      private String getDateString(Date date) {
         synchronized (INET_DFMT) {
            return INET_DFMT.format(date);
         }
      }

      private String getETag(File f) {
         return Integer.toHexString((f.getAbsolutePath() + f.lastModified() + "" + f.length()).hashCode());
      }
   }
}
