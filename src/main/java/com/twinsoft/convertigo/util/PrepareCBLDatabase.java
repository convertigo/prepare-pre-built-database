package com.twinsoft.convertigo.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Stack;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.JavaContext;
import com.couchbase.lite.Manager;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.replicator.Replication.ReplicationStatus;

public class PrepareCBLDatabase {

	Manager 		manager;
	Database 		db;
	static String	database_name = "manydocs";		
	
	public static void main(String[] args) throws IOException, CouchbaseLiteException {
		List<String> argsList  = new ArrayList<String>();  
	    List<String> optsList  = new ArrayList<String>();
	    List<String> doubleOptsList  = new ArrayList<String>();

    	System.out.println("PreparePreBuiltDatabase tool v1.0 (c) 2017 Convertigo");
	    
	    if (args.length != 0) {
	    	
	    	/*
	    	 * Not used for the moment....
	    	 */
		    for (int i=0; i < args.length; i++) {
		         switch (args[i].charAt(0)) {
			         case '-':
			             if (args[i].charAt(1) == '-') {
			                 int len = 0;
			                 String argstring = args[i].toString();
			                 len = argstring.length();
			                 System.out.println("Found double dash with command " + argstring.substring(2, len) );
			                 doubleOptsList.add(argstring.substring(2, len));           
			             } else {
			                 System.out.println("Found dash with command " + args[i].charAt(1) + " and value " + args[i+1] );   
			                 i= i+1;
			                 optsList.add(args[i]);      
			             }           
			         break;         
		         default:            
			         argsList.add(args[i]);
			         break;         
		         }     
		    }
			PrepareCBLDatabase me = new PrepareCBLDatabase();
			database_name = args[1];
			me.openDatabase();
			me.replicate(args[0]);
	    } else {
	    	System.out.println("This tool will prepare a prebuilt mobile fullsync database you will be able to embded in your mobile apps ");
	    	System.out.println("Or bulk donwnload when you mobile application is started.");
	    	System.out.println("");
	        System.out.println("Usage: PreparePreBuiltDatabase Convertigo_server_endpoint fullsync_database_name ex :");
	        System.out.println("       PreparePreBuiltDatabase http://my.convertigo.server.com:18080/convertigo  myfullsyncdatabase");
	    	System.out.println("");
	    	System.out.println("The prebuilt database will be created in the current directory.");
	    }
	}

	/**
	 * Just open the database that will receive our pre-built data...
	 * 
	 * @throws IOException
	 * @throws CouchbaseLiteException
	 */
	void openDatabase() throws IOException, CouchbaseLiteException {
		JavaContext context = new JavaContext();
		manager = new Manager(context, Manager.DEFAULT_OPTIONS);
		manager.setStorageType("SQLite");
		System.out.println("Database technology used : ");
		db = manager.getDatabase(database_name + "_device");
	}
	
	/**
	 * Replicate the data base from the server..
	 * 
	 * @param surl
	 * @throws MalformedURLException
	 */
	void replicate(String surl) throws MalformedURLException {
		URL url = new URL(surl + "/fullsync/" + database_name +"/");
		Replication pull = db.createPullReplication(url);
		pull.setContinuous(false);
		
		/*
		Authenticator  auth = new BasicAuthenticator("","");
		pull.setAuthenticator(auth);
		*/
		
		pull.addChangeListener(new Replication.ChangeListener() {
		    @Override
		    public void changed(Replication.ChangeEvent event) {
		        // will be called back when the pull replication status changes
		    	System.out.print("Replicated : " + event.getCompletedChangeCount() + " Status : " + event.getStatus() + "\r");
		    	if (event.getStatus() ==  ReplicationStatus.REPLICATION_STOPPED) {
		    		System.out.print("\n");
		    		System.out.print("Zipping database ...");
		    		zipDir(Paths.get("./data/data/com.couchbase.lite.test/files/cblite/" + database_name + "_device.cblite2"),
		    			   Paths.get("./" + database_name + "_device.cblite2.zip"));
		    		System.out.println(", Database zip has been created in : " + database_name + "_device.cblite2.zip");
		    		System.out.println("");
		    		System.out.println("Copy this file to a repository accessed by an HTTP server, For example copy the file in a convertigo projet and");
		    		System.out.println("Deploy the project on a Convertigo server.");
		    	}
		    }
		});
		pull.start();
	}
	
	/**
	 * Zip the database directory ton one Zip File
	 * 
	 * @param dirToZip
	 * @param out
	 */
	public static void zipDir(final Path dirToZip, final Path out) {
	    final Stack<String> stackOfDirs = new Stack<>();
	    final Function<Stack<String>, String> createPath = stack -> stack.stream().collect(Collectors.joining("/")) + "/";
	    try(final ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
	        Files.walkFileTree(dirToZip, new FileVisitor<Path>() {

	            @Override
	            public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
	                stackOfDirs.push(dir.toFile().getName());
	                final String path = createPath.apply(stackOfDirs);
	                final ZipEntry zipEntry = new ZipEntry(path);
	                zipOut.putNextEntry(zipEntry);
	                return FileVisitResult.CONTINUE;
	            }

	            @Override
	            public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
	                final String path = String.format("%s%s", createPath.apply(stackOfDirs), file.toFile().getName());
	                final ZipEntry zipEntry = new ZipEntry(path);
	                zipOut.putNextEntry(zipEntry);
	                Files.copy(file, zipOut);
	                zipOut.closeEntry();
	                return FileVisitResult.CONTINUE;
	            }

	            @Override
	            public FileVisitResult visitFileFailed(final Path file, final IOException exc) throws IOException {
	                final StringWriter stringWriter = new StringWriter();
	                try(final PrintWriter printWriter = new PrintWriter(stringWriter)) {
	                    exc.printStackTrace(printWriter);
	                    System.err.printf("Failed visiting %s because of:\n %s\n",
	                            file.toFile().getAbsolutePath(), printWriter.toString());
	                }
	                return FileVisitResult.CONTINUE;
	            }

	            @Override
	            public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) throws IOException {
	                stackOfDirs.pop();
	                return FileVisitResult.CONTINUE;
	            }
	        });
	    } catch (IOException e) {
	        e.printStackTrace();
	    }
	}

}
