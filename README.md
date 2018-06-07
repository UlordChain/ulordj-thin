
# Welcome to ulordj
[![](https://jitpack.io/v/UlordChain/ulordj-thin.svg)](https://jitpack.io/#UlordChain/ulordj-thin)

The ulordj library is a Java implementation of the Ulord protocol, which allows it to maintain a wallet and send/receive transactions without needing a local copy of Ulord Core. It comes with full documentation and some example apps showing how to use it.
USC made a "thin" version of ulordj including just the components needed by the USC node.

### Technologies

* Java 6 for the core modules, Java 8 for everything else
* [Maven 3+](http://maven.apache.org) - for building the project

### Getting started

To get started, it is best to have the latest JDK and Maven installed. The HEAD of the `master` branch contains the latest development code and various production releases are provided on feature branches.

#### Building from the command line

To perform a full build use
```
mvn clean package
```

The outputs are under the `target` directory.

#### Building from an IDE

Alternatively, just import the project using your IDE. [IntelliJ](http://www.jetbrains.com/idea/download/) has Maven integration built-in and has a free Community Edition. Simply use `File | Import Project` and locate the `pom.xml` in the root of the cloned project source tree.
