#!/usr/bin/env bash

# find voltdb binaries
if [ -e ../../bin/voltdb ]; then
    # assume this is the examples folder for a kit
    VOLTDB_BIN="$(dirname $(dirname $(pwd)))/bin"
elif [ -n "$(which voltdb 2> /dev/null)" ]; then
    # assume we're using voltdb from the path
    VOLTDB_BIN=$(dirname "$(which voltdb)")
else
    echo "Unable to find VoltDB installation."
    echo "Please add VoltDB's bin directory to your path."
    exit -1
fi

# call script to set up paths, including
# java classpaths and binary paths
source $VOLTDB_BIN/voltenv

# leader host for startup purposes only
# (once running, all nodes are the same -- no leaders)
STARTUPLEADERHOST="localhost"
# list of cluster nodes separated by commas in host:[port] format
SERVERS="localhost"

# remove binaries, logs, runtime artifacts, etc... but keep the client jar
function clean() {
    rm -rf client/voltkv/*.class procedures/voltkv/*.class voltdbroot log
}

# remove everything from "clean" as well as the jarfiles
function cleanall() {
    clean
    rm -rf voltkv-procs.jar voltkv-client.jar
}

# compile the source code for the client into a jarfile
function jars() {
    # compile java source
    javac -classpath $APPCLASSPATH procedures/voltkv/*.java
    javac -classpath $CLIENTCLASSPATH client/voltkv/*.java
    # build procedure and client jars
    jar cf voltkv-procs.jar -C procedures voltkv
    jar cf voltkv-client.jar -C client voltkv
    # remove compiled .class files
    rm -rf procedures/voltkv/*.class client/voltkv/*.class
}

# compile the client jarfile if it doesn't exist
function jars-ifneeded() {
    if [ ! -e voltkv-procs.jar ] || [ ! -e voltkv-client.jar ]; then
        jars;
    fi
}

# run the voltdb server locally
function server() {
    voltdb create -H $STARTUPLEADERHOST --force
}

# load schema and procedures
function init() {
    jars-ifneeded
    sqlcmd < ddl.sql
}

# run the client that drives the example
function client() {
    async-benchmark
}

# Asynchronous benchmark sample
# Use this target for argument help
function async-benchmark-help() {
    jars-ifneeded
    java -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.AsyncBenchmark --help
}

# latencyreport: default is OFF
# ratelimit: must be a reasonable value if lantencyreport is ON
# Disable the comments (and add a preceding slash) to get latency report
function async-benchmark() {
    jars-ifneeded
    java -classpath voltkv-client.jar:$CLIENTCLASSPATH voltkv.AsyncBenchmark \
        --displayinterval=5 \
        --duration=120 \
        --servers=$SERVERS \
        --poolsize=100000 \
        --preload=true \
        --getputratio=0.90 \
        --keysize=32 \
        --minvaluesize=1024 \
        --maxvaluesize=1024 \
        --entropy=127 \
        --usecompression=false
#        --latencyreport=true \
#        --ratelimit=100000
}

function help() {
    echo "Usage: ./run.sh {clean|cleanall|jars|server|init|client|async-benchmark|aysnc-benchmark-help}"
}

# Run the targets pass on the command line
# If no first arg, run server
if [ $# -eq 0 ]; then server; exit; fi
for arg in "$@"
do
    echo "${0}: Performing $arg..."
    $arg
done

