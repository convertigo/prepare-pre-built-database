# PreparePreBuiltDatabase
Tool to create Pre built FullSync databases

This tool will prepare a prebuilt mobile fullsync database you will be able to embed in your mobile apps 
Or bulk download when you mobile application is started.

Usage: PreparePreBuiltDatabase Convertigo_server_endpoint fullsync_database_name ex :
   PreparePreBuiltDatabase http://my.convertigo.server.com:28080/convertigo  myfullsyncdatabase *[prebuiltdatabase.zip]*

The prebuilt database will be created in the current directory.

You can make a transaction on your Convertigo fullsync connector to generate the zip file (and schedule it if you want). Create a new CustomTransaction **PreparePreBuiltDatabase**, double-click on it and past this code :

```java
function onTransactionStarted() {
	var database = context.connectorName;
    context.addTextNodeUnderRoot("database", database);
    
    // customize this line for custom jar path
    var jar = new java.io.File(com.twinsoft.convertigo.engine.Engine.USER_WORKSPACE_PATH + "/PreparePreBuiltDatabase.jar");
    context.addTextNodeUnderRoot("jarPath", jar.getAbsolutePath());
    context.addTextNodeUnderRoot("jarExists", "" + jar.exists());
    
    var requestUrl = "" + context.httpServletRequest.getRequestURL();
    context.addTextNodeUnderRoot("requestUrl", requestUrl);
    
    var convertigoUrl = requestUrl.replace(new RegExp("(.*?)/projects/.*"), "$1");
    context.addTextNodeUnderRoot("convertigoUrl", convertigoUrl);
    
    var privateDir = new java.io.File(context.getProjectDirectory() + "/_private");
    context.addTextNodeUnderRoot("privateDir", privateDir.getAbsolutePath()); 
    
    var zipFile = new java.io.File(context.getProjectDirectory() + "/" + database + ".zip");
    context.addTextNodeUnderRoot("zipPath", zipFile.getAbsolutePath());
    
    var javaExe = new java.io.File(java.lang.System.getProperty("java.home") + "/bin/java");
    if (!javaExe.exists()) {
    	javaExe = new java.io.File(java.lang.System.getProperty("java.home") + "/bin/java.exe");
    }
    
    context.addTextNodeUnderRoot("javaExe", javaExe.getAbsolutePath());
    
    var process = new java.lang.ProcessBuilder(javaExe.getAbsolutePath(), "-jar", jar.getAbsolutePath(), convertigoUrl, database, zipFile.getAbsolutePath())
    	.directory(privateDir)
    	.redirectErrorStream(true)
    	.start();
    var output = org.apache.commons.io.IOUtils.toString(process.getInputStream());
    context.addTextNodeUnderRoot("output", output);
    
    var result = process.waitFor();
    context.addTextNodeUnderRoot("status", "" + result);
    
    return "cancel";
}
```