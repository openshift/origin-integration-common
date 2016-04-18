#!/bin/bash

set -e

# determine whether DNS resolves the master successfully
function os::int::post::check_master_accessible() {
  local master_ca="$1" master_url="$2" output
  if output=$(curl -sSI --stderr - --connect-timeout 2 --cacert "$master_ca" "$master_url"); then
    echo "ok"
    return 0
  fi
  local rc=$?
  echo "unable to access master url $master_url"
  case $rc in # if curl's message needs interpretation
  51)
	  echo "The master server cert was not valid for ${master_url}."
	  echo "You most likely need to regenerate the master server cert;"
	  echo "or you may need to address the master differently."
	  ;;
  60)
	  echo "The master CA cert did not validate the master."
	  echo "If you have multiple masters, confirm their certs have the same CA."
	  ;;
  esac
  echo "See the error from 'curl ${master_url}' below for details:"
  echo -e "$output"
  return 1
}

# determine whether cert (assumed to be from deployer secret) has specified names
function os::int::post::cert_should_have_names() {
  local file="$1"; shift
  local names=( "$@" )
  local output name cn san missing

  if ! output=$(openssl x509 -in "$file" -noout -text 2>&1); then
    echo "Could not extract certificate from $file. The error was:"
    echo "$output"
    return 1
  fi
  if san=$(echo -e "$output" | grep -A 1 "Subject Alternative Name:"); then
    missing=false
    for name in "${names[@]}"; do
      [[ "$san" != *DNS:$name* ]] && missing=true
    done
    if [[ $missing = true ]]; then
      echo "The supplied $file certificate is required to contain the following name(s) in the Subject Alternative Name field:"
      echo $@
      echo "Instead the certificate has:"
      echo -e "$san"
      echo "Please supply a correct certificate or omit it to allow the deployer to generate it."
      return 1
    fi
  elif [[ $# -gt 1 ]]; then
    echo "The supplied $file certificate is required to have a Subject Alternative Name field containing these names:"
    echo $@
    echo "The certificate does not have the Subject Alternative Name field."
    echo "Please supply a correct certificate or omit it to allow the deployer to generate it."
    return 1
  else
    cn=$(echo -e "$output" | grep "Subject:")
    if [[ "$cn" != *CN=$1* ]]; then
      echo "The supplied $file certificate does not contain $1 in the Subject field and lacks a Subject Alternative Name field."
      echo "Please supply a correct certificate or omit it to allow the deployer to generate it."
      return 1
    fi
  fi
  return 0
}
