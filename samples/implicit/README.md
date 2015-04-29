# Implicit Grant Sample Application

This application is a sample for how you can set up your own application that uses an implicit grant type. The application is written in java and uses Spring framework.
Implicit grants are typically used for Single-page javascript apps.

## Quick Start

Start your UAA

    $ git clone git@github.com:cloudfoundry/uaa.git
    $ cd uaa
    $ ./gradlew run

Verify that the uaa has started by going to http://localhost:8080/uaa

Start the implicit sample application

### Using Gradle:

    $ cd samples/implicit
    $ ./gradlew run
    >> 2015-04-24 15:39:15.862  INFO 88632 --- [main] o.s.c.support.DefaultLifecycleProcessor  : Starting beans in phase 0
    >> 2015-04-24 15:39:15.947  INFO 88632 --- [main] s.b.c.e.t.TomcatEmbeddedServletContainer : Tomcat started on port(s): 8888 (http)
    >> 2015-04-24 15:39:15.948  INFO 88632 --- [main] o.c.i.samples.implicit.Application       : Started Application in 4.937 seconds (JVM running for 5.408)


### Using Maven:

    $ cd samples/implicit
    $ mvn package
    $ java -jar target/implicit-sample-1.0.0-SNAPSHOT.jar
    >> 2015-04-24 15:39:15.862  INFO 88632 --- [main] o.s.c.support.DefaultLifecycleProcessor  : Starting beans in phase 0
    >> 2015-04-24 15:39:15.947  INFO 88632 --- [main] s.b.c.e.t.TomcatEmbeddedServletContainer : Tomcat started on port(s): 8888 (http)
    >> 2015-04-24 15:39:15.948  INFO 88632 --- [main] o.c.i.samples.implicit.Application       : Started Application in 4.937 seconds (JVM running for 5.408)

You can start the implicit grant flow by going to http://localhost:8888

Login with the pre-created UAA user/password of "marissa/koala"