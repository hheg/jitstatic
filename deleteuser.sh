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

