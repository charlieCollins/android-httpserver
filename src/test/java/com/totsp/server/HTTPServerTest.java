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
   private static final HTTPServer SERVER = new HTTPServer("test-server", 8123, 1);
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
}
