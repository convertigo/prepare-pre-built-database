package com.twinsoft.convertigo.util;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import javax.script.ScriptException;

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
import com.couchbase.lite.support.ClearableCookieJar;
import com.couchbase.lite.support.CouchbaseLiteHttpClientFactory;
import com.couchbase.lite.util.Base64;
import com.couchbase.lite.util.Log;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

public class PrepareCBLDatabase {

	Manager manager;
	Database db;
	boolean compileViews = false;
	OkHttpClient client;

	static String database_name = "manydocs";

	public static void main(String[] args) throws Exception {
		System.out.println("PreparePreBuiltDatabase tool v1.3 (c) 2018 Convertigo");

		boolean compileViews = true;
		boolean help = false;
		String token = null;
		int id = 0;

		for (id = 0; id < args.length; id++) {
			String arg = args[id];

			if (arg.equals("--help") || arg.equals("-h")) {
				help = true;
			} else if (arg.equals("--no-compile-views") || arg.equals("-ncv")) {
				compileViews = false;
			} else if (arg.equals("--token") || arg.equals("-t")) {
				if (++id < args.length) {
					token = args[id];
				} else {
					System.out.println("Missing token value");
					help = true;
				}
			} else {
				break;
			}
		}

		if (!help && args.length >= 2 + id) {
			View.setCompiler(new JavaScriptViewCompiler());
			PrepareCBLDatabase me = new PrepareCBLDatabase();
			database_name = args[id + 1];
			System.out.println("database: " + database_name);
			me.compileViews = compileViews;
			me.openDatabase();
			Path zipPath = Paths.get(args.length >= (id + 3) ? args[id + 2] : "./fs." + database_name + ".zip");
			try {
				me.authenticate(args[id + 0], token);
				me.replicate(args[id + 0], zipPath);
			} finally {
				me.logout(args[id + 0]);
			}
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
			System.out.println("  -t   --token            : authentication token from the Convertigo lib_PrepareFSDatabase project");
			System.out.println("");
			System.out.println("The prebuilt database will be created in the current directory.");
		}
	}

	private void logout(String endpoint) throws IOException {
		if (client != null) {
			RequestBody requestBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("__sequence", "Logout")
				.build();
			Request request = new Request.Builder()
				.url(endpoint + "/projects/lib_PrepareFSDatabase/.json")
				.post(requestBody)
				.build();
			client.newCall(request).execute();
		}
	}

