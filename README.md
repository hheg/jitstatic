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
    port: 8085
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

### Docker:
```bash
docker pull hheg/jitstatic:latest
docker run -e USER=huser -e PASS=hseCr3t -v $PWD:/home/jitstatic/db -d -p 8085:8085 hheg/jitstatic:latest
```
Now the container is reachable on port 8085

### Manually: 
To setup an instance you download a jitstatic.jar and store the above example configuration in a file, lets say `config.yaml`
 
To lauch jitstatic you'll type:
```bash
java -jar jistatic.jar server config.yaml

INFO  [2018-04-14 17:58:29,514] org.eclipse.jetty.util.log: Logging initialized @1751ms to org.eclipse.jetty.util.log.Slf4jLog
INFO  [2018-04-14 17:58:29,596] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-04-14 17:58:29,597] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-04-14 17:58:29,603] io.jitstatic.hosted.HostedGitRepositoryManager: Mounting repository on /tmp/jitstatic/remote
INFO  [2018-04-14 17:58:29,743] io.jitstatic.hosted.HostedFactory: Configuring hosted GIT environment on /jitstatic/*
INFO  [2018-04-14 17:58:29,776] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-04-14 17:58:29,776] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-04-14 17:58:29,778] io.dropwizard.server.ServerFactory: Starting JitstaticApplication
INFO  [2018-04-14 17:58:29,878] org.eclipse.jetty.setuid.SetUIDListener: Opened JitstaticApplication@3009eed7{HTTP/1.1,[http/1.1]}{0.0.0.0:8080}
INFO  [2018-04-14 17:58:29,880] org.eclipse.jetty.server.Server: jetty-9.4.z-SNAPSHOT, build timestamp: 2017-11-21T22:27:37+01:00, git hash: 82b8fb23f757335bb3329d540ce37a2a2615f0a8
INFO  [2018-04-14 17:58:30,546] io.dropwizard.jersey.DropwizardResourceConfig: The following paths were found for the configured resources:

    GET     /info/commitid (io.jitstatic.api.JitstaticInfoResource)
    GET     /info/version (io.jitstatic.api.JitstaticInfoResource)
    GET     /metakey/{key : .+} (io.jitstatic.api.MetaKeyResource)
    PUT     /metakey/{key : .+} (io.jitstatic.api.MetaKeyResource)
    POST    /storage (io.jitstatic.api.MapResource)
    GET     /storage/{key : .+} (io.jitstatic.api.MapResource)
    PUT     /storage/{key : .+} (io.jitstatic.api.MapResource)

WARN  [2018-04-14 17:58:30,549] org.glassfish.jersey.internal.Errors: The following warnings have been detected: WARNING: Parameter authFilterClass of type java.lang.Class<? extends javax.ws.rs.container.ContainerRequestFilter> from private final java.lang.Class<? extends javax.ws.rs.container.ContainerRequestFilter> io.dropwizard.auth.AuthDynamicFeature.authFilterClass is not resolvable to a concrete type.
WARNING: Parameter authFilterClass of type java.lang.Class<? extends javax.ws.rs.container.ContainerRequestFilter> from private final java.lang.Class<? extends javax.ws.rs.container.ContainerRequestFilter> io.dropwizard.auth.AuthDynamicFeature.authFilterClass is not resolvable to a concrete type.

INFO  [2018-04-14 17:58:30,556] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@598778cc{/app,null,AVAILABLE}
INFO  [2018-04-14 17:58:30,561] io.dropwizard.setup.AdminEnvironment: tasks = 

    POST    /tasks/log-level (io.dropwizard.servlets.tasks.LogConfigurationTask)
    POST    /tasks/gc (io.dropwizard.servlets.tasks.GarbageCollectionTask)

INFO  [2018-04-14 17:58:30,567] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@120411ec{/admin,null,AVAILABLE}
INFO  [2018-04-14 17:58:30,573] org.eclipse.jetty.server.AbstractConnector: Started JitstaticApplication@3009eed7{HTTP/1.1,[http/1.1]}{0.0.0.0:8085}
INFO  [2018-04-14 17:58:30,574] org.eclipse.jetty.server.Server: Started @2812ms

```

This should start JitStatic on port 8085.

Now you can clone the git repository with:
```bash
git clone http://localhost:8085/app/jitstatic/git
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
To http://localhost:8085/app/jitstatic/jitstatic.git
 * [new branch]      master -> master
```

Now you can do:
```bash
curl --user user1:1234 -i http://localhost:8085/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:22:38 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "264f8aec58118e2682091653017213ace0c04922"
Content-Length: 20

{"hello" : "world"}
```

## JitStatic (the long version)

