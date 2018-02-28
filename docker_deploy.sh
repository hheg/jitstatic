#!/bin/sh

VERSION=`./docker_build.sh`
ERR=$?
if [ ${ERR} != 0 ]; then
	echo "Building docker image failed"
	exit 1
fi
REPO="$1/jitstatic"
case ${VERSION} in
     *-SNAPSHOT) REPO="${REPO}-snapshot";;
     *);;
esac
docker login -u="$1" -p="$2"
ERR=$?
if [ ${ERR} != 0 ]; then
	echo "Error login in"
	exit ${ERR}
fi
docker push ${REPO}
docker logout