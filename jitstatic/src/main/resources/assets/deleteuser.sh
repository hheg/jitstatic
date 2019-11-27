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
CREDS_DOMAIN=$(hostname -f)
BRANCH=""

function usage () {
  echo "Usage: $0 -u <user> -r <realm> [-b <branch>] [-h <host>] [-cu <credential username> -cp <credential password> | -cr <credential username>:<credential password>] -cd <creds domain>"
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
    -cd)
    CREDS_DOMAIN=$2
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
  echo "Need to set a user to be deleted"
  usage
  exit 1
fi

case $REALM in
  keyuser | keyadmin )
    ;;
  git )
    BRANCH="";
    ;;
  * )
    echo "Need to set a realm. Valid entries are keyadmin or git"
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
  BRANCH="--data-urlencode \"ref=${BRANCH}\""
fi

curl -i -s \
-H "X-jitstatic-name: ${CREDS_USER}" \
-H 'X-jitstatic-message: Deleting user ${USER}' \
-H 'X-jitstatic-mail: ${CREDS_USER}@${CREDS_DOMAIN}' \
--user "${CREDS}" -X DELETE \
$HOST/users/${REALM}/${USER}