The repository contains files which contains the data. Each of these files are also the access key and its possible to use directories to separate files. The keys could contain any data you want.
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
curl --user user1:1234 http://localhost:8085/app/storage/hello_world
```

If you leave an metadata file with an empty 'users' entry anyone can access the key. If you there's no user restriction you can't modify the key through the modify API.

At the moment the application only allows basic authentication so be sure you secure it with HTTPS by using standard Dropwizard HTTPS configuration.

## Cloning the hosted repository

To clone the repo you just type (the username and password defined in the Dropwizard configuration file)
```bash
git clone http://huser:hsecr3t@localhost:8085/app/jitstatic/jitstatic.git
```
It's also possible to reach keys (files) through refs so if you make a call like:

```bash
curl --user user1:1234 http://localhost:8085/app/storage/hello_world?ref=refs/heads/somebranch
``` 

Or speficifying a tag it's possible to do this:

```bash
curl --user user1:1234 http://localhost:8085/app/storage/hello_world?ref=refs/tags/sometag
``` 

## API for modyfying a key

Now there's an API for modifying a `hello_world` from an application. You do it with in three simple steps:

First get the current version your `hello_world` is at.
```bash
curl --user user1:1234 -i http://localhost:8085/app/storage/hello_world

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
-d '{"message":"message","data":"eyJvbmUiOiJ0d28ifQ==","userMail":"mail","userInfo":"user"}' \
http://localhost:8085/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:33:53 GMT
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Encoding: utf-8
Content-Length: 0

```
It will return the new version of the `hello_world`.
Making a new GET will return:
```
curl --user user1:1234 -i http://localhost:8085/app/storage/hello_world

HTTP/1.1 200 OK
Date: Sat, 03 Mar 2018 23:34:40 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Length: 13

{"one":"two"}
```
and the git log will look something like:
```git
* c7952e2 - (HEAD -> master, origin/master) commit message (1 minutes ago) <usr>
* 0abb47d - Added key (2 minutes ago) <usr>
```

The version number here is the files git blob version and not the git commit's version.

### Modify tags

You can't use the modify API on a tag since tags are immutable. You can still change them with Git as usual.

### API for creating keys

You can create a key by just POSTing the content to the server. Since there are no information about who can do what on the server, the user to do this is the same one for the git access point, ie the master password.

```
curl -i -H 'Content-Type: application/json' \
--user huser:hseCr3t -X POST \
-d '{"key":"test","branch":"refs/heads/master","data":"eyJvbmUiOiJ0d28ifQ==","message":"testmessage","userMail":"test@test.com","metaData":{"users":[{"password":"1234","user":"user1"}],"contentType":"application/json"},"userInfo":"user"}' \
http://localhost:8085/app/storage

HTTP/1.1 200 OK
Date: Sun, 04 Mar 2018 00:10:37 GMT
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Type: application/json
Content-Encoding: utf-8
Content-Length: 19

{"one" : "two"}
```
You can now GET and PUT this endpoint with 
```
curl --user user1:1234 -i http://localhost:8085/app/storage/test
HTTP/1.1 200 OK
Date: Sun, 04 Mar 2018 00:12:47 GMT
Content-Type: application/json
Content-Encoding: utf-8
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Length: 19

{"one" : "two"}
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
 
git log --graph --pretty=format:'%h %d %s %cr <%an>' --abbrev-commit --date=relative
* d397e03 - (HEAD -> master, origin/master) testmessage (4 minutes ago) <user>
* 7cfbec2 - message (4 minutes ago) <user1>
* 3af867a - Initial commit (4 minutes ago) <hheg>
```


### API for modifying a key's metadata

There's also an API for remotely change a key's metadata file using the master password.
```bash
curl --user huser:hseCr3t -i http://localhost:8085/app/metakey/hello_world

HTTP/1.1 200 OK
Date: Sat, 14 Apr 2018 18:08:51 GMT
Content-Encoding: utf-8
ETag: "9eaea0b295e8daa399924a2961cd25958381aa59"
Content-Type: application/json
Content-Length: 79

{"users":[{"user":"user1","password":"1234"}],"contentType":"application/json"}
```
Then you'd take that tag information and use that in the PUT operation.
```bash
curl -i -H 'Content-Type: application/json' \
-H 'If-Match: "9eaea0b295e8daa399924a2961cd25958381aa59"' \
--user huser:hseCr3t -X PUT \
-d '{"message":"msg","userInfo":"ui","userMail":"mail","metaData":{"users":[{"user":"user1","password":"1234"}],"contentType":"plain/text"}}' \
http://localhost:8085/app/metakey/hello_world

HTTP/1.1 200 OK
Date: Sat, 14 Apr 2018 18:11:52 GMT
ETag: "bc42a0f75a4850525194b28fc8e419efbfec334a"
Content-Encoding: utf-8
Content-Length: 0
```

## Java client

You can find a Java client for jitstatic at 

```xml
<dependency>
    <groupId>io.jitstatic</groupId>
    <artifactId>jitstatic-client</artifactId>
    <version>0.3.1</version>
</dependency>
```

