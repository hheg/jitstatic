[![Build Status](https://travis-ci.org/hheg/jitstatic.svg?branch=master)](https://travis-ci.org/hheg/jitstatic)
[![Coverage Status](https://coveralls.io/repos/github/hheg/jitstatic/badge.svg?branch=master)](https://coveralls.io/github/hheg/jitstatic?branch=master)

# JitStatic

JitStatic is a key-value storage where the data is managed by a git repository. You can access each key from the database with a simple GET.
It's supposed to work as a online "static" store where data changes but still need to be under version control.

You can push and pull from it as if it were an ordinary git repo (full git support except for .gitattributes). Accessing the repo and the data end point can be configured to be on separate user/password combinations.

### Use case:

The use case could be used for is business configuration data where the data is not directly related to specific code. These tend to end up in a database where they are logged in some fashion. After some time they ususally gets forgotten why they are there and their purpose. Having them connected to Git could mean you can use them with your documentation or issue tracker in a more natural way than having them stored in a database. 

Uses Dropwizards simple configuration.

Example configuration:
```
server:
  applicationContextPath: /app
  type: simple
  maxThreads: 1024
  connector:
    type: http
    port: 8080
  requestLog:
    appenders:
      - type: console
logging:
  level: INFO
  appenders:
    - type: console
hosted:
    basePath: file:/tmp/jitstatic/remote
    servletName: jitstatic
    hostedEndpoint: jitstatic.git
    userName: huser
    secret: hseCr3t
```
storage is the key-value end point and hosted is the git end point.

## Hello world

To setup an instance you download a jitstatic.jar and store the above example configuration in a file, lets say `config.yaml`
To lauch jitstatic you'll type:
```bash
java -jar jistatic.jar server config.yaml

INFO  [2018-03-03 23:15:29,026] org.eclipse.jetty.util.log: Logging initialized @1374ms to org.eclipse.jetty.util.log.Slf4jLog
INFO  [2018-03-03 23:15:29,090] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-03-03 23:15:29,091] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-03-03 23:15:29,098] jitstatic.hosted.HostedGitRepositoryManager: Mounting repository on /tmp/jitstatic/remote
INFO  [2018-03-03 23:15:29,237] jitstatic.hosted.HostedFactory: Configuring hosted GIT environment on /jitstatic/*
INFO  [2018-03-03 23:15:29,271] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-03-03 23:15:29,272] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-03-03 23:15:29,273] io.dropwizard.server.ServerFactory: Starting JitstaticApplication
INFO  [2018-03-03 23:15:29,385] org.eclipse.jetty.setuid.SetUIDListener: Opened JitstaticApplication@76464795{HTTP/1.1,[http/1.1]}{0.0.0.0:8080}
INFO  [2018-03-03 23:15:29,387] org.eclipse.jetty.server.Server: jetty-9.4.z-SNAPSHOT
INFO  [2018-03-03 23:15:29,994] io.dropwizard.jersey.DropwizardResourceConfig: The following paths were found for the configured resources:

    GET     /info/commitid (jitstatic.api.JitstaticInfoResource)
    GET     /info/version (jitstatic.api.JitstaticInfoResource)
    POST    /storage (jitstatic.api.MapResource)
    GET     /storage/{key : .+} (jitstatic.api.MapResource)
    PUT     /storage/{key : .+} (jitstatic.api.MapResource)

INFO  [2018-03-03 23:15:30,004] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@db99785{/app,null,AVAILABLE}
INFO  [2018-03-03 23:15:30,011] io.dropwizard.setup.AdminEnvironment: tasks = 

    POST    /tasks/log-level (io.dropwizard.servlets.tasks.LogConfigurationTask)
    POST    /tasks/gc (io.dropwizard.servlets.tasks.GarbageCollectionTask)

INFO  [2018-03-03 23:15:30,023] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@49edcb30{/admin,null,AVAILABLE}
INFO  [2018-03-03 23:15:30,042] org.eclipse.jetty.server.AbstractConnector: Started JitstaticApplication@76464795{HTTP/1.1,[http/1.1]}{0.0.0.0:8080}
INFO  [2018-03-03 23:15:30,042] org.eclipse.jetty.server.Server: Started @2391ms

```

This should start JitStatic on port 8080.

Now you can clone the git repository with:
```bash
git clone http://localhost:8080/app/jitstatic/jitstatic.git
Warning: You seem to have cloned an empty repository
```
In you target directory create a key store file:
```bash
echo '{"hello" : "world"}' > hello_world
echo '{"users" : [{"password": "1234", "user" : "user1"}]}' > hello_world.metadata
```
Then you add the files with git
```bash
git add .
git commit -m "Initial commit"
git push

Couting objects: 4, done.
Delta compression using up to 4 threads.
Compressing objects: 100% (3/3), done.
Writing objects: 100% (4/4), 325 bytes | 325.00 KiB/s, done.
Total 4 (delta 0), reused 0 (delta 0)
remote:    __  _ _   __ _        _   _      
remote:    \ \(_) |_/ _\ |_ __ _| |_(_) ___ 
remote:     \ \ | __\ \| __/ _` | __| |/ __|
remote:  /\_/ / | |__\ \ || (_| | |_| | (__ 
remote:  \___/|_|\__\__/\__\__,_|\__|_|\___|
remote:                                     
remote: Checking refs/heads/master branch.
remote: refs/heads/master OK!
To http://localhost:8080/app/jitstatic/jitstatic.git
 * [new branch]      master -> master
```

Now you can do:
```bash
curl --user user1:1234 -i http://localhost:8080/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:22:38 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "264f8aec58118e2682091653017213ace0c04922"
Content-Length: 20

{"hello" : "world"}
```

## JitStatic (the long version)

The repository contains files which contains the data. Each of these files are also the access key and its possible to use directories to separate files. The data in these keys must be in JSON (in this version) however it will be possible to store any data in the future :).
```json
{"hello" : "world"}
```
Each file on the repo will be the access point for getting the object. This example is the content of a file called `hello_world`.

There must be a file which contains metadata about the `hello_world` file and it's name is `hello_world.metadata`. It must be a JSON file and right now must contain which users that can access the key.
```json
{"users" : [{"password": "1234", "user" : "user1"}]}
```

To reach the `hello_world` data the address could look like the command below. The user and password for the endpoint will be what you have specified in the `key.metadata` file. Each key can be protected so only one or several users can access that particular key
 
```bash
curl --user user1:1234 http://localhost:8080/app/storage/hello_world
```

If you leave an metadata file with an empty 'users' entry anyone can access the key. If you there's no user restriction you can't modify the key through the modify API.

At the moment the application only allows basic authentication so be sure you secure it with HTTPS by using standard Dropwizard HTTPS configuration.

## Cloning the hosted repository

To clone the repo you just type (the username and password defined in the Dropwizard configuration file)
```bash
git clone http://huser:hsecr3t@localhost:8080/app/jitstatic/jitstatic.git
```
It's also possible to reach keys (files) through refs so if you make a call like:

```bash
curl --user user1:1234 http://localhost:8080/app/storage/hello_world?ref=refs/heads/somebranch
``` 

Or speficifying a tag it's possible to do this:

```bash
curl --user user1:1234 http://localhost:8080/app/storage/hello_world?ref=refs/tags/sometag
``` 

## API for modyfying a key

Now there's an API for modifying a `hello_world` from an application. You do it with in three simple steps:

First get the current version your `hello_world` is at.
```bash
curl --user user1:1234 -i http://localhost:8080/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:22:38 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "264f8aec58118e2682091653017213ace0c04922"
Content-Length: 20

{"hello" : "world"}
```
Use that to be able to modify the `hello_world` by sending a PUT command to change it. You'll have to provide a commit message as well. Since JitStatic is supporting any content, unfortunately the data has to be an base64 encoded array.
```
curl -i -H 'Content-Type: application/json' \
-H 'If-Match: "264f8aec58118e2682091653017213ace0c04922"' \
--user user1:1234 -X PUT \
-d '{"message":"message","data":"eyJvbmUiOiJ0d28ifQ==","userMail":"mail"}' \
http://localhost:8080/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:33:53 GMT
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Encoding: utf-8
Content-Length: 0

```
It will return the new version of the `hello_world`.
Making a new GET will return:
```
curl --user user1:1234 -i http://localhost:8080/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:34:40 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Length: 13

{"one":"two"}
```
and the git log will look like:
```git
* c7952e2 - (HEAD -> master, origin/master) commit message (1 minutes ago) <usr>
* 0abb47d - Added key (2 minutes ago) <usr>
```

The version number here is the files git blob version and not the git commit's version.

### Modify tags

You can't use the modify API on a tag since tags are immutable. You can still change them with Git as usual.

### API for creating keys

You can create a key by just POSTing the content to the server. Since there are no information about who can do what on the server, the user to do this is the same one for the git access point.

```
curl -i -H 'Content-Type: application/json' \
--user huser:hseCr3t -X POST \
-d '{"key":"test","branch":"refs/heads/master","data":"eyJvbmUiOiJ0d28ifQ==","message":"testmessage","userMail":"test@test.com","metaData":{"users":[{"password":"1234","user":"user1"}],"contentType":"application/json"},"userInfo":"user"}' \
http://localhost:8080/app/storage

HTTP/1.1 200 OK
Date: Sun, 04 Mar 2018 00:10:37 GMT
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Type: application/json
Content-Encoding: utf-8
Content-Length: 19

{
  "one" : "two"
}
```
You can now GET and PUT this endpoint with 
```
curl --user user1:1234 -i http://localhost:8080/app/storage/test
HTTP/1.1 200 OK
Date: Sun, 04 Mar 2018 00:12:47 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Length: 19

{
  "one" : "two"
}
```
and the git log looks like:
```
git pull
Updating 7cfbec2..d397e03
Fast-forward
 test          | 3 +++
 test.metadata | 1 +
 2 files changed, 4 insertions(+)
 create mode 100644 test
 create mode 100644 test.metadata
 
 git lg
* d397e03 - (HEAD -> master, origin/master) testmessage (4 minutes ago) <user>
* 7cfbec2 - message (4 minutes ago) <user1>
* 3af867a - Initial commit (4 minutes ago) <hheg>
```




