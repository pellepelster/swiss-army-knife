//! the one place deployments are listed, planned, created, deployed and destroyed

use super::*;

/// the cloud-init of the server: it installs the derived host key, and no other, before sshd
/// first starts, so sakctl verifies the first connection against it
pub fn user_data(host_key: &DerivedKey) -> String {
    let private = host_key
        .private_key()
        .lines()
        .map(|line| format!("    {line}"))
        .collect::<Vec<_>>()
        .join("\n");
    format!(
        "#cloud-config\nssh_deletekeys: true\nssh_genkeytypes: []\nssh_keys:\n  ed25519_private: |\n{private}\n  ed25519_public: {public}\n",
        public = host_key.public_key()
    )
}

/// a deployment checked and ready to plan and apply: where it goes and what it consists of. The
/// command line plans and applies with the same one, so it reads the project one time. The window
/// prepares again for the apply, because the user can wait a long time on the plan
pub struct Prepared {
    pub(super) provider: ProviderType,
    pub(super) runtime: HetznerProviderRuntime,
    pub(super) spec: DeploymentSpec,
    /// the dns records of the deployment that the apply removes
    pub(super) stale_records: Vec<HetznerDnsRecordLookup>,
    pub(super) client_key: DerivedKey,
    pub(super) host_key: DerivedKey,
    /// the public half of the api key; the backend checks a signed request with it
    pub(super) api_public_key: String,
    /// the admin of a multi user deployment; none for single user and on a destroy
    pub(super) admin_email: Option<String>,
    /// the argon2id hash of the admin password (the deployment password)
    pub(super) admin_password_hash: String,
    /// the mail server of the e-mail login; none for the password login only
    pub(super) smtp: Option<Smtp>,
    /// where the certificates come from; it sets what sakctl trusts when it waits for the api
    pub(super) acme_ca: AcmeCa,
}

impl Prepared {
    /// what the playbook gets; the provisioner adds the addresses of the server and the device of
    /// the volume as it finds them
    pub(super) fn provision_run(&self) -> ProvisionRun {
        ProvisionRun {
            name: self.spec.name.clone(),
            dns_mode: self.spec.dns_mode,
            // a create or deploy has one, prepare checked it
            root_domain: self.spec.root_domain.clone().unwrap_or_default(),
            api_public_key: self.api_public_key.clone(),
            host: String::new(),
            host_ipv6: None,
            client_key: self.client_key.clone(),
            host_key: self.host_key.clone(),
            storage_device: String::new(),
            // a create or deploy has them, prepare checked them
            admin_email: self.admin_email.clone().unwrap_or_default(),
            smtp: self.smtp.clone(),
            admin_password_hash: self.admin_password_hash.clone(),
            acme_ca: self.acme_ca.directory().map(String::from),
        }
    }

    /// the resources a create or deploy plans and applies: those of the spec and, after the
    /// server and the volume, the roles of the playbook on the server (`core::ansible_roles`). A
    /// destroy has no roles, they go with the server
    pub(super) fn registry(
        &self,
        intent: Intent,
        provisioner: impl FnOnce(ProvisionRun) -> AnsibleRolesProvisioner,
    ) -> Result<sak_cloud::api::Registry, String> {
        let mut registry = self
            .spec
            .registry(&self.runtime)
            .map_err(|error| error.to_string())?;
        if intent != Intent::Destroy {
            registry.register::<AnsibleRoles, AnsibleRolesProvisioner>(Arc::new(provisioner(
                self.provision_run(),
            )));
            registry
                .add(AnsibleRoles {
                    name: self.spec.resource_name(),
                    server: self.spec.server().as_lookup(),
                    volume: self.spec.data_volume().as_lookup(),
                })
                .map_err(|error| error.to_string())?;
        }
        Ok(registry)
    }
}

pub(super) fn failed(provider: ProviderType, error: impl ToString) -> String {
    message("shared.deployments.failed", provider, error)
}

