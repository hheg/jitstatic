[![Build Status](https://travis-ci.org/hheg/jitstatic.svg?branch=master)](https://travis-ci.org/hheg/jitstatic)
[![Coverage Status](https://coveralls.io/repos/github/hheg/jitstatic/badge.svg?branch=master)](https://coveralls.io/github/hheg/jitstatic?branch=master)

JitStatic is a key-value storage where the data is managed by a git repository. You can access each key from the database with a simple GET.
It's supposed to work as a online static store where data changes but still need to be under version control.

You can push and pull from it as if it were an ordinary git repo (full git support except for .gitattributes). Accessing the repo and the data end point can be configured to be on separate user/password combinations.

Use case:

The use case could be used for is business configuration data where the data is not directly related to specific code. These tend to end up in a database where they are logged in some fashion. After some time they ususally gets forgotten why they are there and their purpose. Having them connected to Git could mean you can use them with your documentation or issue tracker in a more natural way than having them stored in a database. 

Uses Dropwizards simple configuration.
Example configuration

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
storage is the key-value end point and hosted is the git end point. You can configure a remote repo too.

The repository can contain of files which contains the data. Each of these files are also the access key The data must be in JSON and in the format of:
```
{
	"data": {
		"data": "value1",
		"users": [
			{
				"user": "suser",
				"password": "ssecret"
			}
		]
	},
	"users" : [{"password": "1234", "user" : "user1"}]
}
```
Each file on the repo will be the access point for getting the object at `"data"`, and the `"data"` could be any JSON object. The example above is kept in a file called `key1`

To reach the `key1` data the address could look like this: 
```
curl --user user1:1234 http://localhost:8080/app/storage/key1
```
To clone the repo you just type
```
git clone http://huser:hsecr3t@localhost:8080/app/jitstatic/jitstatic.git
```

Each key can be protected so only one or several users can access that particular key. If you leave an key file with empty 'users' entry anyone can access the key.

At the moment the application only allows basic authentication so be sure you secure it with HTTPS by using standard Dropwizard HTTPS configuration.

It's also possible to reach keys (files) through refs so if you make a call like:

```
curl --user user1:1234 http://localhost:8080/app/storage/key1?ref=refs/heads/somebranch
``` 

Or speficifying a tag it's possible to do this:

```
curl --user user1:1234 http://localhost:8080/app/storage/key1?ref=refs/tags/sometag
``` 
*API for modyfying a key*

Now there's an API for modifying a `key` from an application. You do it with in three simple steps:

First get the current version your `key` is at.
```
> curl --user usr:pass http://localhost:8080/app/storage/key
< {"version":"7e37486e056c0a6bea6a099840bf73fb313dc8b7","data":{"key":{"data":"value1","users":[{"captain":"america","black":"widow"}]},"key3":{"data":"value3","users":[{"tony":"stark","spider":"man"}]}}}
```
Use that to be able to modify the `key` by sending a PUT command to change it. You'll have to provide a commit message as well
```
> curl -H 'Content-Type: application/json' \
--user usr:pass -X PUT \
-d '{"message":"commit message","haveVersion":"7e37486e056c0a6bea6a099840bf73fb313dc8b7","data":{"one":"two"}}' \
http://localhost:8080/app/storage/key
< {"version":"70990754f75e398b92f3b56d04b3bbd79fddc37b"}
```
It will return the new version of the `key`.
Making a new GET will return:
```
> curl --user usr:pass http://localhost:8080/app/storage/key
< {"version":"70990754f75e398b92f3b56d04b3bbd79fddc37b","data":{"one":"two"}}
```
and the git log will look like:
```
* c7952e2 - (HEAD -> master, origin/master) commit message (1 minutes ago) <usr>
* 0abb47d - Added key (2 minutes ago) <usr>
```

The version number here is the files git blob version and not the git commit's version.

You can't use the modify API on a tag since tags are immutable.

