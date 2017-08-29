[![Build Status](https://travis-ci.org/hheg/jitstatic.svg?branch=master)](https://travis-ci.org/hheg/jitstatic)
[![Coverage Status](https://coveralls.io/repos/github/hheg/jitstatic/badge.svg?branch=master)](https://coveralls.io/github/hheg/jitstatic?branch=master)

jitstatic is a key-value storage where the data is managed by a git repository. You can access each key from the database with a simple GET.
It's supposed to work as a online static store where data changes slowly but still need to be under version control.

You can push an pull from it as if it were an ordinary git repo. Accessing the repo and the data enpoint can be configured to be on separate user/password combinations.

Uses dropwizards simple configuration.
Example configuration

```
server:
  applicationContextPath: /app/
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
storage:
    baseDirectory: /tmp/jitstatic/storage
    localFilePath: storage
    user: suser
    secret: ssecret
hosted:
    basePath: file:/tmp/jitstatic/remote
    servletName: jitstatic
    hostedEndpoint: jitstatic.git
    userName: huser
    secret: hseCr3t
```
storage is the key-value endpoint and hosted is the git endpoint. You can configure a remote repo too.

The repository should contain a file `storage.localFilePath` which contains the data. The data must be in json and in the format of:
```
{
	"key1": {
		"data": "value1",
		"users": [
			{
				"user": "suser",
				"password": "ssecret"
			}
		]
	},
	"key3": {
		"data": "value3",
		"users": [
			{
				"user": "suser",
				"password": "ssecret"
			}
		]
	}
}
```
`key1` will be the accesspoint for getting the object at `"value1"`, and the `"data"` could be any object.

To reach the `key1` data the adress could look like this: 
```
http://localhost:8080/app/storage/key1
```
To clone the repo you just type
```
git clone http://huser:hsecr3t@localhost:8080/app/jitstatic/jitstatic.git
```
The database is contained in the file `storage` as the configuration is set up.

Each key can be protected so only one or several users can access that particular key. If you leave an key with empty 'users' entry anyone can access the key.

Right now the application only allowes basic authentication so be sure you secure it with HTTPS by using standard Dropwizard HTTPS configuration.
