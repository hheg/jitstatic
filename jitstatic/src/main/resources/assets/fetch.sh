#!/usr/bin/env bash

###
# #%L
# jitstatic
# %%
# Copyright (C) 2017 - 2019 H.Hegardt
# %%
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
# 
#      http://www.apache.org/licenses/LICENSE-2.0
# 
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
# #L%
###

HOST="http://localhost:8085/application"
BRANCH=""
ERR=""

set -o pipefail

function usage () {
  echo "Usage: $0 -p <path> [-d <alternative destination>] [-r] [-b <branch>] [-h <host>] [-cu <credential username> -cp <credential password> | -cr <credential username:credential password>]"
}

function checkformat () {
  case ${1} in
    / | /*)
    case ${1} in
        / | */)
        ;;
        *)
        echo "$2 needs to end with a '/'"
        usage
        exit 1
        ;;
    esac
    ;;
    *)
    echo "$2 needs to start with a '/'"
    usage
    exit 1
    ;;
  esac
}

function decode () {
  case "$(uname -s)" in
      Darwin)
        echo $(base64 -D $1)
        ;;
      Linux)
        echo $(base64 -d $1)
        ;;
      *)
        echo "$(uname -s) is unsupported"
  esac
}

while test $# -gt 0; do
  case "$1" in
    -p)
    PPATH=$2
    shift 2
    ;;
    -d)
    DIR=$2
    shift 2
    ;;
    -b)
    BRANCH=$2
    shift 2
    ;;
    -h)
    HOST=$2
    shift 2
    ;;
    -cu)
    CREDS_USER=$2
    shift 2
    ;;
    -cp)
    CREDS_PASS=$2
    shift 2
    ;;
    -cr)
    CREDS=$2
    shift 2
    ;;
    -r)
    RECURSIVE="--data-urlencode recursive=true"
    shift
    ;;
    *)
     ERR="$ERR $1"
    shift
    ;;
  esac
done

if [[ ! -z "$ERR" ]]; then
	echo "Unknown arguments '$ERR'"
	usage
	exit 1
fi

if [[ -z "${PPATH}" ]]; then
  echo "Need to set a path"
  usage
  exit 1
fi

if [[ -z "${DIR}" ]]; then
    DIR="${PPATH}"
fi

checkformat "${PPATH}" "Path"

checkformat "${DIR}" "Directory"

if [[ -z "${CREDS}" ]]; then
    CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ "${CREDS}" = ":" ]] ; then
  read -p "Enter your user name: " CREDS_USER
  read -p "Enter your password: " CREDS_PASS
  CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ ! -z $BRANCH ]]; then
  BRANCH="--data-urlencode ref=${BRANCH}"
fi

declare -a arr
arr=($(curl -s -G -X GET --user "${CREDS}" ${BRANCH} ${RECURSIVE} "${HOST}/storage${PPATH}" | jq --compact-output '.result[]'))
err=$?

if [[ $err -ne 0 ]]; then
  echo "Failed to retrieve files"
  exit 1
fi

echo "Parsing files"
for r in "${arr[@]}" ; do
  key=$(echo "$r" | jq '.key' | tr -d '"')
  tag=$(echo "$r" | jq '.tag' | tr -d '"')
  dst=$(pwd)${DIR}${key#"${PPATH:1}"}
  echo "$key $tag > $dst"
  dstdir=$(dirname $dst)
  mkdir -p $dstdir
  echo "$r" | jq '.data' | tr -d '"' | decode > $dst
done
