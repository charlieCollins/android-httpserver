package com.totsp.server;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import com.totsp.server.internal.HTTPDServerImpl;

public class HTTPServerService extends Service {

   public static final int PORT = 8999;

   private IHTTPDServer server;
   
   // Binder given to clients
   private final IBinder binder = new LocalBinder();

   /**
    * Class used for the client Binder.  Because we know this service always
    * runs in the same process as its clients, we don't need to deal with IPC.
    */
   public class LocalBinder extends Binder {
      HTTPServerService getService() {
         // Return this instance of HTTPServerService so clients can call public methods
         return HTTPServerService.this;
      }
   }

   @Override
   public void onCreate() {
      super.onCreate();      
      // run off of main/UI Thread (service uses same thread as other components by default)
      // TODO rejoin thread in destroy?
      new Thread() {
         @Override
         public void run() {
            server = new HTTPDServerImpl();
            try {
               server.start(PORT);
               Log.i(Constants.LOG_TAG, "HTTP SERVER STARTED, LISTENING ON PORT:" + PORT);
            } catch (Exception e) {
               Log.e(Constants.LOG_TAG, "ERROR can't start HTTP server", e);
            }
         }
      }.start();
   }

   @Override
   public void onDestroy() {
      super.onDestroy();
      try {
         server.stop();
         Log.i(Constants.LOG_TAG, "HTTP SERVER STOPPED");
      } catch (Exception e) {
         Log.e(Constants.LOG_TAG, "ERROR can't stop HTTP server", e);
      }
   }

   @Override
   public IBinder onBind(Intent intent) {
      Log.d(Constants.LOG_TAG, "HTTPServerService BOUND");
      return binder;
   }

   @Override
   public boolean onUnbind(Intent intent) {
      Log.d(Constants.LOG_TAG, "HTTPServerService UN-BOUND");
      return super.onUnbind(intent);
   }
}

   