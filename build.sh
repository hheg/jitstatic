#!/bin/bash

PROFILE=""

if [[ ! -z "${TRAVIS_TAG}" ]] ; then
	PROFILE="-Prelease"
fi

mvn deploy jacoco:report coveralls:report -B -V -e $PROFILE