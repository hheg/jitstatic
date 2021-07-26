#!/bin/bash

PROFILE=""

if [[ ! -z "${TRAVIS_TAG}" ]] ; then
	PROFILE="-Prelease"
fi

mvn -Dorg.slf4j.simpleLogger.log.org.apache.maven.cli.transfer.Slf4jMavenTransferListener=warn deploy jacoco:report coveralls:report -B -V -e $PROFILE