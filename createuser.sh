# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at

# http://www.apache.org/licenses/LICENSE-2.0

# Unless required by applicable law or agreed to in writing,
# software distributed under the License is distributed on an
# "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
# KIND, either express or implied.  See the License for the
# specific language governing permissions and limitations
# under the License.

#!/bin/bash

USER=$1
PASSWD=$2
REALM=$3
BRANCH=$4
BASE=${5:-"http://localhost:8085"}

function usage () {
  echo "Usage: $0 <user> <password> <realm> <branch> [<base>]"
}

if [[ -z "${USER}" ]]; then
  echo "Need to set a user to be created"
  usage
  exit 1;
fi

if [[ -z "${PASSWD}" ]]; then
  echo "Need to set a password for the user to be created"
  usage
  exit 1;
fi

if [[ -z "$BRANCH" ]]; then
  BRANCH="refs/heads/master"
fi

ROLES=""
case $REALM in
  keyuser | keyadmin )
    ROLES="{\"role\":\"${USER}\"},{\"role\":\"config\"}"
    ;;
  git )
    BRANCH="";
    echo "Enter git user's roles."
    echo "Valid roles are:"
    echo "pull,push,forcepush,secrets"
    read -p "Enter them as a comma separated list: " input

    IFS=',' read -ra data <<< "$input"
    idx=0;
    for role in "${data[@]}"; do
      if [[ $idx > 0 && $idx < ${#data[@]} ]]; then
        ROLES="${ROLES},"
      fi
      ROLES="${ROLES}{\"role\":\"${role}\"}"
      idx=$((idx + 1))
    done
    ;;
  * )
    echo "Need to set a realm. Valid entries are keyuser, keyadmin or git"
    usage
    exit 1
    ;;
esac

REALM_USER="keyadmin"
if [[ $REALM -eq "git" ]]; then
  REALM_USER="git"
fi

read -p "Enter your $REALM_USER user name: " CREDS_USER
read -p "Enter your $REALM_USER password: " CREDS_PASS

if [[ ! -z $BRANCH ]]; then
  BRANCH="--data-urlencode \"ref=${BRANCH}\""
fi

curl -i -H 'Content-Type: application/json' \
--user "${CREDS_USER}:${CREDS_PASS}" -X POST \
$BRANCH \
-d "{\"roles\":[${ROLES}],\"basicPassword\":\"${PASSWD}\"}" \
-i $BASE/config/users/${REALM}/${USER}


