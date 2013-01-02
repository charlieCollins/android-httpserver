package com.totsp.server;

/**
 * Callback interface used to let HTTPServer users know when request text is recieved,
 * so that said text can be used as mechanism to kick off some other action on the server 
 * (using the server HTTP socket to just send a message to the server, with no value in the response, just ACK).
 * 
 * @author ccollins
 *
 */
public interface TextRequestCallback {
   
   void onRequest(String request);

}
