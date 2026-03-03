# Module Signing Guide

Ignition modules must be signed with a code-signing certificate before they can be installed on a production gateway. This guide explains how to create the signing certificates and configure them for both local development and CI/CD.

## Overview

Signing requires two files:
- **Keystore** (`keystore.jks`) — A Java KeyStore containing your private key and certificate
- **Certificate chain** (`cert.p7b`) — A PKCS#7 file containing the full certificate chain

These are used by the Ignition SDK Gradle plugin (`io.ia.sdk.modl`) to sign the `.modl` file during the build.

## Step 1: Create the Signing Certificates

### Generate a Root CA and Module Signing Certificate

```bash
# 1. Create a directory for your certificates (this is git-ignored)
mkdir -p certificates && cd certificates

# 2. Generate a Root CA private key
openssl genrsa -out root-ca.key 4096

# 3. Create a self-signed Root CA certificate (valid for 10 years)
openssl req -x509 -new -nodes -key root-ca.key -sha256 -days 3650 \
  -out root-ca.pem \
  -subj "/C=BE/O=Factry/CN=Factry Modules Root CA"

# 4. Generate a module signing private key
openssl genrsa -out module-signing.key 4096

# 5. Create a Certificate Signing Request (CSR)
openssl req -new -key module-signing.key -out module-signing.csr \
  -subj "/C=BE/O=Factry/CN=Factry Module Signing"

# 6. Sign the CSR with the Root CA (valid for 5 years)
openssl x509 -req -in module-signing.csr \
  -CA root-ca.pem -CAkey root-ca.key -CAcreateserial \
  -out module-signing.pem -days 1825 -sha256

# 7. Create a PKCS#12 bundle (needed to import into Java KeyStore)
openssl pkcs12 -export -in module-signing.pem -inkey module-signing.key \
  -certfile root-ca.pem -out module-signing.pfx \
  -name "factry-modules" -password pass:YOUR_KEYSTORE_PASSWORD

# 8. Create the Java KeyStore from the PKCS#12 bundle
keytool -importkeystore \
  -srckeystore module-signing.pfx -srcstoretype PKCS12 -srcstorepass YOUR_KEYSTORE_PASSWORD \
  -destkeystore keystore.jks -deststoretype JKS -deststorepass YOUR_KEYSTORE_PASSWORD

# 9. Create the PKCS#7 certificate chain file
openssl crl2pkcs7 -nocrl -certfile module-signing.pem -certfile root-ca.pem \
  -out cert.p7b
```

Replace `YOUR_KEYSTORE_PASSWORD` with a strong password. Adjust the `-subj` fields (country, organization, common name) as needed.

### Verify the Keystore

```bash
# List entries in the keystore
keytool -list -keystore keystore.jks -storepass YOUR_KEYSTORE_PASSWORD

# Note the alias name shown (e.g., "factry-modules") — you'll need this later
```

### Final Certificate Files

After these steps you should have:

```
certificates/
  keystore.jks          # Java KeyStore (private key + certificate)
  cert.p7b              # PKCS#7 certificate chain
  root-ca.key           # Root CA private key (keep safe!)
  root-ca.pem           # Root CA certificate
  module-signing.key    # Module signing private key
  module-signing.pem    # Module signing certificate
  module-signing.pfx    # PKCS#12 bundle (intermediate file)
```

> **Important**: Keep `root-ca.key` and `module-signing.key` secure. They should never be committed to git.

## Step 2: Configure Local Signing

Create a `gradle.properties` file in the project root (this file is git-ignored):

```properties
ignition.signing.keystoreFile=certificates/keystore.jks
ignition.signing.keystorePassword=YOUR_KEYSTORE_PASSWORD
ignition.signing.certAlias=factry-modules
ignition.signing.certFile=certificates/cert.p7b
ignition.signing.certPassword=YOUR_KEYSTORE_PASSWORD
```

Then build:

```bash
./gradlew clean build
```

The signed module will be at `build/Factry-Historian.modl`.

To skip signing during development, pass the `-PskipSigning=true` flag:

```bash
./gradlew clean build -PskipSigning=true
```

## Step 3: Configure CI/CD Signing (GitHub Actions)

The CI/CD pipeline needs the same certificates. Store them as GitHub repository secrets.

### Encode the Certificate Files

```bash
# Encode keystore to base64 and copy to clipboard
base64 -i certificates/keystore.jks | pbcopy
# → Paste as GitHub secret: SIGNING_KEYSTORE_BASE64

# Encode certificate chain to base64 and copy to clipboard
base64 -i certificates/cert.p7b | pbcopy
# → Paste as GitHub secret: SIGNING_CERT_FILE_BASE64
```

### Add GitHub Repository Secrets

Go to **Settings → Secrets and variables → Actions → New repository secret** and add:

| Secret name | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | Base64-encoded contents of `keystore.jks` |
| `SIGNING_CERT_FILE_BASE64` | Base64-encoded contents of `cert.p7b` |
| `SIGNING_KEYSTORE_PASSWORD` | The keystore password |
| `SIGNING_CERT_ALIAS` | The certificate alias (e.g., `factry-modules`) |
| `SIGNING_CERT_PASSWORD` | The certificate password (usually same as keystore password) |

The `build.yaml` workflow will decode these secrets and pass them to Gradle automatically.

## Installing Signed Modules on Ignition

When you first install a signed module on an Ignition gateway:

1. Go to **Config → System → Modules**
2. Upload the `.modl` file
3. The gateway will prompt you to **accept the certificate** — this is your Root CA
4. Once accepted, all future module updates signed with the same certificate will be trusted automatically
