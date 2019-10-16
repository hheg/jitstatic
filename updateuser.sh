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

REALM_USER="keyadmin"
if [[ $REALM -eq "git" ]]; then
  REALM_USER="git"
fi



read -p "Enter your git user name: " CREDS_USER
read -p "Enter your git password: " CREDS_PASS

if [[ ! -z $BRANCH ]]; then
  BRANCH="--data-urlencode \"ref=${BRANCH}\""
fi


USER_DATA=$(curl -i --user "${CREDS_USER}:${CREDS_PASS}" -s ${BASE}/config/users/${REALM}/${USER})
err=$?
if [[ $err -ne 0 ]]; then
  echo "Error when fetching key"
  exit 1
fi

JSON_USER=$(echo $USER_DATA | jq --arg password "${PASSWD}" '.basicPassword = $password ')

curl -i -H 'Content-Type: application/json' \
--user ${CREDS_USER}:${CREDS_PASS} -X PUT \
$BRANCH \
-d "${JSON_USER}" \
-i ${BASE}/config/users/${REALM}/${USER}

