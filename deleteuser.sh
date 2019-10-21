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
REALM=$2
BRANCH=$3
BASE=${4:-"http://localhost:8085"}

function usage () {
  echo "Usage: $0 <user> <realm> <branch> [<base>]"
}

if [[ -z "${USER}" ]]; then
  echo "Need to set a user to be deleted"
  usage
  exit 1;
fi

if [[ -z "$BRANCH" ]]; then
  BRANCH="refs/heads/master"
fi

ROLES=""
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

read -p "Enter your git user name: " CREDS_USER
read -p "Enter your git password: " CREDS_PASS
read -p "Enter your domain: " CREDS_DOMAIN

if [[ ! -z $BRANCH ]]; then
  BRANCH="--data-urlencode \"ref=${BRANCH}\""
fi

curl -i \
-H "X-jitstatic-name: ${CREDS_USER}" \
-H 'X-jitstatic-message: Deleting user ${USER}' \
-H 'X-jitstatic-mail: ${CREDS_USER}@${CREDS_DOMAIN}' \
--user "${CREDS_USER}:${CREDS_PASS}" -X DELETE \
$BASE/config/users/${REALM}/${USER}

