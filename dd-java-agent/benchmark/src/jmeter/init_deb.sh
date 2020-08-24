#!/bin/bash

sudo apt install python-pip perl wget curl
pip install python-jtl
pip install numpy

if [ -z "$PETCLINIC_URL" ]; then
  PETCLINIC_URL="https://github.com/spring-projects/spring-petclinic.git"
fi

if [ -z "$PETCLINIC_TAG" ]; then
  PETCLINIC_TAG="MLT"
fi

if [ -z "$JMETER_URL" ]; then
  JMETER_URL="https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.3.tgz"
fi

mkdir -p .bin
if [ ! -d ".bin/apache-jmeter-5.3" ]; then
  (cd .bin && wget "$JMETER_URL" && find . -name "apache-jmeter-*.tgz" -type f | xargs -I {} tar xvzf {} && rm -f apache-jmeter-*.tgz)
fi

if [ ! -d ".bin/spring-petclinic" ]; then
  (cd .bin && git clone "$PETCLINIC_URL" && git checkout PETCLINIC_TAG && cd spring-petclinic && ./mvnw install)
fi

if [ ! -d ~/.sdkman ]; then
  curl -s "https://get.sdkman.io" | bash
fi
sdk install java 11.0.8.hs-adpt
sdk install java 8.0.265.hs-adpt
sdk default java 11.0.8.hs-adpt

JAVA_8_HOME="/home/ubuntu/.sdkman/candidates/java/8.0.265.hs-adpt"
JAVA_11_HOME="/home/ubuntu/.sdkman/candidates/java/11.0.8.hs-adpt"

JAVA_HOME=$JAVA_8_HOME ../../gradlew --parallel dd-java-agent:shadowJar
find ../../dd-java-agent/build/libs -name "*-SNAPSHOT.jar" -type f | xargs -I {} cp {} .bin/dd-java-agent.jar
#
#
#AGENT_URL=$1
#
#if [ -z "$AGENT_URL" ]; then
#  AGENT_URL="https://oss.jfrog.org/oss-snapshot-local/com/datadoghq/dd-java-agent/0.61.0-MLT-SNAPSHOT/dd-java-agent-0.61.0-MLT-SNAPSHOT.jar"
#fi
#
#wget -O .bin/dd-java-agent.jar "$AGENT_URL"

echo "All set. Now you can do eg. 'bash driver.sh 0.1,0.2 5,7,9' to run some benchmarks (or 'bash driver.sh' for the usage help)"
