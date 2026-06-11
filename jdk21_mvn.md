jdk21
```shell
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.common.analytics -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.gateway -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.impl -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.keymgt -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.persistence -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.rest.api.common -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.rest.api.publisher.v1.common -am
.\build-jdk21.ps1 clean install "-Dmaven.test.skip=true" "-Dcheckstyle.skip=true" "-Dspotbugs.skip=true" -pl components/apimgt/org.wso2.carbon.apimgt.rest.api.util -am
```

