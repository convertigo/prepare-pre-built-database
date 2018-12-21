# PreparePreBuiltDatabase
Tool to create Pre built FullSync databases

This tool will prepare a prebuilt mobile fullsync database you will be able to embed in your mobile apps 
Or bulk download when you mobile application is started.

Usage: PreparePreBuiltDatabase Convertigo_server_endpoint fullsync_database_name ex :
   PreparePreBuiltDatabase [options] http://my.convertigo.server.com:28080/convertigo  myfullsyncdatabase [prebuiltdatabase.zip]

Options are:
  -h   --help             : shows this message
  -ncv --no-compile-views : disables pre-indexing of all views
  -t   --token            : authentication token from the Convertigo lib_PrepareFSDatabase project

The prebuilt database will be created in the current directory.

Should be used with the [lib_PrepareFSDatabase](https://github.com/convertigo/c8oprj-lib-prepare-fs-database).