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
  echo 'use systemctl default values' >&2
  service_binary='systemctl'
  execution_directory='/usr/bin/'
  service_directory='/etc/systemd/system/'
  configuration_directory='/usr/share/kss/'
  log_directory=''
  service_file='kss.service'
  service_user='www-data'
elif [[ -n $(command -v launchctl || echo '') ]]; then
  echo 'use launchctl default values' >&2
  service_binary='launchctl'
  execution_directory='/usr/local/bin/'
  service_directory='/Library/LaunchDaemons/'
  configuration_directory='/Library/Application Support/kss/'
  log_directory='/Library/Logs/'
  service_file='kss.plist'
  service_user='_www'
else
  echo 'use empty default values' >&2
  service_binary=''
  execution_directory='/usr/bin/'
  service_directory=''
  configuration_directory=''
  log_directory=''
  service_file='kss.service'
  service_user=''
fi

if [[ -n $(command -v gh || echo '') ]]; then
  release_fetch_mode="gh"
else
  release_fetch_mode="unknown"
fi

PROGRAM_NAME=$(basename "$0")

function usageText {
  echo 'Usage: '"$PROGRAM_NAME"' [OPTIONS]'
  echo 'install latest kss release'
  echo ''
  echo 'Options:'
  echo '-h/--help                    prints this usage'
  echo '-t/--token                   github token to use to download executables'
  echo "-d/--directory[$execution_directory]"
  echo '                             directory for binary files'
  echo '-r/--release-fetch-mode      Options:'
  echo '                             gh - use authenticated Github commandline tool'
  echo '                             curl-authenticated - use curl authenticated with the token from the --token option'
  echo '                             curl - use curl without token - needs jq to parse rest response'
  echo '                             archive=<archive> - use tar archive as release'
  echo "-s/--service-directory[$service_directory]"
  echo '                             directory for service files'
  echo "-c/--configuration-directory[$configuration_directory]"
  echo '                             directory for configuration files'
  echo "-l/--log-directory[$log_directory]"
  echo '                             directory for log files (only used in MacOS)'
  echo "-u/--user[$service_user]          user which runs the service process"
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
release_fetch_mode_set='false'

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
      if [ "${release_fetch_mode_set}" == 'false' ] && [ "${release_fetch_mode}" != 'gh' ]; then
          release_fetch_mode='curl-authenticated'
      fi
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
  -r|--release-fetch-mode)
    release_fetch_mode_set='true'
    case $value in
    gh)
      if [[ -n $(command -v gh || echo '') ]]; then
        release_fetch_mode="gh"
      else
        usage '--release-fetch-mode set to gh but can'\''t find gh command'
      fi
      ;;
    curl-authenticated)
      release_fetch_mode="curl-authenticated"
      ;;
    curl)
      if [[ -n $(command -v jq || echo '') ]]; then
        release_fetch_mode="curl"
      else
        usage '--release-fetch-mode set to curl but can'\''t find jq command, which is needed to parse the REST API'
      fi
      ;;
    archive=*)
      release_fetch_mode="archive"
      archive_file="${value:8}"
      if [ ! -f "$archive_file" ]; then
        usage "couldn't find archive file '$archive_file'"
      fi
      ;;
    '')
      usage 'the --release-fetch-mode option needs an argument'
      ;;
    *)
      usage "unknown option ${value} for option --release-fetch-mode - please specify gh, curl-authenticated or curl"
    ;;
    esac
    shift
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

if [ "${release_fetch_mode}" == 'unknown' ] && [[ -n $(command -v jq || echo '') ]]; then
  release_fetch_mode='curl'
fi

if [ "${release_fetch_mode}" == 'curl-authenticated' ] && [ "${authorization_token}" == '' ]; then
  usage "'--release-fetch-mode' with option 'curl-authenticated' needs also '--token' to be specified"
fi

if [ "${release_fetch_mode}" == 'unknown' ]; then
  usage "please specify '--release-fetch-mode' as no default option was found"
fi

if [ "${release_fetch_mode}" == 'gh' ]; then
  if ! gh auth status >/dev/null && [ -z "${GH_TOKEN:-}" ]; then
    usage "gh commandline tool is not setup. Please login via 'gh auth login', specify 'GH_TOKEN' or use '--release-fetch-mode curl' or '--release-fetch-mode curl-authenticated --token <GH PAT token>'"
  fi
fi

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
elif [ -n "${service_binary}" ]; then
  if [ ! -d "${configuration_directory}" ]; then
    echo "'--configuration-directory' (${configuration_directory}) not found. Try to create it" >&2
    mkdir -p "${configuration_directory}"
  fi
  if [ -n "${log_directory}" ] && [ ! -d "${log_directory}" ]; then
    echo "'--log-directory' (${log_directory}) not found. Try to create it" >&2
    mkdir -p "${log_directory}"
  fi
  if [ ! -w "${configuration_directory}" ]; then
    echo "Current user has no writer permissions for '${configuration_directory}'. Service won't be installed" >&2
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

# shellcheck disable=SC2016
query='query ($user: String!, $repo: String!, $asset: String!) { repository(owner: $user, name: $repo) { latestRelease { releaseAssets(name: $asset, first: 1) { nodes { url } } } } }'

function extractUrlFromGraphqlQuery() {
    local response=$1
    if [[ -n $(command -v jq || echo '') ]]; then
      jq --raw-output '.data.repository.latestRelease.releaseAssets.nodes[0].url' <<< "$response"
    else
      local response_tmp="${response%\"*}"
      echo "${response_tmp##*\"}"
    fi
}

function downloadBinary() {
  echo 'download binary' >&2
  curl --progress-bar --header 'Accept: application/octet-stream' --url "${url}" --output "${archive_name}" --fail
}

archive_name='kss.tar.gz'

case "$release_fetch_mode" in
archive)
  echo "use release archive from file $archive_file" >&2
  cp "$archive_file" "$archive_name"
  ;;
gh)
  echo 'download latest release data via gh commandline tool' >&2
  response="$(gh api graphql -F 'user=EdwarDDay' -F 'repo=kotlin-server-scripts' -F 'asset=scripting-host-release.tar.gz' -f "query=$query")"
  url="$(extractUrlFromGraphqlQuery "$response")"
  downloadBinary
  ;;
curl-authenticated)
  echo 'download latest release data via curl and graphql api' >&2
  data="{
  \"query\":\"$query\",
  \"variables\":{
    \"user\":\"EdwarDDay\",
    \"repo\":\"kotlin-server-scripts\",
    \"asset\":\"scripting-host-release.tar.gz\"
  }
  }"
  response="$(curl --request POST "${github_args[@]}" --fail --silent --url 'https://api.github.com/graphql' --data "$data")"
  url="$(extractUrlFromGraphqlQuery "$response")"
  downloadBinary
  ;;
# curl
*)
  echo 'download latest release data via github rest endpoint and jq' >&2
  url="$(curl --request GET "${github_args[@]}" --fail --silent --url 'https://api.github.com/repos/EdwarDDay/kotlin-server-scripts/releases/latest' | jq --raw-output '.assets | map(select(.content_type == "application/gzip"))[0].url')"
  downloadBinary
esac

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
  echo "A sample logging file is in '${configuration_directory}'. Remove the '.sample' extension, reference it in the configuration and edit it as you see fit."
fi

echo 'delete archive' >&2
rm "${archive_name}"
