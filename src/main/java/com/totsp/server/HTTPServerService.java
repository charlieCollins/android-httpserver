package com.totsp.server;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;


/**
 * Android components instantiate and control the HTTP server via binding to this service.
 * 
 * @author ccollins
 *
 */
public class HTTPServerService extends Service {

   // example binding
   /*
    boolean httpServerServiceBound = false;
    HTTPServerService httpServerService = null;
    ServiceConnection httpServerServiceConnection = new ServiceConnection() {
         @Override
         public void onServiceConnected(ComponentName className, IBinder binder) {            
            HTTPServerServiceLocalBinder httpdServiceLocalBinder = (HTTPServerServiceLocalBinder) binder;
            httpServerService = httpdServiceLocalBinder.getService();             
            httpServerServiceBound = true;
            Log.i(App.TAG, "service connected");
         }

         @Override
         public void onServiceDisconnected(ComponentName comp) {
            httpServerServiceBound = false;
            httpServerService = null;
            Log.i(App.TAG, "service disconnected");
         }
      };      
      bindService(new Intent(this, HTTPServerService.class), httpServerServiceConnection,
               Context.BIND_AUTO_CREATE);    
    */

   public static final int DEFAULT_PORT = 8999;
   public static final int DEFAULT_NUM_THREADS = 3; // just used on local LAN and between few devices
   private static final String TAG = "AndroidHTTPServerService";

   private HTTPServer server;
   private boolean started;

   // expose binder and allow callers to configure and interact with server via binder
   private final IBinder binder = new HTTPServerServiceLocalBinder();

   public class HTTPServerServiceLocalBinder extends Binder {
      public HTTPServerService getService() {
         return HTTPServerService.this;
      }
   }

   //
   // lifecycle
   //

   // on start command is NOT part of lifecycle if only binding (though can bind and start)
   @Override
   public int onStartCommand(Intent intent, int flags, int startId) {
      return super.onStartCommand(intent, flags, startId);
   }

   @Override
   public void onCreate() {
      super.onCreate();
   }

   @Override
   public void onDestroy() {
      super.onDestroy();
      try {
         server.stop();
         Log.i(TAG, "HTTP SERVER STOPPED");
      } catch (Exception e) {
         Log.e(TAG, "ERROR can't stop HTTP server", e);
      }
   }

   @Override
   public IBinder onBind(Intent intent) {
      Log.d(TAG, "HTTPServerService BOUND");
      return binder;
   }

   @Override
   public boolean onUnbind(Intent intent) {
      Log.d(TAG, "HTTPServerService UN-BOUND");
      return super.onUnbind(intent);
   }

   //
   // exposed service methods
   //

   public void startServer(final String userAgent, final short port, final byte numThreads) {

      if (started) {
         throw new IllegalStateException("Error, server is already started");
      }

      server = new HTTPServer(userAgent, port, numThreads);
      server.start();
      started = true;
   }

   public void stopServer() {
      if (started) {
         try {
            server.stop();
            server = null;
            started = false;
            Log.i(TAG, "HTTPServiceService stopped server");
         } catch (Exception e) {
            Log.e(TAG, "ERROR can't stop HTTP server", e);
         }
      }
   }

   public void setDebug(boolean debug) {
      if (started) {
         server.setDebug(debug);
      }
   }

   // TODO allow callers to pass in protocol for handling text?
   /*
   public void setProtocol(IHTTPProtocol protocol) {      
   }
   */
}
