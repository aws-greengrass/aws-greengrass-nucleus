#!/bin/sh

set -e # Exit immediately if anything fails

# Decrypt the file
mkdir -p $HOME/secrets
# --batch to prevent interactive command --yes to assume "yes" for questions
gpg --quiet --batch --yes --decrypt --passphrase="$evergreen_test_iot_thing_private_key_secret_passphrase" \
--output $HOME/secrets/test_iot_thing_private.pem.key ./.github/scripts/test_iot_thing_private.pem.key.gpg

gpg --quiet --batch --yes --decrypt --passphrase="$evergreen_test_iot_thing_private_key_secret_passphrase" \
--output $HOME/secrets/test_iot_thing_certificate.pem.crt ./.github/scripts/test_iot_thing_certificate.pem.crt.gpg

#Download the Amazon Root CA
cd $HOME/secrets
curl -o AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem

