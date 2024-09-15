# Kotlin Server Scripts

Kotlin Server Scripts let you run kotlin scripts in response to server requests, without setting up a complete project.

## Setup

There is an easy to use install script to install the latest release on a linux system. It downloads the release
archive, installs the executable and creates and enables a system service. 

```shell
curl 'https://raw.githubusercontent.com/EdwarDDay/kotlin-server-scripts/main/install.sh' --output install.sh
chmod u+x install.sh
./install.sh # admin privileges might be needed 
./install.sh --directory '/opt/local/bin' --user '_www' # for MacOS
```

The `install.sh` script will by default save the executable in the `/usr/bin/` directory, creates system service file
with `www-data` as service execution user and puts a configuration sample file. If you want to configure any of these
settings or see the default values, execute `./install.sh --help` for more information.

### Nginx setup

To execute server scripts, add something like the following configuration to you nginx.conf:

```nginx
# match all kts files
location ~ ^(.*)\.kts(\?.*)?$ {
  # match the server script with .server.kts extension
  try_files \$1.server.kts =404;
  # use default fastcgir parameters
  include fastcgi_params;
  # pass request to kss process
  fastcgi_pass unix:/var/run/kss/kss.sock;
}
```

## Example

Example script looks like:

`HelloWorld.server.kts`
```kotlin
setHeader("Content-Type", "text/html")
status(200)
writeOutput(
    """
    <!DOCTYPE html>
    <html>
    <head>
      <title>Hello Kotlin</title>
    </head>
    <body>
      <h1>Hello Kotlin</h1>
    </body
    </html>
    """
)
```

You can find more examples in the [test folder](scripting-host/src/test/resources)

### Add dependencies

If you want to add a maven dependency, you can add a `@file:DependsOn("<artifact id>"")` annotation to the file. If your
dependency isn't in the maven central repository, you can add additional repositories via a
`@file:Repository("<repository url>")` annotation at file level.

## Building

To build the library just run `./gradlew scripting-host:build`.
