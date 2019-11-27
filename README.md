[![Build Status](https://travis-ci.org/hheg/jitstatic.svg?branch=master)](https://travis-ci.org/hheg/jitstatic)
[![Coverage Status](https://coveralls.io/repos/github/hheg/jitstatic/badge.svg?branch=master)](https://coveralls.io/github/hheg/jitstatic?branch=master)

# JitStatic

JitStatic is a key-value storage where the data is managed by a git repository. You can access each key from the database with a simple GET or modify a key with a PUT and still do all the operations you'd do with git to manage the database.
Modifications to keys are stored in the Git history as if it would if you changed it locally.
It's supposed to work as a online store where data changes but still need to be under version and access control. It can host any type of data.

The Git interface is powered by JGit and offers almost full Git support (except for .gitattributes). Accessing the repo and the data end point can be configured to be on separate user/password combinations.

### An example Use case:

The use case could be used for is business configuration data where the data is not directly related to specific code. These tend to end up in a database where they are logged in some fashion. After some time they usually gets forgotten why they are there and their purpose. Having them connected to Git could mean you can use them with your documentation or issue tracker in a more natural way than having them stored in a database. 

Uses Dropwizards simple configuration.

Example configuration:
```yaml
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
    hostedEndpoint: git
    userName: huser
    secret: hseCr3t
    cors:
      allowedOrigins: "*"
```
storage is the key-value end point and hosted is the Git end point.

## Hello world

### Docker:
```bash
docker run -e USER=huser -e PASS=hseCr3t -v $PWD:/home/jitstatic/db -d -p 8085:8085 hheg/jitstatic:latest
```
The container is reachable on port 8085. Remember to run the container in an empty directory or change $PWD to a directory of your liking. The directory must be owned by a user with id 1000 and group 1000

### Manually: 
To setup an instance you download a jitstatic.jar and store the above example configuration in a file, lets say `config.yaml`
 
To launch jitstatic you'll type:
```bash
java -jar jistatic.jar server config.yaml

21:26:35.950 [main] INFO io.jitstatic.JitstaticApplication - Starting 0.21.1-SNAPSHOT build 4ab20e8
INFO  [2018-11-23 20:26:37,350] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-11-23 20:26:37,352] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-11-23 20:26:37,355] io.jitstatic.hosted.HostedGitRepositoryManager: Mounting repository on /tmp/jitstatic/remote
INFO  [2018-11-23 20:26:37,449] io.jitstatic.hosted.HostedFactory: Configuring hosted GIT environment on /jitstatic/git
INFO  [2018-11-23 20:26:37,476] io.jitstatic.hosted.HostedFactory: CORS is enabled for /storage
INFO  [2018-11-23 20:26:37,476] io.jitstatic.hosted.HostedFactory: CORS is enabled for /storage/*
INFO  [2018-11-23 20:26:37,476] io.jitstatic.hosted.HostedFactory: CORS is enabled for /metakey/*
INFO  [2018-11-23 20:26:37,476] io.jitstatic.hosted.HostedFactory: CORS is enabled for /users/*
INFO  [2018-11-23 20:26:37,476] io.jitstatic.hosted.HostedFactory: CORS is enabled for /bulk/*
INFO  [2018-11-23 20:26:37,477] io.jitstatic.hosted.HostedFactory: CORS is enabled for /info/*
INFO  [2018-11-23 20:26:37,503] io.dropwizard.server.SimpleServerFactory: Registering jersey handler with root path prefix: /app
INFO  [2018-11-23 20:26:37,503] io.dropwizard.server.SimpleServerFactory: Registering admin handler with root path prefix: /admin
INFO  [2018-11-23 20:26:37,505] io.dropwizard.server.ServerFactory: Starting JitstaticApplication
INFO  [2018-11-23 20:26:37,642] org.eclipse.jetty.setuid.SetUIDListener: Opened JitstaticApplication@52831a73{HTTP/1.1,[http/1.1]}{0.0.0.0:8085}
INFO  [2018-11-23 20:26:37,645] org.eclipse.jetty.server.Server: jetty-9.4.z-SNAPSHOT; built: 2018-06-05T18:24:03.829Z; git: d5fc0523cfa96bfebfbda19606cad384d772f04c; jvm 10.0.2+13
INFO  [2018-11-23 20:26:38,471] io.dropwizard.jersey.DropwizardResourceConfig: The following paths were found for the configured resources:

    POST    /bulk/fetch (io.jitstatic.api.BulkResource)
    GET     /info/commitid (io.jitstatic.api.JitstaticInfoResource)
    GET     /info/version (io.jitstatic.api.JitstaticInfoResource)
    GET     /metakey/{key : .+} (io.jitstatic.api.MetaKeyResource)
    PUT     /metakey/{key : .+} (io.jitstatic.api.MetaKeyResource)
    GET     /storage (io.jitstatic.api.KeyResource)
    GET     /storage/{key : .+/} (io.jitstatic.api.KeyResource)
    DELETE  /storage/{key : .+} (io.jitstatic.api.KeyResource)
    GET     /storage/{key : .+} (io.jitstatic.api.KeyResource)
    OPTIONS /storage/{key : .+} (io.jitstatic.api.KeyResource)
    POST    /storage/{key : .+} (io.jitstatic.api.KeyResource)
    PUT     /storage/{key : .+} (io.jitstatic.api.KeyResource)
    DELETE  /users/git/{key : .+} (io.jitstatic.api.UsersResource)
    GET     /users/git/{key : .+} (io.jitstatic.api.UsersResource)
    POST    /users/git/{key : .+} (io.jitstatic.api.UsersResource)
    PUT     /users/git/{key : .+} (io.jitstatic.api.UsersResource)
    DELETE  /users/keyadmin/{key : .+} (io.jitstatic.api.UsersResource)
    GET     /users/keyadmin/{key : .+} (io.jitstatic.api.UsersResource)
    POST    /users/keyadmin/{key : .+} (io.jitstatic.api.UsersResource)
    PUT     /users/keyadmin/{key : .+} (io.jitstatic.api.UsersResource)
    DELETE  /users/keyuser/{key : .+} (io.jitstatic.api.UsersResource)
    GET     /users/keyuser/{key : .+} (io.jitstatic.api.UsersResource)
    POST    /users/keyuser/{key : .+} (io.jitstatic.api.UsersResource)
    PUT     /users/keyuser/{key : .+} (io.jitstatic.api.UsersResource)


INFO  [2018-11-23 20:26:38,495] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@2e7bb00e{/app,null,AVAILABLE}
INFO  [2018-11-23 20:26:38,500] io.dropwizard.setup.AdminEnvironment: tasks = 

    POST    /tasks/log-level (io.dropwizard.servlets.tasks.LogConfigurationTask)
    POST    /tasks/gc (io.dropwizard.servlets.tasks.GarbageCollectionTask)

INFO  [2018-11-23 20:26:38,514] org.eclipse.jetty.server.handler.ContextHandler: Started i.d.j.MutableServletContextHandler@2cea921a{/admin,null,AVAILABLE}
INFO  [2018-11-23 20:26:38,544] org.eclipse.jetty.server.AbstractConnector: Started JitstaticApplication@52831a73{HTTP/1.1,[http/1.1]}{0.0.0.0:8085}
INFO  [2018-11-23 20:26:38,547] org.eclipse.jetty.server.Server: Started @2861ms
```

This should start JitStatic on port 8085.

Now you can clone the git repository with:
```bash
git clone http://localhost:8085/app/jitstatic/git
Cloning to "git"...
remote:    __  _ _   __ _        _   _      
remote:    \ \(_) |_/ _\ |_ __ _| |_(_) ___ 
remote:     \ \ | __\ \| __/ _` | __| |/ __|
remote:  /\_/ / | |__\ \ || (_| | |_| | (__ 
remote:  \___/|_|\__\__/\__\__,_|\__|_|\___|
remote:                                     0.23.0
remote: Counting objects: 4, done
remote: Finding sources: 100% (4/4)
remote: Getting sizes: 100% (3/3)
remote: Total 4 (delta 0), reused 4 (delta 0)
Packing up objects: 100% (4/4), done.

Warning: You seem to have cloned an empty repository
```
Remember to use the values for the master user and password you chose when you started the container (in the example above it's `huser` and password is `hseCr3t`). How user management work, see the Users section.

In your target directory create a key store file:
```bash
echo '{"hello" : "world"}' > hello_world
echo '{"read":[],"write":[]}' > hello_world.metadata
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
remote:                                     0.20.0
remote: Checking refs/heads/master branch.
remote: refs/heads/master OK!
To http://localhost:8085/app/jitstatic/jitstatic.git
 * [new branch]      master -> master
```
The `Checking refs/heads/master branch` line is JitStatic checking that all the keys have a corresponding metadata file in the repository.
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

There must be a file which contains metadata about the `hello_world` file and it's name is `hello_world.metadata`. It must be a JSON file and contain information for each key. There can be a master .metadata that specifies for all keys in the folder, except those with their own metadata file. A metadata file contains set of read and write roles for corresponding users. How users and roles work is specified in the [Users](#users) section.
```json
{"contentType":"application/json","protected":false,"hidden":false,"headers":[],"read":[{"role":"somerole"}],"write":[{"role":"somerole"}]}
```
To specify a key which can be read by all specify an empty `"read":[]` section and if you want to make it write protected you can specify an empty `"write":[]`.

To reach the `hello_world` data the address could look like the command below. To access the `hello_world` key you need a user which has the role you have specified in the `hello_world.metadata`file, in this example it's `somerole`. Each key can be protected so only one or several users can access that particular key
 
```bash
curl --user user1:1234 http://localhost:8085/app/storage/hello_world
```

At the moment the application only allows basic authentication so be sure you secure it with HTTPS by using standard Dropwizard HTTPS configuration or some external SSL terminator.

## Cloning the hosted repository

To clone the repo you just type (the user name and password defined in the Dropwizard configuration file)
```bash
git clone http://huser:hsecr3t@localhost:8085/app/jitstatic/git
```
It's also possible to reach keys (files) through refs, so if you make a call like:

```bash
curl --user user1:1234 http://localhost:8085/app/storage/hello_world?ref=refs/heads/somebranch
``` 

Or specifying a tag it's possible to do this:

```bash
curl --user user1:1234 http://localhost:8085/app/storage/hello_world?ref=refs/tags/sometag
``` 

### API for modifying a key

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
Use that to be able to modify the `hello_world` by sending a PUT command to change it. You'll have to provide a commit message as well. Since JitStatic is supporting any content and due to the JSON format, the data has to be an base64 encoded byte array. If the the data is a string, this string must be in the UTF-8 format. JavaScript f.ex uses a version of UTF-16 as default encoding for their strings which cause the text to get garbled if the string is stored directly, without being converted. It has to be UTF-8 since that's how Git stores text.
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

You can create a key by just POSTing the content to the server. Since there are no information about who can do what on the server, the user to do this is the same one for the git access point, ie the master password. It's possible to add the ref in the body, or as a query parameter and if it's omitted it will default to what ref the server have defaulted to.

```
curl -i -H 'Content-Type: application/json' \
--user huser:hseCr3t -X POST \
-d '{"data":"eyJvbmUiOiJ0d28ifQ==","message":"testmessage","userMail":"test@test.com","metaData":{"read":[],"write":[],"contentType":"application/json"},"userInfo":"user","ref":"refs/heads/master"}' \
http://localhost:8085/app/storage/test

HTTP/1.1 200 OK
Date: Sun, 04 Mar 2018 00:10:37 GMT
ETag: "70990754f75e398b92f3b56d04b3bbd79fddc37b"
Content-Type: application/json
Content-Encoding: utf-8
Content-Length: 0

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
-d '{"message":"msg","userInfo":"ui","userMail":"mail","metaData":{"read":[{"role":"somerole"}],"write":[{"role":"somerole"}],"contentType":"plain/text"}}' \
http://localhost:8085/app/metakey/hello_world

HTTP/1.1 200 OK
Date: Sat, 14 Apr 2018 18:11:52 GMT
ETag: "bc42a0f75a4850525194b28fc8e419efbfec334a"
Content-Encoding: utf-8
Content-Length: 0
```

### API for deleting a key

The API for deleting a key looks like the following:

```
curl -i -H 'Content-Type: application/json' \
-H 'X-jitstatic-name: user' \
-H 'X-jitstatic-message: reason why the key is deleted' \
-H 'X-jitstatic-mail: user@somewhere.org' \
--user user1:1234 -X DELETE \
http://localhost:8085/app/storage/hello_world
HTTP/1.1 200 OK
Date: Mon, 21 May 2018 21:09:29 GMT
Content-Length: 0

```
Deleting master metadata files is not supported, but is considered to be supported.

### API for listing keys

It's possible to list keys under a directory which looks like this:
```
root/dir1/file1
root/dir1/file2
root/dir3/file3
root/dir1/dir2/file4
file5
```
And making a query for all files in `root/dir1/` will result in the combined result of the content of keys `root/dir1/file1, root/dir1/file2`

```
curl --user user1:1234 -i http://localhost:8085/app/storage/root/dir1/
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 20:36:23 GMT
Content-Type: application/json
Vary: Accept-Encoding
Content-Length: 290

{"result":[{"key":"root/dir1/file1","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922","data":"eyJoZWxsbyIgOiAid29ybGQifQo="},{"key":"root/dir1/file2","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922","data":"eyJoZWxsbyIgOiAid29ybGQifQo="}]}
```
It is possible to do a recursive search to include `root/dir1/dir2/file4` with:
```
curl --user user1:1234 -i http://localhost:8085/app/storage/root/dir1/?recursive=true
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 20:37:09 GMT
Content-Type: application/json
Vary: Accept-Encoding
Content-Length: 434

{"result":[{"key":"root/dir1/dir2/file4","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922","data":"eyJoZWxsbyIgOiAid29ybGQifQo="},{"key":"root/dir1/file1","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922","data":"eyJoZWxsbyIgOiAid29ybGQifQo="},{"key":"root/dir1/file2","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922","data":"eyJoZWxsbyIgOiAid29ybGQifQo="}]}
```
And if you want to just to get the keys and no payload you can do this:
```
curl --user user1:1234 -i http://localhost:8085/app/storage/root/dir1/?recursive=true\&light=true
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 20:37:41 GMT
Content-Type: application/json
Vary: Accept-Encoding
Content-Length: 320

{"result":[{"key":"root/dir1/dir2/file4","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922"},{"key":"root/dir1/file1","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922"},{"key":"root/dir1/file2","type":"application/json","tag":"264f8aec58118e2682091653017213ace0c04922"}]}
```
### API for Fetching multiple keys

It's possible to fetch multiple keys from a repo like this:

```
[master]
root/dir1/file1
root/dir1/file2
root/dir3/file3
root/dir1/dir2/file4
file5
```

```
curl --user user1:1234 -i http://localhost:8085/app/bulk/fetch \
-H 'Content-Type: application/json' \
-X POST \
-d '[{"ref":"refs/heads/master","paths":[{"path":"root/dir1/","recursively":false},{"path":"root/dir3/file3","recursively":true}]},{"ref":"refs/heads/develop","paths":[{"path":"root/dir1/","recursively":false},{"path":"root/dir3/file3","recursively":false}]}]'
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 20:38:21 GMT
Content-Type: application/json
Content-Length: 537

{"result":[{"key":"root/dir1/file1","tag":"264f8aec58118e2682091653017213ace0c04922","contentType":"application/json","content":"eyJoZWxsbyIgOiAid29ybGQifQo=","ref":"refs/heads/master"},{"key":"root/dir1/file2","tag":"264f8aec58118e2682091653017213ace0c04922","contentType":"application/json","content":"eyJoZWxsbyIgOiAid29ybGQifQo=","ref":"refs/heads/master"},{"key":"root/dir3/file3","tag":"264f8aec58118e2682091653017213ace0c04922","contentType":"application/json","content":"eyJoZWxsbyIgOiAid29ybGQifQo=","ref":"refs/heads/master"}]}
```

All keys are still protected by authorization. The keys are still protected by the corresponding access rules. 


### MetaKeys

Each key have a <key_name>.metadata which stores information about the key. It defines contentType, headers that should be used and who can read and write to the files. You could if you want hide files from being accessed from the API by using the `hidden` property. It can also be read only by using the `protected` property.
You could if you want create a master .metadata in the directory root and all keys in that directory (not sub folders) will have that key. If you need you can override that by specifying a specific metadata file for a particular file in that folder. The users field is deprecated and the preferred way of RBAC is to use the role based RBAC described in the Users section. 
Example:
```json
{"contentType":"application/json","protected":false,"hidden":false,"headers":[{"header":"tag","value":"1234"},{"header":"header","value":"value"}],"read":[{"role":"read"}],"write":[{"role":"write"}]}
```

## <a name="users"></a> Users

A user is stored in the repository with the following format:
```bash
.users/<realm>/<username>
```
The format of the file <username> is in the format 
```
{"roles":[{"role":"role"}],"basicPassword":"pass"}
```
The `.user` folder not a valid key so this can't be reached from the normal endpoints.

#### RBAC

There are two ways of declaring RBAC for a specific key. The old `users` field in the metadata file and the preferred way of defining `read` and write `roles`in the metadata file and then specify a user with a corresponding role and a password. If you use the API for creating users, the passwords will be automatically hashed when stored. If more security is needed an additional salt can be configured. If this is done, all clones must define that in it's settings to have same password work across all of them.

#### Public keys

A key which does specify an empty users field and the read role as empty means that this key is readable by all. The write role specifies who can write to the same key.

### Realms

There are four security realms in JitStatic which covers parts of the functionality.
* Git realm
* KeyAdmin realm
* KeyUser realm
* Admin realm

#### The Git realm .users/git/

This realm covers the user management for accessing the Git repository with RBAC for who can `pull`, `push`, force push a branch (`forcepush`) or read the `secrets`. This information is stored in a special branch called `refs/heads/secrets` which is clonable only if you have the role `secrets`. This branch shouldn't be merged into any other branch and if the folder `.users/git/`is in an another branch it will be ignored.

By having this separation each repository can create their own secrets branch, so the information can be protected and sites can have more protection.

#### The KeyAdmin realm .users/keyadmin/

A user defined this realm has full admin (create/read/write/delete) rights to all keys in that branch the user is defined. These users have no roles but the branch is the role. They can read both key and metadatakey.

#### The KeyUser realm .users/keyuser/

A user defined in this realm can only manage their (exclusively) own keys by defining their role to the `write` role of the key. A shared key can't be deleted by a `keyuser`. A `keyuser` can create a key if the key doesn't exist. They can allow other roles to read and write to the file but then they can't delete the file. A user in this realm can read both key and metadatakey if they belong to the read roles and write roles.

#### The admin realm

This special realm is only for protecting the urls `/admin/healthcheck`and `/admin/metrics` if these can't be protected by other means.
This is done by adding the following to the configuration

```yaml
hosted:
    ...
    adminName: admin
    adminPass: pass
    protectMetrics: true
    protectHealthChecks: true
    protectTasks: true
```
or by specifying arguments to the application or to `JAVA_OPTS` 

```
-Ddw.hosted.adminName=admin -Ddw.hosted.adminPass=pass -Ddw.hosted.protectMetrics=true -Ddw.hosted.protectHealhcheck=true
```

### Users API

There's an API for the user interface. The rules are that a user in the keyadmin realm can add users to the keyuser realm the branch they are admins for. Only a user in the git realm (refs/heads/secrets) can add keyadmins to any branch. It follows the same mechanic as the previous API's.

Fetching a user keyadminuser in the realm keyadmin with a user from the git realm called gituser.
```bash
curl --user gituser:3234 -i http://localhost:8085/app/users/keyadmin/keyadminuser
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 21:18:14 GMT
ETag: "c1eb087e3498d309a051f3051e92938dc9b4d04b"
Content-Encoding: utf-8
Content-Type: application/json
Content-Length: 61

{"roles":[{"role":"one"},{"role":"two"}]}

```

Updating a user

```bash
curl -i -H 'Content-Type: application/json' \
-H 'If-Match: "c1eb087e3498d309a051f3051e92938dc9b4d04b"' \
--user gituser:3234 -X PUT \
-d '{"roles":[{"role":"one"},{"role":"two"}],"basicPassword":"p2"}' \
-i http://localhost:8085/app/users/keyadmin/keyadminuser
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 21:23:45 GMT
ETag: "70a0f29ac3f0468a367a55f52004b5eafdfbe2e3"
Content-Length: 0
```

Adding a user

```bash
curl -i -H 'Content-Type: application/json' \
--user gituser:3234 -X POST \
-d '{"roles":[{"role":"one"},{"role":"two"}],"basicPassword":"p3"}' \
-i http://localhost:8085/app/users/keyadmin/otherkeyadmin
HTTP/1.1 200 OK
Date: Fri, 23 Nov 2018 21:58:14 GMT
ETag: "70dd186fee57318a0d30ebd8cd6b2f79d978c36d"
Content-Length: 0

```

## CORS Support

JitStatic has now CORS support. To enable CORS support to the docker container add
```bash
docker run -e JAVA_OPTS="-Ddw.hosted.cors.allowOrigins=*" -e USER=huser -e PASS=hseCr3t -v $PWD:/home/jitstatic/db -d -p 8085:8085 hheg/jitstatic:latest
```
or
```bash
java -Ddw.hosted.cors.allowOrigins=* -jar jistatic.jar server config.yaml
```

## JSON Logging support

To enable JSON logging for the main log output you add the following to the startup (assuming the it's the first appender you'd like to output JSON)
```bash
	-Ddw.logging.appenders[0].layout.type=json
```
and to make the access logs in JSON you'd use
```bash
	-Ddw.server.requestLog.appenders[0].layout.type=access-json
```

## CLI (Alpha)

There's a CLI for creating and managing users. To get the scripts you can retrieve them at the endpoint, `<appliction base>/cli/`, as such:
```
curl http://localhost:8085/application/cli/createuser.sh
curl http://localhost:8085/application/cli/updateuser.sh
curl http://localhost:8085/application/cli/deleteuser.sh
curl http://localhost:8085/application/cli/fetch.sh
```
Note that this is alpha code and may change in future releases. 

## Java client
You can find a Java client for JitStatic in Maven Central with coordinates 

```xml
<dependency>
    <groupId>io.jitstatic</groupId>
    <artifactId>jitstatic-client</artifactId>
    <version>0.11.0</version>
</dependency>
```
## Disclaimer

This is a project with some interesting challenges I'd like to explore while being useful at the same time. This is used in production today, but it's also a way of testing some new ideas and techniques, which haven't yet found their way to production :).


