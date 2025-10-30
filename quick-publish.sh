#!/bin/bash

set -eo pipefail

sbt zincCore2_13/packageBin

cp internal/zinc-core/target/jvm-2.13/zinc-core_2.13-1.10.6-SNAPSHOT.jar \
  ../universe3/maven/scala-worker/org.scala-sbt/zinc-core_2.13/org.scala-sbt__zinc-core_2.13__1.10.8-bin-db-1-876b1ad98.jar
