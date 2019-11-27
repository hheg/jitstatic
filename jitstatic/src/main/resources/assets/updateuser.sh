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
BRANCH=""
HOST="http://localhost:8085/application"
ERR=""

function usage () {
  echo "Usage: $0 -u <user> -p <password> -r <realm> [-b <branch>] [-h <host>] [-o <role>,... | -a <add roles>,... | -d <delete roles>,...] [-cu <credential username> -cp <credential password> | -cr <credential username>:<credential password>]"
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

function json_roles(){
    IFS=',' read -ra data <<< "$1"
    result=""
    idx=0;
    for role in "${data[@]}"; do
        if [[ $idx > 0 && $idx < ${#data[@]} ]]; then
            result="${result},"
        fi
        result="${result}{\"role\":\"${role}\"}"
        idx=$((idx + 1))
    done
    echo "$result"
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
    -a)
    ADD_ROLES=$2
    shift 2
    ;;
    -d)
    DELETE_ROLES=$2
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
  usage
  exit 1;
fi

case $REALM in
  keyuser | keyadmin )
    ;;
  git )
    BRANCH="";
    ;;
  * )
    echo "Need to set a realm. Valid entries are keyuser, keyadmin or git"
    usage
    exit 1
    ;;
esac

if [[ -z "${CREDS}" ]]; then
    CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ "${CREDS}" = ":" ]] ; then
  read -p "Enter your user name: " CREDS_USER
  read -p "Enter your password: " CREDS_PASS
  CREDS="${CREDS_USER}:${CREDS_PASS}"
fi

if [[ ! -z $BRANCH ]]; then
  BRANCH="?ref=$(urlencode ${BRANCH})"
fi

tfile=$(mktemp)
USER_DATA=$(curl -s --user "${CREDS}" -D $tfile ${HOST}/users/${REALM}/${USER} -o -)
err=$?
if [[ $err -ne 0 ]]; then
  echo "Error when fetching key"
  rm -f $tfile
  exit 1
fi

if [[ ! -z "${ROLES}" ]]; then
    result=$(json_roles ${ROLES})
    USER_DATA=$(echo $USER_DATA | jq -c --argjson rr "[${result}]" '.roles = $rr')
fi

if [[ ! -z "${ADD_ROLES}" ]]; then
    result=$(json_roles ${ADD_ROLES})
    USER_DATA=$(echo $USER_DATA | jq -c --argjson rr "[${result}]" '.roles += $rr')
fi

if [[ ! -z "$DELETE_ROLES" ]]; then
    IFS=',' read -ra data <<< "$DELETE_ROLES"
    for role in "${data[@]}"; do
        USER_DATA=$(echo $USER_DATA | jq -c --arg rr "$role" 'del(.roles[] | select(.role == $rr))')
    done
fi

if [[ ! -z "${PASSWD}" ]]; then
   USER_DATA=$(echo $USER_DATA | jq -c --arg password "${PASSWD}" '.basicPassword = $password')
fi

etag=$(cat $tfile | grep -i 'etag:' | awk '{printf $2}' | tr -d '\r')

curl -s -H "If-match: $etag" \
-H 'Content-Type: application/json' \
--user ${CREDS} -X PUT \
-d "${USER_DATA}" \
${HOST}/users/${REALM}/${USER}${BRANCH}

rm -f $tfile
