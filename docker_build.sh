#!/bin/sh
VERSION_FILE="jitstatic/target/classes/version"
if [ ! -f ${VERSION_FILE} ]; then
	echo "${VERSION_FILE} doesn't exist"
	exit 1
fi
VERSION=`cat jitstatic/target/classes/version`
if [ -z ${VERSION} ]; then
	echo "Build version is not defined"
	exit 1
fi
REPO="hheg/jitstatic"
case ${VERSION} in
     *-SNAPSHOT) REPO="${REPO}-snapshot";;
     *);;
esac

CMD="docker build --build-arg version=${VERSION} -t ${REPO}:${VERSION}"
BRANCH=`git rev-parse --abbrev-ref HEAD`
TAG=`git describe --exact-match HEAD 2>/dev/null`

if [ "${BRANCH}" = "HEAD" ] && [  "${TAG}" = "v${VERSION}" ]; then
	case ${VERSION} in
     	*-SNAPSHOT) echo "Error Build is a snapshot with tag" && exit 1;;
     	*);;
	esac 
	CMD="${CMD} -t ${REPO}:latest"
fi
CMD="${CMD} ."
echo $CMD
echo ${VERSION}