/// a failure of the provider in a plan or an apply. A resource of a declared name that belongs
/// to someone else, and a resource that must stay but would be created anew, each have an exit
/// code of their own; nothing of them was changed
pub(super) fn cloud_failure(
    key: &str,
    provider: ProviderType,
    error: sak_cloud::api::Error,
) -> WizardError {
    match error {
        sak_cloud::api::Error::NotOwned { .. } => WizardError::new(
            t!("shared.deployments.not_owned", error = error.to_string()),
            EXIT_NOT_OWNED,
        ),
        sak_cloud::api::Error::RecreateRefused { .. } => WizardError::new(
            t!(
                "shared.deployments.recreate_refused",
                error = error.to_string()
            ),
            EXIT_RECREATE_REFUSED,
        ),
        error => message(key, provider, error).into(),
    }
}

/// a message about the provider: `key` names what failed (list, plan, apply)
pub(super) fn message(key: &str, provider: ProviderType, error: impl ToString) -> String {
    t!(key, provider = provider.name(), error = error.to_string()).to_string()
}

/// manages the sak deployments at the providers: finds them by the name label, diffs them
/// against the resource group of a deployment, plans and applies a create, deploy or destroy.
/// Every call gets the provider tokens, so a token added later counts immediately
#[derive(Default)]
pub struct DeploymentManager;

impl DeploymentManager {
    /// the deployments at every provider with a token, by provider and name
    pub async fn list(
        &self,
        tokens: Vec<(ProviderType, String)>,
    ) -> Result<Vec<DeploymentResponse>, String> {
        let mut deployments = Vec::new();
        for (provider, token) in tokens {
            match provider {
                ProviderType::Hetzner => {
                    let api = Arc::new(HetznerApi::new(&token));
                    let runtime = HetznerProviderRuntime::from_api(
                        api.clone(),
                        Location::nbg1,
                        ServerType::cx23,
                    );
                    let discovered = discover(&api)
                        .await
                        .map_err(|error| failed(provider, error))?;
                    for deployment in &discovered {
                        // a deployment that cannot be compared says why, the others still show
                        let state = state(&runtime, deployment).await;
                        deployments.push(DeploymentResponse::of(provider, state, deployment));
                    }
                }
            }
        }
        Ok(deployments)
    }

    /// the check of a deployment, as the page of a deployment shows it: a deploy of the deployment
    /// as it is, completed from it as the command line does, and its plan. The deploy is what an
    /// apply of the check applies
    pub async fn check(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
        location: Location,
        container: &ContainerRuntime,
    ) -> Result<(NewDeployment, PlanResponse), WizardError> {
        let deployment = self
            .completed(
                tokens.clone(),
                NewDeployment::as_it_is(name, password, location),
                "",
            )
            .await?;
        let plan = self.plan(tokens, &deployment, container).await?;
        Ok((deployment, plan))
    }

    /// a deploy with what is not given taken from the deployment, for the window and the command
    /// line alike: its user mode and, for multi user, the admin and the mail server (`detected`,
    /// `complete`). A deploy that gives them all is not completed; a deployment that cannot be
    /// read (no provider, not found, a wrong password) is the error
    pub async fn completed(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: NewDeployment,
        smtp_password: &str,
    ) -> Result<NewDeployment, WizardError> {
        let incomplete = deployment.user_mode != Some(UserMode::Single)
            && (deployment.user_mode.is_none()
                || deployment.smtp.is_none()
                || deployment.admin_email.is_none());
        if !incomplete {
            return Ok(deployment);
        }
        let detected = self
            .detected(tokens, &deployment.name, &deployment.password)
            .await?;
        completed_from(deployment, &detected, smtp_password)
    }

