package com.totsp.server.internal;

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

import android.util.Log;

import com.totsp.server.Constants;
import com.totsp.server.IHTTPDServer;

/**
 * HTTPD server for serving JPG and MP4/3GP files from Android (only handles GET, and only allows files of certain types).
 * Primitive, but works, and supports Partial Content (HTTP 206) for use with some TV SDKs.
 * 
 * Should be started OFF OF the main/UI thread (obviously).
 * 
 * @author charliecollins
 *
 */
public class HTTPDServerImpl implements IHTTPDServer {

   private static final String ANDROID_BUILD_MODEL = android.os.Build.MODEL;
   private static final String ANDROID_BUILD_VERSION = android.os.Build.VERSION.RELEASE;

   ///private static final List<String> SUPPORTED_FILE_EXTENSIONS = Lists.newArrayList("jpg", "jpeg", "mp4", "m4v", "3gp");

   private static final SimpleDateFormat DFMT = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z");
   static {
      DFMT.setTimeZone(TimeZone.getTimeZone("GMT"));
   }

   private static final int BUFFER_SIZE = 4096;
   
   private final ExecutorService executor;

   public HTTPDServerImpl() {
      // adj thread pool?
      executor = Executors.newFixedThreadPool(3);
      Log.i(Constants.LOG_TAG, "ANDROID HTTPD SERVER INSTANTIATED");
   }

   public void start(int port) {
      try {
         final ServerSocket serverSocket = new ServerSocket(port);
         while (!executor.isShutdown()) {
            executor.submit(new RequestResponse(serverSocket.accept()));
         }
      } catch (IOException e) {
         Log.e(Constants.LOG_TAG, "ERROR starting server:" + e.getMessage(), e);
      }
   }

   public void stop() {
      executor.shutdown();
      try {
         executor.awaitTermination(10, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
         Log.e(Constants.LOG_TAG, "ERROR stopping server:" + e.getMessage(), e);
      }
      executor.shutdownNow();
   }

   static class RequestResponse implements Runnable {
      private final Socket socket;

      RequestResponse(final Socket socket) throws SocketException {
         this.socket = socket;
      }

      public void run() {
         try {
            Log.i(Constants.LOG_TAG, "RequestResponse RUN start - " + System.currentTimeMillis());

            // NOTE HTTP request ends at double newline (each in form of CRLF)
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));

            List<String> lines = new ArrayList<String>();
            String line = null;
            while ((line = in.readLine()) != null) {
               if (line.trim().equals("")) {
                  break;
               }
               lines.add(line);
            }

            /*
            for (String s : lines) {
               Log.d(Constants.LOG_TAG, "   *** REQUEST LINE: " + s);
            }
            */

            String request = lines.get(0); // first line

            Matcher get = Pattern.compile("GET /?(\\S*).*").matcher(request);
            if (get.matches()) {
               ///Log.d(Constants.LOG_TAG, "   HANDLE GET");

               request = get.group(1);
               if (request.endsWith("/")) {
                  ///Log.d(Constants.LOG_TAG, "   REQUEST FOR PATH/DIR (just respond with server info)");
                  createTextResponse("AndroidHTTPServer (AndroidModel:" + ANDROID_BUILD_MODEL + " AndroidVersion:"
                           + ANDROID_BUILD_VERSION + ")", Status.OK);
               } else {
                  // make sure it's a file, and make sure we can read it
                  if (request.contains("+")) {
                     request = request.replace("+", " ");
                  }
                  File file = new File(request);
                  ///Log.d(Constants.LOG_TAG, "   file: " + file.getCanonicalPath());
                  
                  // TODO validate supported file type
                  
                  if (file.isFile() && file.canRead()) {
                     Log.i(Constants.LOG_TAG,
                              "   file is present/readable and allowed, serving it up via path:"
                                       + file.getAbsolutePath());
                     try {
                        createBinaryResponse(file, lines, socket.getOutputStream());
                     } catch (Exception e) {
                        Log.e(Constants.LOG_TAG, "ERROR with transferStdIo (fine if client cancels connection) e:" + e.getMessage());
                        // NOTE if the CLIENT cancels the connection, this error may occur, not fatal
                     }
                  } else {
                     Log.e(Constants.LOG_TAG, "resource cannot be read, or type is not allowed, will not be served");
                     createTextResponse("resource not allowed", Status.FORBIDDEN);
                  }
               }
            } else {
               Log.w(Constants.LOG_TAG, "client made request that was not allowed");
               // don't support anything but GET, return 405
               createTextResponse("not allowed", Status.NOT_ALLOWED);
            }

            // closing socket is fine, even if keep-alive, serversocket will maintain?
            ///Log.d(Constants.LOG_TAG, "   *** closing socket");
            socket.close();

            Log.i(Constants.LOG_TAG, "RequestResponse RUN stop");
         } catch (IOException e) {
            Log.e(Constants.LOG_TAG, "ERROR I/O exception", e);
            createTextResponse("ERROR handling request: " + e.getMessage(), Status.ERROR);
         }
      }      

