package com.totsp.server;

import com.totsp.server.HTTPServer;
import com.totsp.server.util.SimpleHttpClient;

import org.junit.AfterClass;
import org.junit.Assert;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;

public class HTTPServerTest {

   // NOTE: these are really integration-ish tests, but they serve required purpose of simple-sanity-checking HTTP server

   // static server instance so we can use AfterClass
   private static final TextRequestCallback CALLBACK = new TextRequestCallback() {
      @Override
      public void onRequest(String request) {
         System.out.println("CALLBACK got request:" + request);
      }      
   };
   private static final HTTPServer SERVER = new HTTPServer("test-server", 8123, 1, CALLBACK);
   static {
      SERVER.start();
      SERVER.setDebug(true);
   }

   private String serverUrl;

   public HTTPServerTest() {
      InetAddress addr = null;
      try {
         addr = InetAddress.getLocalHost();
      } catch (IOException e) {
         Assert.fail(e.getMessage());
      }
      this.serverUrl = "http://" + addr.getHostAddress() + ":" + 8123;
   }

   @AfterClass
   public static void tearDown() {
      SERVER.stop();
   }

   @Test
   public void testRequestServerRoot() throws Exception {
      String response = SimpleHttpClient.get(serverUrl);
      Assert.assertEquals("test-server (AndroidModel:null AndroidVersion:null)", response);
   }

   @Test
   public void testRequestDirectoryListing() throws Exception {
      String response = SimpleHttpClient.get(serverUrl + "/");
      Assert.assertEquals("test-server (AndroidModel:null AndroidVersion:null)", response);
   }

   @Test
   public void testFileNotFound() throws Exception {
      String response = SimpleHttpClient.get(serverUrl + "/file.jpg");
      Assert.assertEquals("resource not a file", response);
   }
   
   @Test
   public void testTextFile() throws Exception {      
      File testFile = new File("src/test/resources/test.txt");
      Assert.assertTrue(testFile.exists());
      String response = SimpleHttpClient.get(serverUrl + "/" + testFile.getAbsolutePath());
      Assert.assertEquals("say what you want about the tenets of national socialism, dude, at least it's an ethos", response);
   }
   
   @Test
   public void testJpgFile() throws Exception {      
      File testFile = new File("src/test/resources/test.jpg");
      Assert.assertTrue(testFile.exists());
      String response = SimpleHttpClient.get(serverUrl + "/" + testFile.getAbsolutePath());
      Assert.assertEquals(544538, response.length());
   } 
   
   @Test
   public void testTextAndCallback() throws Exception {
      String response = SimpleHttpClient.get(serverUrl + "/DISPLAY_MEDIA~foobar");
      Assert.assertEquals("ACK", response);
   }
   
   //
   @Test
   public void testTextWithQueryStringAndCallback() throws Exception {
      String exampleQueryString = "?DISPLAY_MEDIA=http%3A%2F%2F192.168.0.142%3A8999%2Fstorage%2Femulated%2F0%2FDCIM%2FCamera%2FIMG_20121227_163753.jpg";
      String response = SimpleHttpClient.get(serverUrl + exampleQueryString);
      Assert.assertEquals("ACK", response);
   }
}