	void authenticate(String endpoint, String token) throws IOException {
		System.out.println("token: " + token);
		if (token == null) {
			return;
		}
		RequestBody requestBody = new MultipartBody.Builder()
				.setType(MultipartBody.FORM)
				.addFormDataPart("__sequence", "Authenticate")
				.addFormDataPart("token", token)
				.build();

		Request request = new Request.Builder()
				.url(endpoint + "/projects/lib_PrepareFSDatabase/.json")
				.post(requestBody)
				.build();
		Call call = client.newCall(request);
		Response response = call.execute();
		String body = response.body().string();
		ScriptEngine engine = new ScriptEngineManager().getEngineByName("nashorn");
		try {
			Object res = engine.eval("var o = (" + body + "); o.error ? o.error : o.ok == 'true'");
			if (Boolean.TRUE.equals(res)) {
				return;
			} else {
				System.err.println("Authentication failed: " + res);
			}
		} catch (ScriptException e) {
			System.err.println("Authentication failed: " + e);
		}
		System.exit(1);
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
		Log.enableLogging(Log.TAG, Log.ASSERT);
		Logger logger = java.util.logging.Logger.getLogger("com.couchbase.lite");
		logger.setLevel(Level.OFF);
		
		SimpleCookieJar cookieJar = new SimpleCookieJar();
		manager.setDefaultHttpClientFactory(new CouchbaseLiteHttpClientFactory(cookieJar));
		client = new OkHttpClient.Builder().cookieJar(cookieJar).build();
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
		final long tsStart = System.currentTimeMillis();
		final long time[] = {0};
		final long count[] = {0};
		/*
		Authenticator  auth = new BasicAuthenticator("","");
		pull.setAuthenticator(auth);
		 */

		pull.addChangeListener(new Replication.ChangeListener() {
			@Override
			public void changed(Replication.ChangeEvent event) {
				// will be called back when the pull replication status changes
				long now = System.currentTimeMillis();
				boolean stop = event.getStatus() ==  ReplicationStatus.REPLICATION_STOPPED;
				if (now > time[0] || stop) {
					System.out.println("[" + (count[0]++) + "] Replicated : " + event.getCompletedChangeCount() + " Status : " + event.getStatus());
					time[0] = now + 5000;
				}
				if (stop) {
					try {
						long tsStop = now;
						Path cbldir = Paths.get("./data/data/com.couchbase.lite.test/files/cblite/" + database_name + ".cblite2").toAbsolutePath();
						SQLiteStore store = new SQLiteStore(cbldir.toString(), manager, db);
						store.open();
						String rev = store.getInfo("checkpoint/" + pull.remoteCheckpointDocID());
						store.setInfo("prebuiltrevision", rev);
						store.close();
						
						long tsView = 0;
						if (compileViews) {
							doCompileViews();
							tsView = System.currentTimeMillis();
						}
						
						
						
						System.out.print("\nZipping database ...");
						zipDir(cbldir, zipPath);
						long tsZip = System.currentTimeMillis();
						
						System.out.println(", Database zip has been created in : " + zipPath);
						System.out.println("Replication in " + (tsStop - tsStart) / 1000 + "s.");
						if (tsView > 0) {
							System.out.println("Compile view in " + (tsView - tsStop) / 1000 + "s.");
						}
						System.out.println("Zip in " + (tsZip - tsView) / 1000 + "s.");
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
	void doCompileViews() throws CouchbaseLiteException {
		QueryOptions qo = new QueryOptions();
		qo.setStartKey("_design/");
		qo.setEndKey("_design/~");
		Map<String, Object> docs = db.getAllDocs(qo);

		if (docs.containsKey("rows")) {
			List<QueryRow> rows = (List<QueryRow>) docs.get("rows");
			for (QueryRow row : rows) {
				try {
					String ddoc = row.getDocumentId().substring("_design/".length());
					Map<String, Map<String, Object>> views = (Map<String, Map<String, Object>>) db.getExistingDocument(row.getDocumentId()).getProperties().get("views");
					if (views != null) {
						for (Entry<String, Map<String, Object>> view : views.entrySet()) {
							String tdViewName = ddoc + "/" + view.getKey();
							try {
								System.out.print("Compile View : " + tdViewName + "\r");
								compileView(tdViewName, view.getValue()).updateIndexAlone();
							} catch (Exception e) {
								System.out.println("Failed to handle the view '" + tdViewName + "' cause by: " + e);
							}
						}
					}
				} catch (Exception e) {
					System.out.println("Failed to handle the document '" + row.getDocumentId() + "' cause by: " + e);
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

	class SimpleCookieJar implements ClearableCookieJar {
		List<Cookie> cookies = Collections.emptyList();

		@Override
		public void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
			System.out.println("saveFromResponse " + url + " " + cookies);
			this.cookies = cookies;
		}

		@Override
		public List<Cookie> loadForRequest(HttpUrl url) {
			return cookies;
		}

		@Override
		public void clear() {
			cookies = Collections.emptyList();
		}

		@Override
		public boolean clearExpired(Date date) {
			return false;
		}

	}

	/**
	 * Zip the database directory ton one Zip File
	 * 
	 * @param dirToZip
	 * @param out
	 */
	public static void zipDir(final Path dirToZip, final Path out) {
		try (final ZipOutputStream zipOut = new ZipOutputStream(new FileOutputStream(out.toFile()))) {
			StringBuilder sb = new StringBuilder();

			Files.walkFileTree(dirToZip, new SimpleFileVisitor<Path>() {

				public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) throws IOException {
					zipOut.putNextEntry(new ZipEntry(dirToZip.relativize(dir).toString() + "/"));
					zipOut.closeEntry();
					return FileVisitResult.CONTINUE;
				}

				public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) throws IOException {
					String path = dirToZip.relativize(file).toString();
					zipOut.putNextEntry(new ZipEntry(path));
					DigestOutputStream digest;
					try {
						digest = new DigestOutputStream(zipOut, MessageDigest.getInstance("MD5"));
					} catch (NoSuchAlgorithmException e) {
						throw new IOException(e);
					}
					Files.copy(file, digest);
					sb.append(path + "\n");
					sb.append(file.toFile().length() + "\n");
					sb.append(Base64.encodeToString(digest.getMessageDigest().digest(), Base64.NO_WRAP) + "\n");
					zipOut.closeEntry();
					return FileVisitResult.CONTINUE;
				}
			});

			zipOut.putNextEntry(new ZipEntry("md5-b64.txt"));
			OutputStreamWriter osw = new OutputStreamWriter(zipOut, "UTF-8");
			osw.write(sb.toString());
			osw.flush();
			zipOut.closeEntry();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

}
