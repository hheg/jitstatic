#!/usr/bin/env bash

set -o pipefail

while test $# -gt 0; do
  case "$1" in
    -p)
    shift
    PPATH=$1
    shift
    ;;
    -d)
    shift
    DIR=$1
    shift
    ;;
    -b)
    shift
    BRANCH=$1
    shift
    ;;
    -u)
    shift
    BASE=$1
    shift
    ;;
    -cu)
    shift
    CREDS_USER=$1
    shift
    ;;
    -cp)
    shift
    CREDS_PASS=$1
    shift
    ;;
    -cr)
    shift
    CREDS=$1
    shift
    ;;
    *)
    echo "Unknown parameter $1"
    usage
    exit 1
    ;;
  esac
done


BRANCH=${BRANCH:-"refs/heads/master"} 
BASE=${BASE:-"http://localhost:8086"}

function usage () {
  echo "Usage: $0 -p <path> -d <dir> [-b <branch>] [-u <base>] [[-cu <user>] [-cp <pass>] | -cr <user:pass>]"
}

if [[ -z "${PPATH}" ]]; then
  echo "Need to set a path"
  usage
  exit 1
fi

if [[ -z "${DIR}" ]]; then
  echo "Need to set a directory"
  usage
  exit 1
fi

CREDS="${CREDS_USER}:${CREDS_PASS}"

if [[ "${CREDS}" = ":" ]] ; then
  read -p "Enter your git user name: " CREDS_USER
  read -p "Enter your git password: " CREDS_PASS
  CREDS="${CREDS_USER}:${CREDS_PASS}"
fi



if [[ ! -z $BRANCH ]]; then
  BRANCH="--data-urlencode ref=${BRANCH}"
fi

declare -a arr
arr=($(curl -s -G -X GET --user "${CREDS}" ${BRANCH} --data-urlencode "recursive=true" "${BASE}/config/storage/${PPATH}/${DIR}/" | jq --compact-output '.result[]'))
err=$?

if [[ $err -ne 0 ]]; then
  echo "Failed to retrieve files"
  exit 1
fi

echo "Parsing files"
for r in "${arr[@]}" ; do
  key=$(echo "$r" | jq '.key' | tr -d '"')
  tag=$(echo "$r" | jq '.tag' | tr -d '"')
  dst=$(pwd)${key#$PPATH}
  echo "$key $tag > $dst"
  dstdir=$(dirname $dst)
  mkdir -p $dstdir
  echo "$r" | jq '.data' | tr -d '"' | base64 -D > $dst
done
