package com.twinsoft.convertigo.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import com.couchbase.lite.CouchbaseLiteException;
import com.couchbase.lite.Database;
import com.couchbase.lite.JavaContext;
import com.couchbase.lite.Manager;
import com.couchbase.lite.Mapper;
import com.couchbase.lite.QueryOptions;
import com.couchbase.lite.QueryRow;
import com.couchbase.lite.Reducer;
import com.couchbase.lite.View;
import com.couchbase.lite.javascript.JavaScriptViewCompiler;
import com.couchbase.lite.replicator.Replication;
import com.couchbase.lite.replicator.Replication.ReplicationStatus;
import com.couchbase.lite.store.SQLiteStore;

public class PrepareCBLDatabase {

	Manager manager;
	Database db;
	boolean compileViews = false;
	
	static String database_name = "manydocs";

	public static void main(String[] args) throws IOException, CouchbaseLiteException {

		System.out.println("PreparePreBuiltDatabase tool v1.2 (c) 2017 Convertigo");
		
		boolean compileViews = true;
		boolean help = false;
		int id = 0;
		
		for (id = 0; id < args.length; id++) {
			String arg = args[id];
			
			if (arg.equals("--help") || arg.equals("-h")) {
				help = true;
			} else if (arg.equals("--no-compile-views") || arg.equals("-ncv")) {
				compileViews = false;
			} else {
				break;
			}
		}

		if (!help && args.length >= 2 + id) {
			View.setCompiler(new JavaScriptViewCompiler());
			PrepareCBLDatabase me = new PrepareCBLDatabase();
			database_name = args[id + 1];
			me.compileViews = compileViews;
			me.openDatabase();
			Path zipPath = Paths.get(args.length >= (id + 3) ? args[id + 2] : "./fs." + database_name + ".zip");
			me.replicate(args[id + 0], zipPath);
		} else {
			System.out.println("This tool will prepare a prebuilt mobile fullsync database you will be able to embed in your mobile apps ");
			System.out.println("Or bulk download when you mobile application is started.");
			System.out.println("");
			System.out.println("Usage: PreparePreBuiltDatabase Convertigo_server_endpoint fullsync_database_name ex :");
			System.out.println("   PreparePreBuiltDatabase [options] http://my.convertigo.server.com:28080/convertigo  myfullsyncdatabase [prebuiltdatabase.zip]");
			System.out.println("");
			System.out.println("Options are:");
			System.out.println("  -h   --help             : shows this message");
			System.out.println("  -ncv --no-compile-views : disables pre-indexing of all views");
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
		db = manager.getDatabase(database_name);
	}

	/**
	 * Replicate the data base from the server..
	 * 
	 * @param surl
	 * @throws MalformedURLException
	 */
	void replicate(String surl, Path zipPath) throws MalformedURLException {
		URL url = new URL(surl + "/fullsync/" + database_name +"/");
		Replication pull = db.createPullReplication(url);
		pull.setContinuous(false);
		
		final long time[] = {0};
		/*
		Authenticator  auth = new BasicAuthenticator("","");
		pull.setAuthenticator(auth);
		 */

		pull.addChangeListener(new Replication.ChangeListener() {
			@Override
			public void changed(Replication.ChangeEvent event) {
				// will be called back when the pull replication status changes
				long now = System.currentTimeMillis();
				if (now > time[0]) {
					System.out.println("Replicated : " + event.getCompletedChangeCount() + " Status : " + event.getStatus());
					time[0] = now + 5000;
				}
				if (event.getStatus() ==  ReplicationStatus.REPLICATION_STOPPED) {
					try {						
						Path cbldir = Paths.get("./data/data/com.couchbase.lite.test/files/cblite/" + database_name + ".cblite2").toAbsolutePath();
						SQLiteStore store = new SQLiteStore(cbldir.toString(), manager, db);
						store.open();
						String rev = store.getInfo("checkpoint/" + pull.remoteCheckpointDocID());
						store.setInfo("prebuiltrevision", rev);
						store.close();
						
						if (compileViews) {
							doCompileViews();
						}
						
						System.out.print("\nZipping database ...");
						zipDir(cbldir, zipPath);
						System.out.println(", Database zip has been created in : " + zipPath);
						System.out.println("");
						System.out.println("Copy this file to a repository accessed by an HTTP server, For example copy the file in a convertigo projet and");
						System.out.println("Deploy the project on a Convertigo server.");
					} catch (Exception e) {
						System.err.println("Failed to replicate and prepare the zip file.");
						e.printStackTrace();
					}
				}
			}
		});
		pull.start();
	}
	
	@SuppressWarnings("unchecked")
	void doCompileViews() throws CouchbaseLiteException, InterruptedException {
		QueryOptions qo = new QueryOptions();
		qo.setStartKey("_design/");
		Map<String, Object> docs = db.getAllDocs(qo);
		
		if (docs.containsKey("rows")) {
			List<QueryRow> rows = (List<QueryRow>) docs.get("rows");
			for (QueryRow row : rows) {
				String ddoc = row.getDocumentId().substring("_design/".length());
				Map<String, Map<String, Object>> views = (Map<String, Map<String, Object>>) db.getExistingDocument(row.getDocumentId()).getProperties().get("views");
				if (views != null) {
					for (Entry<String, Map<String, Object>> view : views.entrySet()) {
						String tdViewName = ddoc + "/" + view.getKey();
						System.out.print("Compile View : " + tdViewName + "\r");
						compileView(tdViewName, view.getValue()).updateIndexAlone();
					}
				}
			}
		}
	}
    
    View compileView(String viewName, Map<String, Object> viewProps) {
        String language = (String) viewProps.get("language");
        if (language == null) {
            language = "javascript";
        }

        String mapSource = (String) viewProps.get("map");
        if (mapSource == null) {
            return null;
        }

        Mapper mapBlock = View.getCompiler().compileMap(mapSource, language);
        if (mapBlock == null) {
            return null;
        }

        String mapID = viewName + ":" + mapSource.hashCode();

        String reduceSource = (String) viewProps.get("reduce");
        Reducer reduceBlock = null;
        if (reduceSource != null) {
            reduceBlock = View.getCompiler().compileReduce(reduceSource, language);
            if (reduceBlock == null) {
                return null;
            }
            mapID += ":" + reduceSource.hashCode();
        }

        View view = db.getView(viewName);
        view.setMapReduce(mapBlock, reduceBlock, mapID);
        String collation = (String) viewProps.get("collation");
        if ("raw".equals(collation)) {
            view.setCollation(View.TDViewCollation.TDViewCollationRaw);
        }
        return view;
    }
    
	/**
	 * Zip the database directory ton one Zip File
	 * 
	 * @param dirToZip
	 * @param out
	 */
	public static void zipDir(final Path dirToZip, final Path out) {
		try (final ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
			Files.walkFileTree(dirToZip, new SimpleFileVisitor<Path>() {

				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
					zipOut.putNextEntry(new ZipEntry(dirToZip.relativize(dir).toString() + "/"));
					zipOut.closeEntry();
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
					zipOut.putNextEntry(new ZipEntry(dirToZip.relativize(file).toString()));
					Files.copy(file, zipOut);
					zipOut.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

}
