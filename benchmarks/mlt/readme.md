This folder contains JMeter based load generation and aggregation scripts.

## Dependencies
* curl
* wget
* perl
* python
* python-pip
* numpy
* apache jmeter (at least 5.3)
* sdkman
* JDK 11
* DD Agent

## Structure
### JMeter Test Plan
* the JMeter load generator is driven by [pet_clinic.jmx](pet_clinic.jmx) test plan which is 
parameterized in regard of the target server
* the test plan goes over a number of requests a regular user would make and executes them in random order as quickly as possible
* currently, the number of distinct requests is hard-coded but it is possible to make it completely dynamic based on the available data 
* the test plan simulates 2 concurrent users

### Driver Script
* a shell [script](driver.sh) to start up the Petclinic Spring app with required parameters, start
the JMeter in headless mode and convert the JMeter native JTL files into CSV aggregations.
* the default settings can be modified by using the following env variables
   * JAVA_HOME - use this particular Java installation
   * AGENT_HOME - the location to search for `dd-java-agent.jar` file
   * JMETER_HOME - custom JMeter installation location
   * PETCLINIC_JAR - custom binary of the Petclinic Spring application

### Helper Scripts
* [init_deb.sh](init_deb.sh) 
  * a convenience script to download and install all required dependencies on a Debian-like OS
  * the default settings can be modified by using the following env variables
    * PETCLINIC_URL - url to `git clone` the petclinic project from
    * JMETER_URL - url to download the JMeter distribution from
* [jtl2cvs.py](jtl2cvs.py) - a script converting the JMeter JTL files into CSV counterparts with aggregated latencies (min, max, avg, percentiles)

## Running
Typically, the system will be initialized by running `sh init_deb.sh` or, if targeting a different system,
a corresponding sequence of commands to download and install the listed dependencies.

Once the system is set up the benchmarking can be started as `bash driver.sh 0.1,0.2,0.5,0.8 1,5,10,100,200,500` - 
the first comma separated list is the list of span sampling rates. The second comma separated list is the list
of stack sampling periods. These two lists are required.

The driver script will start the Petclinic Spring application for all the combinations of the span sampling rates
and the stack sampling periods. For each run a data file in the form of `mlt_<sampling_period>ms_<sample_rate * 100>.csv` 
which can be then imported into a spreadsheet app to analyze them. The driver will also generate and
preserve the low-level `mlt_<sampling_period>ms_<sample_rate * 100>.jtl` files which can eg. be loaded
directly in JMeter app or parsed in python using the `python-jtl` module.