      private void createTextResponse(final String text, Status status) {
         ///Log.d(Constants.LOG_TAG, "    createTextResponse text:" + text + " status:" + status);
         try {
            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 " + status.getDesc() + "\r\n");
            sb.append("Server: AndroidHTTPServer\r\n");
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
            Log.e(Constants.LOG_TAG, "ERROR I/O exception", e);
         }
      }

      private void createBinaryResponse(File source, List<String> requestLines, OutputStream dest) throws Exception {
         ///Log.d(Constants.LOG_TAG, "    createBinaryResponse");
         long start = System.currentTimeMillis();

         // determine if request contains a "range" or not 
         // Support HTTP 1.1 "Partial Content" -- http://www.w3.org/Protocols/rfc2616/rfc2616-sec10.html#sec10.2.7
         boolean rangePresent = false;
         boolean rangeValid = false;
         boolean rangeEndAbsent = false;
         boolean keepAliveHeaderPresent = false;

         long rangeStart = 0;
         long rangeEnd = 0;
         String rangeString = null;
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
                     Log.e(Constants.LOG_TAG, "ERROR getting partial content range", e);
                     rangeValid = false;
                  }
               }
            }
            if (!keepAliveHeaderPresent && line.contains("Connection: keep-alive")
                     || line.contains("Connection: Keep-Alive")) {
               ///Log.d(Constants.LOG_TAG, "      keep alive header present:" + line);
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

         ///Log.d(Constants.LOG_TAG, "      rangePresent:" + rangePresent);
         ///Log.d(Constants.LOG_TAG, "      rangeValid:" + rangeValid);
         ///Log.d(Constants.LOG_TAG, "      rangeEndAbsent:" + rangeEndAbsent);
         ///Log.d(Constants.LOG_TAG, "      rangeString:" + rangeString);
         ///Log.d(Constants.LOG_TAG, "      rangeStart:" + rangeStart);
         ///Log.d(Constants.LOG_TAG, "      rangeEnd:" + rangeEnd);
         ///Log.d(Constants.LOG_TAG, "      rangeSize:" + rangeSize);

         if (rangePresent && rangeValid) {
            ///Log.d(Constants.LOG_TAG, "      rangePresent and rangeValid -- transfer file as partial content using range (206)");

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
            Log.d(Constants.LOG_TAG, "      *** RESPONSE:\n" + sb.toString());

            byte[] headerBytes = sb.toString().getBytes();
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
            ///Log.d(Constants.LOG_TAG, "      inform client range is invalid (416)");
            createTextResponse("range supplied is invalid", Status.RANGE_INVALID);
         } else {
            ///Log.d(Constants.LOG_TAG, "      transfer standard file in one shot, range not present (200)");

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
            Log.d(Constants.LOG_TAG, "      *** RESPONSE:\n" + sb.toString());
            
            byte[] headerBytes = sb.toString().getBytes();
            dest.write(headerBytes, 0, headerBytes.length);
            dest.flush();

            // BODY
            FileInputStream fis = new FileInputStream(source);
            byte[] data = new byte[4 * BUFFER_SIZE];
            for (int read; (read = fis.read(data)) > -1;) {
               dest.write(data, 0, read);
            }

            dest.flush();

            // NOTE JavaDoc "Closing the returned OutputStream will close the associated socket"         
            dest.close();
         }

         Log.i(Constants.LOG_TAG, "      duration:" + (System.currentTimeMillis() - start));
      }
      
      private String getMimeType(File file) {
         String mimeType = URLConnection.guessContentTypeFromName(file.getName());
         // change borked "m4v" file extension to mp4 mime
         if (mimeType != null && mimeType.endsWith("m4v")) {
            mimeType = "video/mp4";
         }
         ///Log.i(Constants.LOG_TAG, "      mimeType:" + mimeType);
         return mimeType;
      }

      private String getDateString(Date date) {
         synchronized (DFMT) {
            return DFMT.format(date);
         }
      }

      private String getETag(File f) {
         return Integer.toHexString((f.getAbsolutePath() + f.lastModified() + "" + f.length()).hashCode());
      }
   }

   private enum Status {
      OK("200 OK"), PARTIAL_OK("216 Partial Content"), NOT_FOUND("404 Not Found"),
      NOT_ALLOWED("405 Method Not Allowed"), FORBIDDEN("403 Forbidden"), RANGE_INVALID(
               "416 Requested Range Not Satisfiable"), ERROR("500 Internal Server Error"), NOT_IMPL(
               "501 Not Implemented");

      private String desc;

      private Status(String desc) {
         this.desc = desc;
      }

      public String getDesc() {
         return this.desc;
      }
   }
}