    /// the deployment `name` at the hetzner project of `tokens`, its name checked first: a wrong
    /// name is a usage error (`EXIT_USAGE`)
    pub(super) async fn find(
        &self,
        tokens: &[(ProviderType, String)],
        name: &str,
    ) -> Result<DiscoveredDeployment, WizardError> {
        let name = checked_name(name)?;
        let Some((provider, token)) = tokens
            .iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string().into());
        };
        discover(&HetznerApi::new(token))
            .await
            .map_err(|error| failed(*provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name)
            .ok_or_else(|| {
                t!("shared.deployments.not_found", name = name)
                    .to_string()
                    .into()
            })
    }

    /// the settings a deploy can take from an existing deployment, for the window and the command
    /// line: the user mode on its labels and, for multi user, the admin and the mail server of its
    /// running configuration (read with a request signed with the api key). A deployment without
    /// the label is multi user when its backend has a mail server. A deployment that does not
    /// answer gives only its label
    pub async fn detected(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<Detected, WizardError> {
        let deployment = self.find(&tokens, name).await?;
        let label = deployment
            .user_mode()
            .and_then(|mode| UserMode::parse(&mode));
        // a single user deployment has no more settings
        let running = match label {
            Some(UserMode::Single) => Ok(None),
            _ => running_config_of(&deployment, password).await.map(Some),
        };
        Ok(match running {
            Ok(running) => detect(label, running.as_ref()),
            Err(error) => Detected {
                unread: Some(error),
                ..detect(label, None)
            },
        })
    }

    /// whether `password` is the one of the deployment `name`: checked against the password
    /// hashes on its resources, or its ssh key; the window asks it while the password is typed
    pub async fn check_password(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<(), WizardError> {
        let deployment = self.find(&tokens, name).await?;
        Ok(verify_password(&deployment, &deployment.name, password)?)
    }

    /// the server of the deployment `name` and the keys of `password` to reach it, the password
    /// checked against the deployment first
    pub async fn ssh_target(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<crate::core::ssh::SshTarget, WizardError> {
        let deployment = self.find(&tokens, name).await?;
        let name = deployment.name.as_str();
        verify_password(&deployment, name, password)?;
        let server = deployment
            .server
            .as_ref()
            .ok_or_else(|| t!("cli.ssh_no_server", name = name).to_string())?;
        Ok(crate::core::ssh::SshTarget {
            host: server
                .public_ipv4
                .clone()
                .ok_or_else(|| t!("shared.provision.no_address", name = name).to_string())?,
            host_ipv6: server
                .public_ipv6
                .clone()
                .map(|network| first_address(&network)),
            client_key: crate::core::keys::derive_key(name, password)?,
            host_key: crate::core::keys::derive_host_key(name, password)?,
        })
    }

    /// where the deployment `name` answers its users (`Endpoint`), the password checked first
    pub async fn public_endpoint(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<Endpoint, WizardError> {
        let deployment = self.find(&tokens, name).await?;
        Ok(endpoint(&deployment, password)?)
    }

    /// the api url and the running configuration of a deployment, read from its backend at
    /// `api.<base domain>` with a request signed with the api key. sakctl checks the password
    /// against the deployment first
    pub async fn running_config(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
        password: &str,
    ) -> Result<(String, crate::core::config::RunningConfig), WizardError> {
        let deployment = self.find(&tokens, name).await?;
        let endpoint = endpoint(&deployment, password)?;
        let config = crate::core::config::fetch(
            &endpoint.url,
            &endpoint.key,
            endpoint.trust,
            endpoint.address,
        )
        .await?;
        Ok((endpoint.url, config))
    }

    /// the dns zones of the first hetzner project, each with the dns modes the deployment
    /// `name` can use it in
    pub async fn root_domains(
        &self,
        tokens: Vec<(ProviderType, String)>,
        name: &str,
    ) -> Result<Vec<RootDomainResponse>, String> {
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string());
        };
        let runtime = HetznerProviderRuntime::from_api(
            Arc::new(HetznerApi::new(&token)),
            Location::nbg1,
            ServerType::cx23,
        );
        let domains = root_domains(&runtime)
            .await
            .map_err(|error| failed(provider, error))?;
        Ok(domains
            .iter()
            .map(|domain| root_domain_response(domain, name.trim()))
            .collect())
    }

    /// checks the name (free for a create, an existing deployment for a deploy), the password
    /// (long enough, the same two times, hard to guess for a create, the one of the deployment
    /// otherwise) and, but for a destroy, the root domain (a zone of the project that allows the
    /// dns mode). Then derives the keys and labels and lays out the resource group at the first
    /// hetzner project
    pub async fn prepare(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
    ) -> Result<Prepared, WizardError> {
        let NewDeployment {
            name,
            password,
            password_repeat,
            location,
            dns_mode,
            root_domain,
            intent,
            delete_data: _,
            user_mode,
            acme_ca,
            smtp,
            admin_email,
            force: _,
        } = deployment;
        let (location, intent) = (*location, *intent);
        let name = checked_name(name)?;
        // a location Hetzner added after this release can be read, but nothing is created in it; a
        // destroy needs no location
        if location == Location::unknown && intent != Intent::Destroy {
            return Err(WizardError::new(
                t!("shared.deployments.unknown_location", name = name),
                crate::core::error::EXIT_USAGE,
            ));
        }
        if password.chars().count() < MIN_PASSWORD_CHARS {
            return Err(t!(
                "shared.deployments.short_password",
                min = MIN_PASSWORD_CHARS
            )
            .to_string()
            .into());
        }
        if password != password_repeat {
            return Err(t!("shared.deployments.password_mismatch")
                .to_string()
                .into());
        }
        // a new password must be hard to guess: whoever reads the project can try guesses offline
        // against the label and the public keys of the deployment
        if intent == Intent::Create
            && let Some(hint) = crate::core::keys::weak_password(name, password)
        {
            return Err(t!("shared.deployments.weak_password", hint = hint)
                .to_string()
                .into());
        }
        let Some((provider, token)) = tokens
            .into_iter()
            .find(|(provider, _)| *provider == ProviderType::Hetzner)
        else {
            return Err(t!("shared.deployments.no_provider").to_string().into());
        };
        let api = Arc::new(HetznerApi::new(&token));
        let existing = discover(&api)
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name);
        // the user mode given, else the one on the deployment (a deploy keeps it)
        let user_mode = user_mode.or_else(|| {
            existing
                .as_ref()
                .and_then(DiscoveredDeployment::user_mode)
                .and_then(|mode| UserMode::parse(&mode))
        });
        // the acme ca given, else the one of the deployment, else production
        let acme_ca = acme_ca
            .or_else(|| {
                existing
                    .as_ref()
                    .and_then(DiscoveredDeployment::acme_ca)
                    .and_then(|ca| AcmeCa::parse(&ca))
            })
            .unwrap_or_default();
        // multi user needs the mail server and the address of the admin, single user uses neither
        let (smtp, admin_email) = match (intent, user_mode) {
            (Intent::Destroy, _) => (None, None),
            (_, None) => return Err(t!("shared.deployments.no_user_mode").to_string().into()),
            (_, Some(UserMode::Single)) => (None, None),
            (_, Some(UserMode::Multi)) => {
                let smtp = smtp
                    .clone()
                    .ok_or_else(|| t!("shared.deployments.invalid_smtp_host").to_string())?;
                check_smtp(&smtp)?;
                let admin_email = admin_email
                    .as_deref()
                    .map(str::trim)
                    .filter(|address| is_address(address))
                    .map(str::to_lowercase)
                    .ok_or_else(|| t!("shared.deployments.invalid_admin_email").to_string())?;
                (Some(smtp), Some(admin_email))
            }
        };
        let (dns_mode, root_domain) = resolve_dns(
            name,
            intent,
            *dns_mode,
            root_domain.as_deref(),
            existing.as_ref().and_then(DiscoveredDeployment::dns_mode),
            existing
                .as_ref()
                .and_then(DiscoveredDeployment::root_domain),
        )?;
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        // one read of the zones gives the root domains, the records of the deployment and the
        // records in the way
        let listings = runtime
            .dns_zones()
            .list_all()
            .await
            .map_err(|error| failed(provider, error))?;
        let records = deployment_records(&listings, name);
        let location = match (intent, existing) {
            (Intent::Create, Some(_)) => {
                return Err(t!("shared.deployments.name_taken", name = name)
                    .to_string()
                    .into());
            }
            (Intent::Create, None) => location,
            (Intent::Deploy, None) => {
                return Err(t!("shared.deployments.not_found", name = name)
                    .to_string()
                    .into());
            }
            // destroyed already, maybe with records left: check the password against them. Without
            // records nothing of the deployment is left to change, the plan stops at a resource of
            // someone else with its name
            (Intent::Destroy, None) => {
                if !records.is_empty() {
                    let hashes: Vec<String> = records
                        .iter()
                        .filter_map(|record| record.labels.get(PASSWORD_HASH_LABEL).cloned())
                        .collect();
                    check_password(&hashes, None, name, password)?;
                }
                location
            }
            (Intent::Deploy | Intent::Destroy, Some(existing)) => {
                verify_password(&existing, name, password)?;
                // the location of the deployment: its server, volume and addresses never move, so
                // the given location counts only when none of them exists
                existing
                    .location()
                    .map_err(|resources| {
                        t!(
                            "shared.deployments.mixed_locations",
                            name = name,
                            resources = resources
                        )
                        .to_string()
                    })?
                    .unwrap_or(location)
            }
        };
        let runtime =
            HetznerProviderRuntime::from_api(api.clone(), location, server_type_for(location));
        let root_domain = match intent {
            Intent::Destroy => None,
            Intent::Create | Intent::Deploy => {
                let domains: Vec<RootDomain> = listings.iter().map(RootDomain::of).collect();
                Some(check_root_domain(
                    &domains,
                    root_domain.as_deref(),
                    name,
                    dns_mode,
                )?)
            }
        };

        let client_key = crate::core::keys::derive_key(name, password)?;
        let host_key = crate::core::keys::derive_host_key(name, password)?;
        let public_key = client_key.public_key();
        let password_hash = crate::core::keys::password_hash(name, password)?;
        let api_public_key = crate::core::keys::api_key(name, password)?.public_key();
        let mut spec = DeploymentSpec::new(
            name,
            &public_key,
            runtime.default_location,
            runtime.default_server_type,
        )
        .map_err(|error| error.to_string())?
        .with_dns_mode(dns_mode)
        .with_password_hash(&password_hash);
        if let Some(root_domain) = &root_domain {
            spec = spec.with_root_domain(root_domain);
        }
        // the user mode on every resource, so a deploy keeps it
        if intent != Intent::Destroy
            && let Some(user_mode) = user_mode
        {
            spec = spec.with_user_mode(user_mode.as_str());
        }
        if intent != Intent::Destroy {
            spec = spec
                .with_acme_ca(acme_ca.as_str())
                .with_version(crate::core::VERSION);
        }
        spec.user_data = user_data(&host_key);
        let stale_records = stale_records(&records, &spec.dns_records(), &spec.identity());
        // a record of another owner, of any type, on a name of the deployment stops the apply
        // here, before anything is created
        let in_the_way = foreign_records(&listings, &spec);
        if intent != Intent::Destroy && !in_the_way.is_empty() {
            return Err(WizardError::new(
                t!(
                    "shared.deployments.not_owned",
                    error = t!(
                        "shared.deployments.records_in_the_way",
                        records = in_the_way.join(", ")
                    )
                ),
                EXIT_NOT_OWNED,
            ));
        }
        Ok(Prepared {
            provider,
            runtime,
            spec,
            stale_records,
            client_key,
            host_key,
            api_public_key,
            admin_email,
            admin_password_hash: crate::core::keys::admin_password_hash(name, password)?,
            smtp,
            acme_ca,
        })
    }

    /// prepares the deployment and plans it: its resources diffed against the project, the roles
    /// of the server against its markers, read through ssh in the image of the tools
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
    ) -> Result<PlanResponse, WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        self.plan_prepared(&prepared, deployment, container).await
    }

    /// the plan of a deployment prepared already
    pub async fn plan_prepared(
        &self,
        prepared: &Prepared,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
    ) -> Result<PlanResponse, WizardError> {
        let volume = prepared.spec.data_volume().name;
        let mut registry = prepared.registry(deployment.intent, |run| AnsibleRolesProvisioner {
            run,
            runtime: container.clone(),
            force: deployment.force,
            server_changed: Default::default(),
            events: None,
            failure: Default::default(),
        })?;
        let diffs = registry.diff_all().await.map_err(|error| {
            cloud_failure("shared.deployments.plan_failed", prepared.provider, error)
        })?;
        Ok(PlanResponse {
            name: prepared.spec.name.clone(),
            public_key: prepared.spec.public_key.clone(),
            resources: diffs
                .iter()
                .map(|diff| {
                    let keep = deployment.intent == Intent::Destroy
                        && !deployment.delete_data
                        && diff.resource.name == volume;
                    let provisioning = is_provisioning(&diff.resource);
                    PlannedResource {
                        resource: diff.resource.log_text.clone(),
                        kind: match ResourceKind::of(&diff.resource) {
                            Some(kind) => key(&kind),
                            None if provisioning => "provisioning".to_string(),
                            None => String::new(),
                        },
                        name: diff.resource.name.clone(),
                        resource_status: ResourceStatus::of(diff.status),
                        // the playbook runs when a role is not up to date, an up to date one only
                        // on a forced deploy
                        action: if provisioning {
                            Some(
                                if diff.status == DiffStatus::UpToDate && !deployment.force {
                                    PlanAction::RunsWhenForced
                                } else {
                                    PlanAction::WillRun
                                },
                            )
                        } else {
                            action(
                                deployment.intent,
                                diff.status,
                                diff.needs_recreate(),
                                keep,
                                false,
                            )
                        },
                        working: working(
                            deployment.intent,
                            diff.status,
                            diff.needs_recreate(),
                            false,
                        ),
                        status: diff.status,
                        keep,
                        // a destroy removes everything, so the changes are of no interest
                        changes: match deployment.intent {
                            Intent::Destroy => Vec::new(),
                            Intent::Create | Intent::Deploy => diff
                                .changes
                                .iter()
                                .map(|change| change.log_text())
                                .collect(),
                        },
                    }
                })
                .chain(prepared.stale_records.iter().map(|record| PlannedResource {
                    resource: record.log_text(),
                    kind: key(&ResourceKind::DnsRecord),
                    name: record.resource_ref().name,
                    resource_status: ResourceStatus::UpToDate,
                    action: action(deployment.intent, DiffStatus::UpToDate, false, false, true),
                    working: working(deployment.intent, DiffStatus::UpToDate, false, true),
                    status: DiffStatus::UpToDate,
                    keep: false,
                    changes: Vec::new(),
                }))
                .collect(),
        })
    }

    /// prepares the deployment and applies it. A create or deploy applies the resources in the
    /// order of their dependencies, the roles of the playbook after the server and the volume
    /// (`core::ansible_roles`), then removes the dns records of an old dns mode or root domain.
    /// After a provisioning sakctl waits for the api and sets the version label of the server.
    /// Each resource, line and task goes to `on_event`. What is up-to-date stays as it is,
    /// so the next run finishes a run that failed. A destroy removes the dns records of the
    /// deployment, then every resource but the data volume (also that with `delete_data`),
    /// dependents first
    pub async fn create(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), WizardError> {
        let prepared = self.prepare(tokens, deployment).await?;
        self.apply(prepared, deployment, container, on_event).await
    }

    /// applies a deployment prepared already, as `create` does
    pub async fn apply(
        &self,
        prepared: Prepared,
        deployment: &NewDeployment,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> Result<(), WizardError> {
        if deployment.intent == Intent::Destroy {
            self.remove_records(&prepared, on_event)
                .await
                .map_err(|error| {
                    cloud_failure(
                        "shared.deployments.destroy_failed",
                        prepared.provider,
                        error,
                    )
                })?;
            let mut registry = prepared
                .spec
                .destroy_registry(&prepared.runtime, deployment.delete_data)
                .map_err(|error| error.to_string())?;
            let mut report = |event: ApplyEvent| {
                let event = match event {
                    ApplyEvent::Started(resource) => CreateEvent::Started {
                        resource: resource.log_text,
                    },
                    ApplyEvent::Destroyed(resource, gone) => CreateEvent::Destroyed {
                        resource: resource.log_text,
                        text: destroyed_key(gone),
                    },
                    ApplyEvent::Applied(_) => return,
                };
                on_event(event);
            };
            registry
                .destroy_all_with(&mut report)
                .await
                .map_err(|error| {
                    cloud_failure(
                        "shared.deployments.destroy_failed",
                        prepared.provider,
                        error,
                    )
                })?;
            return Ok(());
        }
        // the events of the resources and of the playbook go through one channel to `on_event`
        // while the registry applies; it closes when the registry and its report are gone
        let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel::<CreateEvent>();
        let server_changed = Arc::new(AtomicBool::new(false));
        let failure = Arc::new(Mutex::new(None));
        let mut registry = prepared.registry(deployment.intent, |run| AnsibleRolesProvisioner {
            run,
            runtime: container.clone(),
            force: deployment.force,
            server_changed: server_changed.clone(),
            events: Some(sender.clone()),
            failure: failure.clone(),
        })?;
        let server_name = prepared.spec.resource_name();
        let provisioned = Arc::new(AtomicBool::new(false));
        let apply = {
            let provisioned = provisioned.clone();
            async move {
                // a server created, created anew or changed runs the roles again, whatever its
                // markers say
                let mut report = |event: ApplyEvent| {
                    let event = match event {
                        ApplyEvent::Started(resource) => CreateEvent::Started {
                            resource: resource.log_text,
                        },
                        ApplyEvent::Applied(result) => {
                            if ResourceKind::of(&result.resource) == Some(ResourceKind::Server)
                                && result.resource.name == server_name
                                && result.applied != Applied::Unchanged
                            {
                                server_changed.store(true, Ordering::SeqCst);
                            }
                            if is_provisioning(&result.resource)
                                && result.applied != Applied::Unchanged
                            {
                                provisioned.store(true, Ordering::SeqCst);
                            }
                            CreateEvent::Applied {
                                resource: result.resource.log_text,
                                text: applied_key(result.applied),
                            }
                        }
                        ApplyEvent::Destroyed(..) => return,
                    };
                    let _ = sender.send(event);
                };
                let result = registry.apply_all_with(&mut report).await;
                drop(registry);
                result
            }
        };
        let forward = async {
            while let Some(event) = receiver.recv().await {
                on_event(event);
            }
        };
        let (result, ()) = tokio::join!(apply, forward);
        if let Err(error) = result {
            // a failed playbook says what failed in its own words, a failure at the provider with
            // its name
            if let Some(message) = failure.lock().ok().and_then(|mut failure| failure.take()) {
                return Err(message.into());
            }
            return Err(cloud_failure(
                "shared.deployments.create_failed",
                prepared.provider,
                error,
            ));
        }
        self.remove_records(&prepared, on_event)
            .await
            .map_err(|error| {
                cloud_failure("shared.deployments.create_failed", prepared.provider, error)
            })?;
        if !provisioned.load(Ordering::SeqCst) {
            return Ok(());
        }

        // the server just provisioned: the wait for the api goes to its address
        let server = discover(&prepared.runtime.api)
            .await
            .map_err(|error| failed(prepared.provider, error))?
            .into_iter()
            .find(|found| found.name == prepared.spec.name)
            .and_then(|found| found.server)
            .ok_or_else(|| {
                t!("shared.deployments.not_found", name = prepared.spec.name).to_string()
            })?;
        let address = server
            .public_ipv4
            .as_deref()
            .and_then(|host| host.parse::<std::net::IpAddr>().ok());
        // done when the deployment answers as its users reach it: through its ingress, with its
        // certificate. A backend that did not start or a missing certificate stops the apply here
        if let Some(root_domain) = &prepared.spec.root_domain {
            let api = crate::core::config::api_url(&sak_cloud::hetzner::deployment::base_domain(
                &prepared.spec.name,
                prepared.spec.dns_mode,
                root_domain,
            ));
            on_event(CreateEvent::Provisioning {
                message: t!("shared.deployments.waiting_for_api", url = api.as_str()).to_string(),
            });
            crate::core::config::wait_for_api(
                &api,
                address,
                prepared.acme_ca.trust(),
                crate::core::config::API_WAIT,
                std::time::Duration::from_secs(5),
            )
            .await?;
        }
        // the server runs the images of this version now, its version label says so
        set_server_labels(
            &prepared.runtime.api,
            server.id,
            &[(VERSION_LABEL, Some(crate::core::VERSION))],
        )
        .await
        .map_err(|error| failed(prepared.provider, error))?;
        Ok(())
    }

    /// removes the stale dns records of the deployment and reports each when it starts and when
    /// it is done
    pub(super) async fn remove_records(
        &self,
        prepared: &Prepared,
        on_event: &mut (dyn FnMut(CreateEvent) + Send),
    ) -> sak_cloud::api::Result<()> {
        for record in &prepared.stale_records {
            on_event(CreateEvent::Started {
                resource: record.log_text(),
            });
        }
        remove_records(
            &prepared.runtime,
            &prepared.stale_records,
            &mut |record, gone| {
                on_event(CreateEvent::Destroyed {
                    resource: record.log_text(),
                    text: destroyed_key(gone),
                })
            },
        )
        .await
    }
}
