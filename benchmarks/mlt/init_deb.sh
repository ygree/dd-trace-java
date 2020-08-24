#!/bin/bash

sudo apt install python-pip perl wget curl
pip install python-jtl
pip install numpy

mkdir -p .bin
if [ ! -d ".bin/apache-jmeter-5.3" ]; then
  (cd .bin && wget https://downloads.apache.org/jmeter/binaries/apache-jmeter-5.3.tgz && tar xvzf apache-jmeter-5.3.tgz && rm -f apache-jmeter-5.3.tgz)
fi

if [ ! -d ".bin/spring-petclinic" ]; then
  (cd .bin && git clone https://github.com/spring-projects/spring-petclinic.git && cd spring-petclinic && ./mvnw install)
fi

curl -s "https://get.sdkman.io" | bash
sdk install java 11.0.8.hs-adpt
sdk default java 11.0.8.hs-adpt


AGENT_URL=$1

if [ -z "$AGENT_URL" ]; then
  AGENT_URL="https://oss.jfrog.org/oss-snapshot-local/com/datadoghq/dd-java-agent/0.61.0-MLT-SNAPSHOT/dd-java-agent-0.61.0-MLT-SNAPSHOT.jar"
fi

wget -O .bin/dd-java-agent.jar "$AGENT_URL"

echo "All set. Now you can do eg. 'bash driver.sh 0.1,0.2 5,7,9' to run some benchmarks (or 'bash driver.sh' for the usage help)"
