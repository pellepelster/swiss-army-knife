# the tools sakctl runs in a container instead of on the user's machine: ansible (its ansible-core
# the minor version of the root mise.toml) and the pelle_io.sak collection of ../ansible. The build
# context is the repository (mise run tools:image:build), Dockerfile.dockerignore lets only ansible/ in
FROM debian:13.7-slim

# renovate: datasource=pypi depName=ansible
ARG ANSIBLE_VERSION=14.5.0

# ssh and sshpass for the hosts ansible provisions, git and rsync for its modules
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates git openssh-client python3 python3-venv rsync sshpass \
    && rm -rf /var/lib/apt/lists/*

# jmespath and netaddr for the filters json_query and ipaddr
ENV PATH="/opt/ansible/bin:${PATH}"
RUN python3 -m venv /opt/ansible \
    && pip install --no-cache-dir "ansible==${ANSIBLE_VERSION}" jmespath netaddr

# the collection with the versions of its requirements.yml, in a path every user of the image reads
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
