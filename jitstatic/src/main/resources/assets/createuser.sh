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

ROLES=""
HOST="http://localhost:8085/application"
ERR=""
BRANCH=""

set -o pipefail

function usage () {
  echo "Usage: $0 -u <user> -p <password> -r <realm> [-b <branch>] [-h <host>] -o <roles,...> [-cu <credential username> -cp <credential password> | -cr <creds username>:<credential password>]"
}

function urlencode() {
   local LANG=C i c e=''
   for ((i=0;i<${#1};i++)); do
      c=${1:$i:1}
      [[ "$c" =~ [a-zA-Z0-9\.\~\_\-] ]] || printf -v c '%%%02X' "'$c"
      e+="$c"
   done
   echo "$e"
}

while test $# -gt 0; do
  case "$1" in
    -u)
    USER=$2
    shift 2
    ;;
    -p)
    PASSWD=$2
    shift 2
    ;;
    -o)
    ROLES=$2
    shift 2
    ;;
    -r)
    REALM=$2
    shift 2
    ;;
    -h)
    HOST=$2
    shift 2
    ;;
    -b)
    BRANCH=$2
    shift 2
    ;;
    -cu)
    CREDS_USER=$2
    shift 2;
    ;;
    -cp)
    CREDS_PASS=$2
    shift 2;
    ;;
    -cr)
    CREDS=$2
    shift 2
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


if [[ -z "${USER}" ]]; then
  echo "Need to set a user to be created"
  read -p "Enter user name to be created " USER
fi

if [[ -z "${USER}" ]]; then
  echo "Need to set a user to be created"
  exit 1
fi

if [[ -z "${PASSWD}" ]]; then
  echo "Need to set a password for the user to be created "
  read -p "Enter created users' password " USER
fi

if [[ -z "${PASSWD}" ]]; then
  echo "Need to set a password to be created"
  exit 1
fi
data=""
case $REALM in
  keyuser | keyadmin )
    IFS=',' read -ra data <<< "$ROLES"
    ;;
  git )
    BRANCH="";
    if [[ -z "$ROLES" ]]; then
        echo "Enter git user's roles."
        echo "Valid roles are:"
        echo "pull,push,forcepush,secrets,create"
        read -p "Enter them as a comma separated list: " input
        IFS=',' read -ra data <<< "$input"
    else
       # Validate git roles or?
       IFS=',' read -ra data <<< "$ROLES"
    fi
    ;;
  * )
    echo "Need to set a realm. Valid entries are keyuser, keyadmin or git"
    usage
    exit 1
    ;;
esac
ROLES=""
idx=0;
for role in "${data[@]}"; do
    if [[ $idx > 0 && $idx < ${#data[@]} ]]; then
        ROLES="${ROLES},"
    fi
    ROLES="${ROLES}{\"role\":\"${role}\"}"
    idx=$((idx + 1))
done

if [[ -z "${CREDS}" ]]; then
    CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ "${CREDS}" = ":" ]] ; then
  read -p "Enter your user name: " CREDS_USER
  read -p "Enter your password: " CREDS_PASS
  CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ ! -z "${BRANCH}" ]]; then
  BRANCH="?ref=$(urlencode ${BRANCH})"
fi

curl -i -s -H 'Content-Type: application/json' \
--user "${CREDS}" -X POST \
-d "{\"roles\":[${ROLES}],\"basicPassword\":\"${PASSWD}\"}" \
$HOST/users/${REALM}/${USER}${BRANCH}

