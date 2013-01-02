package com.totsp.server.util;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Scanner;

public final class SimpleHttpClient {

   // stupid simple client, just does get, and just returns response as a string
   
   private SimpleHttpClient() {
   }

   public static String get(String urlString) {
      String response = null;
      URL url = null;
      try {
         url = new URL(urlString);
      } catch (MalformedURLException e) {
         e.printStackTrace();
      }

      InputStream is = null;
      try {
         HttpURLConnection connection = (HttpURLConnection) url.openConnection();
         connection.setConnectTimeout(10000);
         int code = connection.getResponseCode();
         if (code >= 200 && code <= 400) {
            is = connection.getInputStream();
         } else {
            is = connection.getErrorStream();
         }
         response = streamToString(is);
      } catch (IOException e) {
         e.printStackTrace();
      } finally {
         if (is != null) {
            try {
               is.close();
            } catch (IOException e) {
               // gulp
            }
         }
      }
      return response;
   }

   private static String streamToString(InputStream is) {
      java.util.Scanner s = new Scanner(is, "UTF-8").useDelimiter("\\A");
      if (s.hasNext()) {
         return s.next().trim();
      }
      return null;
   }
}
