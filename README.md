Android HTTP Server
====================

Overview
--------
HTTP server that can run on an Android device and serve content (content from said device).

Also contains a Service wrapper that can be bound to start/stop/control the server. 

(At the time this was written I looked at many small Java HTTP servers but none quite fit the bill, 
either due to size and dependencies, or just craziness with usage, or bugs/errors on Android, hence the existence of this.) 

Created as a plain Java project (with Android jar for stubs) so that plain JVM dev/testing
can be done on non-androidy parts.  


Logging
--------
Uses SLF4J.      
Plugin the correct logging implementation library at runtime to control logging.      
The tests use the SLF4J "Simple" logger, which can be configured with system properties:      
http://www.slf4j.org/api/org/slf4j/impl/SimpleLogger.html      

If you use the Java system property ``-Dorg.slf4j.simpleLogger.defaultLogLevel=debug``, you will see debug logging, etc.

(NOTE: slf4j works fine on Android, just include slf4j-android -- auto creates log tag.) 


Build
-----
Uses Maven.    
```mvn eclipse:eclipse```      
```mvn clean install```   
etc   


Use on Android
---------------
Build it, include the JAR in libs (and include the slf4j android jar, see logging).


TODO
----
Android tests.   
Protocol pass in.   
Enhancements to server (see TODOs). 
 
