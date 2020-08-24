#!/bin/bash
set +e

# trap ctrl-c and call ctrl_c()
trap ctrl_c INT

function ctrl_c {
  if [ ! -z "$CMD_PID" ]; then
    kill $CMD_PID
    exit 1
  fi
}

if [ "$#" -lt 2 ] && [ "$#" -gt 3 ]; then
  echo "Usage: driver.sh span_sample_rate_list stack_sampling_period_list [output_dir]"
  exit 2
fi

mkdir -p ".bin"

if [ -z "$JAVA_HOME" ]; then
  JAVA_CMD="java"
else
  JAVA_CMD="$JAVA_HOME/bin/java"
fi

if [ -z "$AGENT_HOME" ]; then
  AGENT_HOME="./.bin"
fi

if [ -z "$JMETER_HOME" ]; then
  JMETER_HOME="./.bin/apache-jmeter-5.3"
fi

if [ -z "$PETCLINIC_JAR" ]; then
  PETCLINIC_JAR=$(find .bin -name "spring-petclinic*BUILD-SNAPSHOT.jar" -type f)
fi

IFS=',' read -r -a SAMPLE_RATES <<< "$1"
IFS=',' read -r -a SAMPLE_PERIODS <<< "$2"

OUTPUT_DIR=$3
if [ -z "$OUTPUT_DIR" ]; then
  OUTPUT_DIR=".reports"
fi

if [ ! -e "$OUTPUT_DIR" ]; then
  mkdir -p $OUTPUT_DIR
fi

# This will run spring petclinic with MLT enabled and various sample rates and stack sampling periods.
# Once the app is up and running JMeter will be used to generate load simulating user interaction.
# The metrics are captured in a JTL file which is then processed such that aggregates (min, max, avg, percentiles)
# per request and exported to CSV file.
# The script takes comma-delimited list of span sample rates as its first argument, comma-delimited list of stack
# sampler periods as the second argument and an optional third argument specifying the output directory.

for SAMPLE_RATE in "${SAMPLE_RATES[@]}"; do
  for SAMPLE_PERIOD in "${SAMPLE_PERIODS[@]}"; do
    OUTPUT_FILE=$OUTPUT_DIR/mlt_${SAMPLE_PERIOD}ms_$(echo print ${SAMPLE_RATE}*100 | perl).jtl
    OUTPUT_CSV_FILE=$OUTPUT_DIR/mlt_${SAMPLE_PERIOD}ms_$(echo print ${SAMPLE_RATE}*100 | perl).csv

    echo "==== Span Sample Rate: ${SAMPLE_RATE}, Stack Sampling Period: ${SAMPLE_PERIOD}, Output File: $OUTPUT_FILE, CSV File: $OUTPUT_CSV_FILE"
    rm -f $OUTPUT_FILE
    $JAVA_CMD -javaagent:${AGENT_HOME}/dd-java-agent.jar -Ddd.profiling.enabled=true -Ddd.trace.enabled=true -Ddd.method.trace.enabled=true -Ddd.method.trace.sample.rate=${SAMPLE_RATE} -DnbThreads=200 -Dmlt.sampler.ms=${SAMPLE_PERIOD} -jar $PETCLINIC_JAR &
    CMD_PID=$!
    echo "=> Waiting for Spring petclinic"
    while [ true ]; do
      curl -I --fail localhost:8080/ 2>/dev/null
      if [ 0 -eq $? ]; then
        echo ""
        break
      fi
      echo -n "."
      sleep 1
    done

    echo "=> Running load"
    $JMETER_HOME/bin/jmeter -n -Jtarget_server=localhost -t ~/src/pet_clinic.jmx -l $OUTPUT_FILE

    echo "=> Generating CSV file: ${OUTPUT_CSV_FILE}"
    ./jtl2csv.py $OUPTUT_FILE > $OUTPUT_CSV_FILE

    echo "=> Shutting down Spring petclinic"
    kill $CMD_PID
  done
done
