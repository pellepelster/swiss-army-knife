# the tools sakctl runs in a container instead of on the user's machine: ansible, the version
# of the root mise.toml (ansible-core 2.18 of ansible 11), and the
# pelle_io.sak collection of ../ansible that provisions the server of a deployment. The build context
# is the repository (mise run tools:image:build), Dockerfile.dockerignore lets only ansible/ in
FROM debian:13.7-slim

ARG ANSIBLE_VERSION=11.13.0

# ssh and sshpass for the hosts ansible provisions, git and rsync for its modules
RUN apt-get update \
    && apt-get install --yes --no-install-recommends \
        ca-certificates git openssh-client python3 python3-venv rsync sshpass \
    && rm -rf /var/lib/apt/lists/*

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
