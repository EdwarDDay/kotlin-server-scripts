#!/bin/bash

#
# Copyright 2024 Eduard Wolf
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#

set -eu
set -o pipefail

PROGRAM_NAME=$(basename "$0")

function usageText {
  echo 'Usage: '"${PROGRAM_NAME}"' [OPTIONS]'
  echo 'install latest kss release'
  echo ''
  echo 'Options:'
  echo '-h/--help                    prints this usage'
  echo '-t/--token                   github token to use to download executables'
  echo '-d/--directory[/usr/bin/]    directory for binary files'
  echo '-s/--service-directory[/etc/systemd/system/]'
  echo '                             directory for service files'
  echo '-u/--user[www-data]          user which runs the service process'
}

function usage {
  if [ -z "${1+''}" ]
  then
    usageText
    exit 0
  else
    echo "$1" >&2
    usageText >&2
    exit 1
  fi
}

authorization_token=''
execution_directory='/usr/bin/'
service_directory='/etc/systemd/system/'
service_user='www-data'

while [[ $# -gt 0 ]]; do
  key="$1"
  if [ -z "${2+''}" ]; then
    value=''
  else
    value="$2"
  fi
  case $key in
  -h|--help)
    usage
  ;;
  -t|--token)
    if [ "$value" ]; then
      authorization_token="$value"
      shift
    else
      usage 'the --token option needs an argument'
    fi
  ;;
  -d|--directory)
    if [ "$value" ]; then
      execution_directory="${value%/}/"
      shift
    else
      usage 'the --directory option needs an argument'
    fi
  ;;
  -s|--service-directory)
    if [ "$value" ]; then
      service_directory="${value%/}/"
      shift
    else
      usage 'the --service-directory option needs an argument'
    fi
  ;;
  -u|--user)
    if [ "$value" ]; then
      service_user="${value}"
      shift
    else
      usage 'the --user option needs an argument'
    fi
  ;;
  *)
    usage "unknown option ${key}"
  ;;
  esac
  shift
done

if [ ! -d "${execution_directory}" ]; then
  usage "'--directory' is set to '${execution_directory}' which is no existing directory. Please specify an existing directory."
elif [ ! -w "${execution_directory}" ]; then
  usage "Current user has no writer permissions to'${execution_directory}'. Please execute with a different user."
fi

if [ ! -d "${service_directory}" ]; then
  echo "'--service-directory' is set to '${service_directory}' which is no existing directory. Service won't be installed" >&2
  service_directory=''
elif [ ! -w "${execution_directory}" ]; then
  echo "Current user has no writer permissions to'${service_directory}'. Service won't be installed" >&2
  service_directory=''
fi

authorization_args=()
if [ "$authorization_token" ]; then
  authorization_args=(--header "Authorization: Bearer ${authorization_token}")
fi

github_args=(--location ${authorization_args[@]+"${authorization_args[@]}"} --header 'X-GitHub-Api-Version: 2022-11-28')

echo 'test repository access' >&2

auth_test_response_code="$(curl "${github_args[@]}" --header 'Accept: application/vnd.github+json' --head --silent \
  --write-out '%{response_code}\n' --output '/dev/null' \
  --url 'https://api.github.com/repos/EdwarDDay/kotlin-server-scripts')"

if [ "${auth_test_response_code}" != "200" ]; then
    usage "You don't have access to the repository without a GitHub access token."
fi

echo 'download latest release data' >&2

query="{\"query\": \"query { repository(owner: \\\"EdwarDDay\\\", name: \\\"kotlin-server-scripts\\\") { latestRelease { releaseAssets(name: \\\"scripting-host-release.tar.gz\\\", first: 1) { nodes { url } } } } }\" }"
response="$(curl --request POST "${github_args[@]}" --fail --silent --url 'https://api.github.com/graphql' --data "$query")"
response_tmp="${response%\"*}"
url="${response_tmp##*\"}"

echo 'download binary' >&2

archive_name='kss.tar.gz'

curl --progress-bar --url "${url}" --output "${archive_name}" --fail

echo 'extract binary' >&2

tar --extract --gunzip --file "${archive_name}" --strip-components 2 --directory "${execution_directory}" 'scripting-host-release/bin/'

if [[ -n "${service_directory}" ]]; then
  echo 'configure service' >&2
  # escape sed escape char
  service_user="${service_user/|/\\\|}"
  tar --extract --gunzip --file "${archive_name}" --to-stdout 'scripting-host-release/service/kss.service' | sed "s|{{DIRECTORY}}|${execution_directory}|g" | sed "s|{{USER}}|${service_user}|g" > "${service_directory}kss.service"
  systemctl enable kss
fi

echo 'delete archive' >&2
rm "${archive_name}"
