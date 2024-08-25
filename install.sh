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

if [[ -n $(command -v systemctl || echo '') ]]; then
  service_binary="systemctl"
  service_directory='/etc/systemd/system/'
  configuration_directory='/usr/share/kss/'
  log_directory=''
  service_file='kss.service'
elif [[ -n $(command -v launchctl || echo '') ]]; then
  service_binary="launchctl"
  service_directory='/Library/LaunchDaemons/'
  configuration_directory='/Library/Application Support/kss/'
  log_directory='/Library/Logs/'
  service_file='kss.plist'
else
  service_binary=''
  service_directory=''
  configuration_directory=''
  log_directory=''
  service_file='kss.service'
fi


PROGRAM_NAME=$(basename "$0")

function usageText {
  echo 'Usage: '"$PROGRAM_NAME"' [OPTIONS]'
  echo 'install latest kss release'
  echo ''
  echo 'Options:'
  echo '-h/--help                    prints this usage'
  echo '-t/--token                   github token to use to download executables'
  echo '-d/--directory[/usr/bin/]    directory for binary files'
  echo "-s/--service-directory[$service_directory]"
  echo '                             directory for service files'
  echo "-c/--configuration-directory[$configuration_directory]"
  echo '                             directory for configuration files'
  echo "-l/--log-directory[$log_directory]"
  echo '                             directory for log files (only used in MacOS)'
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
  -c|--configuration-directory)
    if [ "$value" ]; then
      configuration_directory="${value%/}/"
      shift
    else
      usage 'the --configuration-directory option needs an argument'
    fi
  ;;
  -l|--log-directory)
    if [ "$value" ]; then
      log_directory="${value%/}/"
      shift
    else
      usage 'the --log-directory option needs an argument'
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
  usage "Current user has no writer permissions for '${execution_directory}'. Please execute with a different user."
fi

if [ ! -d "${service_directory}" ]; then
  echo "'--service-directory' is set to '${service_directory}' which is no existing directory. Service won't be installed" >&2
  service_directory=''
elif [ ! -w "${service_directory}" ]; then
  echo "Current user has no writer permissions for '${service_directory}'. Service won't be installed" >&2
  service_directory=''
else
  if [ ! -d "${configuration_directory}" ]; then
    echo "'--configuration-directory' (${configuration_directory}) not found. Try to create it" >&2
    mkdir -p "${configuration_directory}"
  fi
  if [ ! -d "${log_directory}" ]; then
    echo "'--log-directory' (${log_directory}) not found. Try to create it" >&2
    mkdir -p "${log_directory}"
  fi
  if [ ! -w "${configuration_directory}" ]; then
    echo "Current user has no writer permissions for '${configuration_directory}'. Service won't be installed" >&2
    service_directory=''
  elif [ ! -w "${log_directory}" ]; then
    echo "Current user has no writer permissions for '${log_directory}'. Service won't be installed" >&2
    service_directory=''
  elif ! id "$service_user" >/dev/null 2>&1; then
    echo "User '$service_user' does not exist. Service won't be installed" >&2
    service_directory=''
  fi
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
  tar --extract --gunzip --file "${archive_name}" --to-stdout "scripting-host-release/service/$service_file" |\
   sed "s|{{DIRECTORY}}|${execution_directory}|g" | \
   sed "s|{{WORKING_DIRECTORY}}|${configuration_directory}|g" | \
   sed "s|{{LOG_DIRECTORY}}|${log_directory}|g" | \
   sed "s|{{USER}}|${service_user}|g" \
   > "$service_directory$service_file"
  case $service_binary in
  systemctl)
    systemctl enable kss
    echo 'The system service is enabled. Run'
    echo ''
    echo 'systemctl start kss'
    echo ''
    echo 'to start the service'
    ;;
  launchctl)
    echo 'The launch daemon is enabled. Run'
    echo ''
    echo "launchctl load $service_directory$service_file"
    echo ''
    echo 'to start the daemon'
    ;;
  esac

  tar --extract --gunzip --file "${archive_name}" --strip-components 2 --directory "${configuration_directory}" 'scripting-host-release/config/'
  echo "A sample configuration is in '${configuration_directory}'. Remove the '.sample' extension and edit it as you see fit."
fi

echo 'delete archive' >&2
rm "${archive_name}"
