# carbon-apimgt

## About this repository

|  Branch | Build Status(Jenkins) | Build Status(TravisCI) |
| :------------ |:------------- |:-------------
| master      | [![Build Status](https://wso2.org/jenkins/job/platform-builds/job/carbon-apimgt/badge/icon)](https://wso2.org/jenkins/view/platform/job/platform-builds/job/carbon-apimgt/) | [![Build Status](https://api.travis-ci.org/wso2/carbon-apimgt.svg?branch=master)](https://travis-ci.org/wso2/carbon-apimgt) |

## Building from the source

If you want to build carbon-apimgt from the source code:

1. Install Java 11 (https://adoptopenjdk.net/archive.html)
1. Install Apache Maven 3.x.x (https://maven.apache.org/download.cgi#)
1. Get a clone or download the source from this repository (https://github.com/wso2/carbon-apimgt.git).
1. Check out branch master as follows:\
``git checkout master``
1. Navigate to the ``carbon-apimgt`` directory and run the following Maven command.\
 ``mvn clean install``
1. 在插件的maven项目中先进行构建mvn package -D enforcer.skip=true -D maven.test.skip=true -D checkstyle.skip=true -D spotbugs.skip=true -T 1C
