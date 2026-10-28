# the tools sak-wizard runs in a container instead of on the user's machine: terraform and ansible,
# the versions of the root mise.toml (terraform 1.17.0, ansible-core 2.18 of ansible 11), and the
# pelle_io.sak collection of ../ansible that provisions the server of a deployment. The build context
# is the repository (mise run wizard:image:build), Dockerfile.dockerignore lets only ansible/ in
FROM debian:13.7-slim

ARG TERRAFORM_VERSION=1.17.0
# from https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_SHA256SUMS
ARG TERRAFORM_SHA256_AMD64=c168cab7c637a8769fdb90089ddd19de701bc6ff8aca64dc2e2185fe46f59f67
ARG TERRAFORM_SHA256_ARM64=f32478134ffc6d710eec474bbd316652cdddc0f4e336973a3745e8d3a3309cdf
ARG ANSIBLE_VERSION=11.13.0
# set by buildkit, amd64 or arm64
ARG TARGETARCH

# ssh and sshpass for the hosts ansible provisions, git and rsync for its modules
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates curl git openssh-client python3 python3-venv rsync sshpass unzip \
    && rm -rf /var/lib/apt/lists/*

RUN case "${TARGETARCH}" in \
        amd64) sha256="${TERRAFORM_SHA256_AMD64}" ;; \
        arm64) sha256="${TERRAFORM_SHA256_ARM64}" ;; \
        *) echo "no terraform for ${TARGETARCH}" >&2; exit 1 ;; \
    esac \
    && curl --fail --silent --show-error --location --output /tmp/terraform.zip \
        "https://releases.hashicorp.com/terraform/${TERRAFORM_VERSION}/terraform_${TERRAFORM_VERSION}_linux_${TARGETARCH}.zip" \
    && echo "${sha256}  /tmp/terraform.zip" | sha256sum --check --strict \
    && unzip -q /tmp/terraform.zip terraform -d /usr/local/bin \
    && rm /tmp/terraform.zip

# the full ansible package, ansible-core with the community collections, and the python
# libraries their filters use (json_query, ipaddr), in a venv of its own
ENV PATH="/opt/ansible/bin:${PATH}"
RUN python3 -m venv /opt/ansible \
    && pip install --no-cache-dir "ansible==${ANSIBLE_VERSION}" jmespath netaddr

# the collection with the collections it depends on, the versions its requirements.yml pins,
# where every user of the image finds them
ENV ANSIBLE_COLLECTIONS_PATH=/opt/ansible/collections
COPY ansible /tmp/pelle_io.sak
RUN ansible-galaxy collection install --collections-path /opt/ansible/collections \
        -r /tmp/pelle_io.sak/requirements.yml \
    && ansible-galaxy collection install --collections-path /opt/ansible/collections /tmp/pelle_io.sak \
    && rm -rf /tmp/pelle_io.sak

RUN groupadd --gid 1000 sak \
    && useradd --uid 1000 --gid 1000 --create-home --shell /bin/bash sak

USER sak
WORKDIR /home/sak
