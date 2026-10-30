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
/// command line plans and applies with the same one (`Planned`), so it reads the project and the
/// markers one time. The window prepares and plans again for the apply, because the user can wait
/// a long time on the check
pub struct Prepared {
    pub(super) provider: ProviderType,
    pub(super) runtime: HetznerProviderRuntime,
    pub(super) spec: DeploymentSpec,
    /// the dns records of the deployment that the apply removes
    pub(super) stale_records: Vec<HetznerDnsRecordLookup>,
    /// a create or deploy with what it provisions the server with, or a destroy
    pub(super) purpose: Purpose<Provisioning>,
    /// a second server, ssh key or firewall of the deployment: the plan shows it, nothing changes
    /// it
    pub(super) left_out: Vec<sak_cloud::hetzner::deployment::LeftOut>,
}

/// what a plan or an apply is for, typed: a create and a deploy with `P`, what they provision
/// with; a destroy with only whether the data volume goes too. `prepare` builds it from the
/// `Intent` of the request, one step at a time
pub(super) enum Purpose<P> {
    Create { provisioning: P },
    Deploy { provisioning: P },
    Destroy { delete_data: bool },
}

impl<P> Purpose<P> {
    pub(super) fn intent(&self) -> Intent {
        match self {
            Purpose::Create { .. } => Intent::Create,
            Purpose::Deploy { .. } => Intent::Deploy,
            Purpose::Destroy { .. } => Intent::Destroy,
        }
    }

    /// what a create or deploy provisions with; none for a destroy
    pub(super) fn provisioning(&self) -> Option<&P> {
        match self {
            Purpose::Create { provisioning } | Purpose::Deploy { provisioning } => {
                Some(provisioning)
            }
            Purpose::Destroy { .. } => None,
        }
    }

    /// the same purpose with what `next` makes of the provisioning; a destroy goes as it is
    pub(super) fn then<Q, E>(self, next: impl FnOnce(P) -> Result<Q, E>) -> Result<Purpose<Q>, E> {
        Ok(match self {
            Purpose::Create { provisioning } => Purpose::Create {
                provisioning: next(provisioning)?,
            },
            Purpose::Deploy { provisioning } => Purpose::Deploy {
                provisioning: next(provisioning)?,
            },
            Purpose::Destroy { delete_data } => Purpose::Destroy { delete_data },
        })
    }
}

/// who signs in to a create or deploy: one user with the password, or users with a link by
/// e-mail, which needs the mail server and the address of the admin
pub(super) enum Users {
    Single,
    Multi { smtp: Smtp, admin_email: String },
}

impl Users {
    pub(super) fn mode(&self) -> UserMode {
        match self {
            Users::Single => UserMode::Single,
            Users::Multi { .. } => UserMode::Multi,
        }
    }
}

/// who signs in to a create or deploy, from the user mode given or on the deployment: multi user
/// needs the mail server and the address of the admin, single user uses neither
pub(super) fn users(
    user_mode: Option<UserMode>,
    smtp: Option<&Smtp>,
    admin_email: Option<&str>,
) -> Result<Users, SakctlError> {
    match user_mode {
        None => Err(t!(
            "shared.deployments.no_user_mode",
            hint = crate::core::i18n::hint(crate::core::i18n::Hint::UserMode)
        )
        .to_string()
        .into()),
        Some(UserMode::Single) => Ok(Users::Single),
        Some(UserMode::Multi) => {
            let smtp = smtp.cloned().ok_or_else(|| {
                t!(
                    "shared.deployments.invalid_smtp_host",
                    hint = crate::core::i18n::hint(crate::core::i18n::Hint::SmtpHost)
                )
                .to_string()
            })?;
            check_smtp(&smtp)?;
            let admin_email = admin_email
                .map(str::trim)
                .filter(|address| is_address(address))
                .map(str::to_lowercase)
                .ok_or_else(|| {
                    t!(
                        "shared.deployments.invalid_admin_email",
                        hint = crate::core::i18n::hint(crate::core::i18n::Hint::AdminEmail)
                    )
                    .to_string()
                })?;
            Ok(Users::Multi { smtp, admin_email })
        }
    }
}

/// what a create or deploy provisions the server with, checked by `prepare`; a destroy has none
pub(super) struct Provisioning {
    /// the root domain, a zone of the project the deployment can use in its dns mode
    pub(super) root_domain: String,
    pub(super) users: Users,
    /// where the certificates come from; it sets what sakctl trusts when it waits for the api
    pub(super) acme_ca: AcmeCa,
    pub(super) client_key: DerivedKey,
    pub(super) host_key: DerivedKey,
    /// the public half of the api key; the backend checks a signed request with it
    pub(super) api_public_key: String,
    /// the argon2id hash of the admin password (the deployment password)
    pub(super) admin_password_hash: String,
    /// the playbook runs even when the provisioning is up to date
    pub(super) force: bool,
}

/// a prepared deployment with its plan: `plan` is what sakctl shows, the apply applies its diffs,
/// so the markers of the server are read once for both. A destroy applies no diffs, it removes
/// what it finds
pub struct Planned {
    pub plan: PlanResponse,
    prepared: Prepared,
    diffs: Vec<ResourceDiff>,
}

impl Prepared {
    /// what the playbook gets; the provisioner adds the addresses of the server and the device of
    /// the volume as it finds them
    pub(super) fn provision_run(&self, provisioning: &Provisioning) -> ProvisionRun {
        let (admin_email, smtp) = match &provisioning.users {
            Users::Single => (String::new(), None),
            Users::Multi { smtp, admin_email } => (admin_email.clone(), Some(smtp.clone())),
        };
        ProvisionRun {
            name: self.spec.name.clone(),
            dns_mode: self.spec.dns_mode,
            root_domain: provisioning.root_domain.clone(),
            api_public_key: provisioning.api_public_key.clone(),
            host: String::new(),
            host_ipv6: None,
            client_key: provisioning.client_key.clone(),
            host_key: provisioning.host_key.clone(),
            storage_device: String::new(),
            admin_email,
            smtp,
            admin_password_hash: provisioning.admin_password_hash.clone(),
            acme_ca: acme_directory(provisioning.acme_ca).map(String::from),
        }
    }

    /// the resources a create or deploy plans and applies: those of the spec and, after the
    /// server and the volume, the roles of the playbook on the server (`core::ansible_roles`),
    /// whose provisioner `provisioner` makes of the run and the force. A destroy has no roles,
    /// they go with the server; and it creates nothing, so no resource of its plan is refused for
    /// a recreate (a volume smaller than the size of today is no reason)
    pub(super) fn registry(
        &self,
        provisioner: impl FnOnce(ProvisionRun, bool) -> AnsibleRolesProvisioner,
    ) -> Result<sak_cloud::api::Registry, String> {
        let mut registry = self
            .spec
            .registry(&self.runtime)
            .map_err(|error| error.to_string())?;
        let Some(provisioning) = self.purpose.provisioning() else {
            let spec = &self.spec;
            let resources = [
                spec.ssh_key().resource_ref(),
                spec.data_volume().resource_ref(),
                spec.ipv4().resource_ref(),
                spec.ipv6().resource_ref(),
                spec.firewall().resource_ref(),
                spec.server().resource_ref(),
            ]
            .into_iter()
            .chain(
                spec.dns_records()
                    .into_iter()
                    .map(|record| record.resource_ref()),
            );
            for resource in resources {
                registry = registry.recreatable(resource);
            }
            return Ok(registry);
        };
        registry.register::<AnsibleRoles, AnsibleRolesProvisioner>(Arc::new(provisioner(
            self.provision_run(provisioning),
            provisioning.force,
        )));
        registry
            .add(AnsibleRoles {
                name: self.spec.resource_name(),
                server: self.spec.server().as_lookup(),
                volume: self.spec.data_volume().as_lookup(),
            })
            .map_err(|error| error.to_string())?;
        Ok(registry)
    }
}

pub(super) fn failed(provider: ProviderType, error: impl ToString) -> String {
    message("shared.deployments.failed", provider, error)
}

/// a failure of the provider in a plan or an apply. A resource of a declared name that belongs
/// to someone else, and a resource that must stay but would be created anew, each have an exit
/// code of their own; nothing of them was changed. A failed playbook says what failed in its own
/// words, without the name of the provider
pub(super) fn cloud_failure(
    key: &str,
    provider: ProviderType,
    error: sak_cloud::api::Error,
) -> SakctlError {
    match error {
        sak_cloud::api::Error::NotOwned { .. } => SakctlError::new(
            t!("shared.deployments.not_owned", error = error.to_string()),
            EXIT_NOT_OWNED,
        ),
        sak_cloud::api::Error::RecreateRefused { .. } => SakctlError::new(
            t!(
                "shared.deployments.recreate_refused",
                error = error.to_string()
            ),
            EXIT_RECREATE_REFUSED,
        ),
        sak_cloud::api::Error::Provisioning { message, .. } => message.into(),
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
                    let runtime = HetznerProviderRuntime::from_api(api.clone());
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
    ) -> Result<(DeploymentRequest, PlanResponse), SakctlError> {
        let deployment = self
            .completed(
                tokens.clone(),
                DeploymentRequest::as_it_is(name, password, location),
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
        deployment: DeploymentRequest,
        smtp_password: &str,
    ) -> Result<DeploymentRequest, SakctlError> {
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
    ) -> Result<DiscoveredDeployment, SakctlError> {
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
    ) -> Result<Detected, SakctlError> {
        let deployment = self.find(&tokens, name).await?;
        let label = deployment.user_mode();
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
    ) -> Result<(), SakctlError> {
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
    ) -> Result<crate::core::ssh::SshTarget, SakctlError> {
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
    ) -> Result<Endpoint, SakctlError> {
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
    ) -> Result<(String, crate::core::config::RunningConfig), SakctlError> {
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
        let runtime = HetznerProviderRuntime::from_api(Arc::new(HetznerApi::new(&token)));
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
    /// hetzner project. The request becomes its typed `Purpose` here
    pub async fn prepare(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &DeploymentRequest,
    ) -> Result<Prepared, SakctlError> {
        let name = checked_request(deployment)?;
        let (password, intent) = (deployment.password.as_str(), deployment.intent);
        let (provider, api) = hetzner_api(tokens)?;
        let existing = discover(&api)
            .await
            .map_err(|error| failed(provider, error))?
            .into_iter()
            .find(|deployment| deployment.name == name);
        let (purpose, acme_ca) = users_and_acme_ca(deployment, existing.as_ref())?;
        let (dns_mode, root_domain) = resolve_dns(
            name,
            intent,
            deployment.dns_mode,
            deployment.root_domain.as_deref(),
            existing.as_ref().and_then(DiscoveredDeployment::dns_mode),
            existing
                .as_ref()
                .and_then(DiscoveredDeployment::root_domain),
        )?;
        // one read of the zones gives the root domains, the records of the deployment and the
        // records in the way
        let listings = HetznerDnsZoneProvisioner::new(api.clone())
            .list_all()
            .await
            .map_err(|error| failed(provider, error))?;
        let records = deployment_records(&listings, name);
        let left_out = existing
            .as_ref()
            .map(|existing| existing.left_out.clone())
            .unwrap_or_default();
        let location = location_of(
            name,
            password,
            intent,
            existing,
            deployment.location,
            &records,
        )?;
        let runtime = HetznerProviderRuntime::from_api(api);
        // a create or deploy gets its root domain checked against the zones; a destroy has none
        let purpose = purpose.then(|users| {
            let domains: Vec<RootDomain> = listings.iter().map(RootDomain::of).collect();
            Ok::<_, SakctlError>(Settings {
                users,
                root_domain: check_root_domain(&domains, root_domain.as_deref(), name, dns_mode)?,
            })
        })?;
        let (spec, keys) = laid_out(
            name,
            password,
            location,
            dns_mode,
            purpose.provisioning(),
            acme_ca,
        )?;
        let stale_records = stale_records(&records, &spec.dns_records(), &spec.identity());
        if intent != Intent::Destroy {
            check_records_in_the_way(&listings, &spec)?;
        }
        // a destroy provisions nothing, the argon2id of the admin password would be for nothing
        let purpose = purpose.then(|settings| {
            Ok::<_, SakctlError>(Provisioning {
                root_domain: settings.root_domain,
                users: settings.users,
                acme_ca,
                client_key: keys.client_key,
                host_key: keys.host_key,
                api_public_key: keys.api_public_key,
                admin_password_hash: crate::core::keys::admin_password_hash(name, password)?,
                force: deployment.force,
            })
        })?;
        Ok(Prepared {
            provider,
            runtime,
            spec,
            stale_records,
            purpose,
            left_out,
        })
    }

    /// prepares the deployment and plans it: its resources diffed against the project, the roles
    /// of the server against its markers, read through ssh in the image of the tools
    pub async fn plan(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &DeploymentRequest,
        container: &ContainerRuntime,
    ) -> Result<PlanResponse, SakctlError> {
        let prepared = self.prepare(tokens, deployment).await?;
        Ok(self.plan_prepared(prepared, container).await?.plan)
    }

    /// the plan of a deployment prepared already, kept for its apply
    pub async fn plan_prepared(
        &self,
        prepared: Prepared,
        container: &ContainerRuntime,
    ) -> Result<Planned, SakctlError> {
        let intent = prepared.purpose.intent();
        let delete_data = matches!(prepared.purpose, Purpose::Destroy { delete_data: true });
        // a destroy has no roles and so nothing to force
        let force = prepared
            .purpose
            .provisioning()
            .is_some_and(|provisioning| provisioning.force);
        let volume = prepared.spec.data_volume().name;
        let mut registry = prepared.registry(|run, force| AnsibleRolesProvisioner {
            run,
            runtime: container.clone(),
            force,
            server_changed: Default::default(),
            events: None,
        })?;
        // a destroy creates nothing: what a create or deploy must not do does not stop its plan
        let diffs = match prepared.purpose {
            Purpose::Destroy { .. } => registry.compare_all().await,
            Purpose::Create { .. } | Purpose::Deploy { .. } => registry.diff_all().await,
        }
        .map_err(|error| {
            cloud_failure("shared.deployments.plan_failed", prepared.provider, error)
        })?;
        let plan = PlanResponse {
            name: prepared.spec.name.clone(),
            public_key: prepared.spec.public_key.clone(),
            resources: diffs
                .iter()
                .map(|diff| {
                    let keep =
                        intent == Intent::Destroy && !delete_data && diff.resource.name == volume;
                    let provisioning = is_provisioning(&diff.resource);
                    let kind = match ResourceKind::of(&diff.resource) {
                        Some(kind) => PlannedKind::Resource(kind),
                        None if provisioning => PlannedKind::Provisioning,
                        // the registry is built here, of the kinds above only
                        None => {
                            return Err(SakctlError::from(format!(
                                "{} is of no kind sakctl knows",
                                diff.resource.log_text
                            )));
                        }
                    };
                    Ok(PlannedResource {
                        resource: diff.resource.log_text.clone(),
                        kind,
                        name: diff.resource.name.clone(),
                        resource_status: ResourceStatus::of(diff.status),
                        // the playbook runs when a role is not up to date, an up to date one only
                        // on a forced deploy
                        action: if provisioning {
                            Some(if diff.status == DiffStatus::UpToDate && !force {
                                PlanAction::RunsWhenForced
                            } else {
                                PlanAction::WillRun
                            })
                        } else {
                            action(intent, diff.status, diff.needs_recreate(), keep, false)
                        },
                        working: working(intent, diff.status, diff.needs_recreate(), false),
                        status: diff.status,
                        keep,
                        // a destroy removes everything, so the changes are of no interest
                        changes: match intent {
                            Intent::Destroy => Vec::new(),
                            Intent::Create | Intent::Deploy => diff
                                .changes
                                .iter()
                                .map(|change| change.log_text())
                                .collect(),
                        },
                    })
                })
                .chain(prepared.stale_records.iter().map(|record| {
                    Ok(PlannedResource {
                        resource: record.log_text(),
                        kind: PlannedKind::Resource(ResourceKind::DnsRecord),
                        name: record.resource_ref().name,
                        resource_status: ResourceStatus::UpToDate,
                        action: action(intent, DiffStatus::UpToDate, false, false, true),
                        working: working(intent, DiffStatus::UpToDate, false, true),
                        status: DiffStatus::UpToDate,
                        keep: false,
                        changes: Vec::new(),
                    })
                }))
                // a duplicate the deployment does not use: a deploy and a destroy leave it
                .chain(prepared.left_out.iter().map(|left_out| {
                    Ok(PlannedResource {
                        resource: format!("{} '{}'", key(&left_out.kind), left_out.name),
                        kind: PlannedKind::Resource(left_out.kind),
                        name: left_out.name.clone(),
                        resource_status: ResourceStatus::of(DiffStatus::Duplicate),
                        action: (intent == Intent::Destroy).then_some(PlanAction::WillKeep),
                        working: Working::Check,
                        status: DiffStatus::Duplicate,
                        keep: intent == Intent::Destroy,
                        changes: vec![t!("shared.plan.left_out").to_string()],
                    })
                }))
                .collect::<Result<Vec<_>, SakctlError>>()?,
        };
        Ok(Planned {
            plan,
            prepared,
            diffs,
        })
    }

    /// prepares the deployment and applies it. A create or deploy applies the resources in the
    /// order of their dependencies, the roles of the playbook after the server and the volume
    /// (`core::ansible_roles`), then removes the dns records of an old dns mode or root domain.
    /// After a provisioning sakctl sets the version label of the server and waits for the api.
    /// Each resource, line and task goes to `on_event`. What is up-to-date stays as it is,
    /// so the next run finishes a run that failed. A destroy removes the dns records of the
    /// deployment, then every resource but the data volume (also that with `delete_data`),
    /// dependents first
    pub async fn prepare_and_apply(
        &self,
        tokens: Vec<(ProviderType, String)>,
        deployment: &DeploymentRequest,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<(), SakctlError> {
        let prepared = self.prepare(tokens, deployment).await?;
        self.apply_prepared(prepared, None, container, on_event)
            .await
    }

    /// applies a deployment planned already, as `prepare_and_apply` does, with the diffs of its
    /// plan
    pub async fn apply(
        &self,
        planned: Planned,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<(), SakctlError> {
        let Planned {
            prepared, diffs, ..
        } = planned;
        self.apply_prepared(prepared, Some(diffs), container, on_event)
            .await
    }

    /// the apply of `prepare_and_apply` and `apply`: a create or deploy applies the `planned`
    /// diffs, or plans first without them
    async fn apply_prepared(
        &self,
        prepared: Prepared,
        planned: Option<Vec<ResourceDiff>>,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<(), SakctlError> {
        let provisioning = match &prepared.purpose {
            Purpose::Create { provisioning } | Purpose::Deploy { provisioning } => provisioning,
            Purpose::Destroy { delete_data } => {
                let data_volume = if *delete_data {
                    DataVolume::Delete
                } else {
                    DataVolume::Keep
                };
                return self.destroy(&prepared, data_volume, on_event).await;
            }
        };
        let provisioned = self
            .apply_resources(&prepared, planned, container, on_event)
            .await?;
        self.remove_records(&prepared, on_event)
            .await
            .map_err(|error| {
                cloud_failure("shared.deployments.create_failed", prepared.provider, error)
            })?;
        if provisioned {
            self.after_provisioning(&prepared, provisioning, on_event)
                .await?;
        }
        Ok(())
    }

    /// a destroy: the dns records of the deployment, then every resource but the data volume
    /// (also that with `DataVolume::Delete`), dependents first
    async fn destroy(
        &self,
        prepared: &Prepared,
        data_volume: DataVolume,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<(), SakctlError> {
        let destroy_failed = |error| {
            cloud_failure(
                "shared.deployments.destroy_failed",
                prepared.provider,
                error,
            )
        };
        self.remove_records(prepared, on_event)
            .await
            .map_err(destroy_failed)?;
        let mut registry = prepared
            .spec
            .destroy_registry(&prepared.runtime, data_volume)
            .map_err(|error| error.to_string())?;
        let mut report = |event: ApplyEvent| {
            let event = match event {
                ApplyEvent::Started(resource) => DeploymentEvent::Started {
                    resource: resource.log_text,
                },
                ApplyEvent::Destroyed(resource, gone) => DeploymentEvent::Destroyed {
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
            .map_err(destroy_failed)?;
        Ok(())
    }

    /// the resources of a create or deploy in the order of their dependencies, the roles of the
    /// playbook after the server and the volume. Their events and those of the playbook go
    /// through one channel to `on_event` while the registry applies, the `planned` diffs when
    /// there are, else after a plan of its own. Whether the roles ran
    async fn apply_resources(
        &self,
        prepared: &Prepared,
        planned: Option<Vec<ResourceDiff>>,
        container: &ContainerRuntime,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<bool, SakctlError> {
        // the channel closes when the registry and its report are gone
        let (sender, mut receiver) = tokio::sync::mpsc::unbounded_channel::<DeploymentEvent>();
        let server_changed = Arc::new(AtomicBool::new(false));
        let mut registry = prepared.registry(|run, force| AnsibleRolesProvisioner {
            run,
            runtime: container.clone(),
            force,
            server_changed: server_changed.clone(),
            events: Some(sender.clone()),
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
                        ApplyEvent::Started(resource) => DeploymentEvent::Started {
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
                            DeploymentEvent::Applied {
                                resource: result.resource.log_text,
                                text: applied_key(result.applied),
                            }
                        }
                        ApplyEvent::Destroyed(..) => return,
                    };
                    let _ = sender.send(event);
                };
                let result = match planned {
                    Some(diffs) => registry.apply_planned_with(diffs, &mut report).await,
                    None => registry.apply_all_with(&mut report).await,
                };
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
        result.map_err(|error| {
            cloud_failure("shared.deployments.create_failed", prepared.provider, error)
        })?;
        Ok(provisioned.load(Ordering::SeqCst))
    }

    /// after the roles ran: sets the version label of the server, then waits until the deployment
    /// answers as its users reach it, through its ingress with its certificate. A backend that
    /// did not start or a missing certificate stops the apply here
    async fn after_provisioning(
        &self,
        prepared: &Prepared,
        provisioning: &Provisioning,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> Result<(), SakctlError> {
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
        // the server runs the images of this version now, its version label says so; before the
        // wait, which a next deploy does not repeat: the markers tell it the provisioning is done
        set_server_labels(
            &prepared.runtime.api,
            server.id,
            &[(VERSION_LABEL, Some(crate::core::VERSION))],
        )
        .await
        .map_err(|error| failed(prepared.provider, error))?;
        let address = server
            .public_ipv4
            .as_deref()
            .and_then(|host| host.parse::<std::net::IpAddr>().ok());
        let api = crate::core::config::api_url(&sak_cloud::hetzner::deployment::base_domain(
            &prepared.spec.name,
            prepared.spec.dns_mode,
            &provisioning.root_domain,
        ));
        on_event(DeploymentEvent::Provisioning {
            message: t!("shared.deployments.waiting_for_api", url = api.as_str()).to_string(),
        });
        crate::core::config::wait_for_api(
            &api,
            address,
            acme_trust(provisioning.acme_ca),
            crate::core::config::API_WAIT,
            std::time::Duration::from_secs(5),
        )
        .await?;
        Ok(())
    }

    /// removes the stale dns records of the deployment and reports each when it starts and when
    /// it is done
    pub(super) async fn remove_records(
        &self,
        prepared: &Prepared,
        on_event: &mut (dyn FnMut(DeploymentEvent) + Send),
    ) -> sak_cloud::api::Result<()> {
        for record in &prepared.stale_records {
            on_event(DeploymentEvent::Started {
                resource: record.log_text(),
            });
        }
        remove_records(
            &prepared.runtime,
            &prepared.stale_records,
            &mut |record, gone| {
                on_event(DeploymentEvent::Destroyed {
                    resource: record.log_text(),
                    text: destroyed_key(gone),
                })
            },
        )
        .await
    }
}

/// the checks of a request that need nothing of the project: the name, a location sakctl knows
/// (a destroy needs none) and the password, long enough, the same two times and, for a create,
/// hard to guess. Gives the name, trimmed
fn checked_request(deployment: &DeploymentRequest) -> Result<&str, SakctlError> {
    let DeploymentRequest {
        name,
        password,
        password_repeat,
        location,
        intent,
        ..
    } = deployment;
    let name = checked_name(name)?;
    // a location Hetzner added after this release can be read, but nothing is created in it; a
    // destroy needs no location
    if *location == Location::unknown && *intent != Intent::Destroy {
        return Err(SakctlError::new(
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
    if *intent == Intent::Create
        && let Some(hint) = crate::core::keys::weak_password(name, password)
    {
        return Err(t!("shared.deployments.weak_password", hint = hint)
            .to_string()
            .into());
    }
    Ok(name)
}

/// the api of the first hetzner project of `tokens`
fn hetzner_api(
    tokens: Vec<(ProviderType, String)>,
) -> Result<(ProviderType, Arc<HetznerApi>), SakctlError> {
    let Some((provider, token)) = tokens
        .into_iter()
        .find(|(provider, _)| *provider == ProviderType::Hetzner)
    else {
        return Err(t!("shared.deployments.no_provider").to_string().into());
    };
    Ok((provider, Arc::new(HetznerApi::new(&token))))
}

/// the purpose of the request with who signs in for a create or deploy, and the acme ca: each the
/// one given, else the one on the `existing` deployment (a deploy keeps them); the ca else
/// production
fn users_and_acme_ca(
    deployment: &DeploymentRequest,
    existing: Option<&DiscoveredDeployment>,
) -> Result<(Purpose<Users>, AcmeCa), SakctlError> {
    let user_mode = deployment
        .user_mode
        .or_else(|| existing.and_then(DiscoveredDeployment::user_mode));
    let acme_ca = deployment
        .acme_ca
        .or_else(|| existing.and_then(DiscoveredDeployment::acme_ca))
        .unwrap_or_default();
    let users = || {
        users(
            user_mode,
            deployment.smtp.as_ref(),
            deployment.admin_email.as_deref(),
        )
    };
    let purpose = match deployment.intent {
        Intent::Destroy => Purpose::Destroy {
            delete_data: deployment.delete_data,
        },
        Intent::Create => Purpose::Create {
            provisioning: users()?,
        },
        Intent::Deploy => Purpose::Deploy {
            provisioning: users()?,
        },
    };
    Ok((purpose, acme_ca))
}

/// where the deployment goes, once its existence fits the intent: a create needs a free name, a
/// deploy an existing deployment. The password is checked against the `existing` deployment, or
/// for a destroy of what is left against the hashes of its `records`. An existing deployment
/// stays where it is; the `given` location counts only when none of its server, volume and
/// addresses exists
fn location_of(
    name: &str,
    password: &str,
    intent: Intent,
    existing: Option<DiscoveredDeployment>,
    given: Location,
    records: &[DeploymentRecord],
) -> Result<Location, SakctlError> {
    match (intent, existing) {
        (Intent::Create, Some(_)) => Err(t!("shared.deployments.name_taken", name = name)
            .to_string()
            .into()),
        (Intent::Create, None) => Ok(given),
        (Intent::Deploy, None) => Err(t!("shared.deployments.not_found", name = name)
            .to_string()
            .into()),
        // destroyed already, maybe with records left: check the password against their hashes.
        // A destroy removes only records with the hash of the password (the identity of the
        // spec), so records without one stay and need no check; without records nothing of the
        // deployment is left to change, the plan stops at a resource of someone else
        (Intent::Destroy, None) => {
            let hashes: Vec<String> = records
                .iter()
                .filter_map(|record| record.labels.get(PASSWORD_HASH_LABEL).cloned())
                .collect();
            if !hashes.is_empty() {
                check_password(&hashes, None, name, password)?;
            }
            Ok(given)
        }
        (Intent::Deploy | Intent::Destroy, Some(existing)) => {
            verify_password(&existing, name, password)?;
            Ok(existing
                .location()
                .map_err(|resources| {
                    t!(
                        "shared.deployments.mixed_locations",
                        name = name,
                        resources = resources
                    )
                    .to_string()
                })?
                .unwrap_or(given))
        }
    }
}

/// what a create or deploy is set up with, its root domain checked against the zones; the keys
/// come with the spec
struct Settings {
    users: Users,
    root_domain: String,
}

/// the keys derived from the name and the password that a provisioning needs
struct Keys {
    client_key: DerivedKey,
    host_key: DerivedKey,
    /// the public half of the api key
    api_public_key: String,
}

/// the keys of `name` and `password` and the resource group they lay out in `location`: every
/// resource with the dns mode and the hash of the password, the server with the cloud-init of its
/// host key. A create or deploy also puts its root domain, user mode, acme ca and the version on
/// every resource, so a deploy keeps them; `settings` is none for a destroy
fn laid_out(
    name: &str,
    password: &str,
    location: Location,
    dns_mode: DnsMode,
    settings: Option<&Settings>,
    acme_ca: AcmeCa,
) -> Result<(DeploymentSpec, Keys), SakctlError> {
    let client_key = crate::core::keys::derive_key(name, password)?;
    let host_key = crate::core::keys::derive_host_key(name, password)?;
    let public_key = client_key.public_key();
    let password_hash = crate::core::keys::password_hash(name, password)?;
    let api_public_key = crate::core::keys::api_key(name, password)?.public_key();
    let mut spec = DeploymentSpec::new(name, &public_key, location, server_type_for(location))
        .map_err(|error| error.to_string())?
        .with_dns_mode(dns_mode)
        .with_password_hash(&password_hash);
    if let Some(settings) = settings {
        spec = spec
            .with_root_domain(&settings.root_domain)
            .with_user_mode(settings.users.mode())
            .with_acme_ca(acme_ca)
            .with_version(crate::core::VERSION);
    }
    spec.user_data = user_data(&host_key);
    let keys = Keys {
        client_key,
        host_key,
        api_public_key,
    };
    Ok((spec, keys))
}

/// a record of another owner, of any type, on a name of the deployment stops a create or deploy
/// here, before anything is created
fn check_records_in_the_way(
    listings: &[HetznerDnsZoneListing],
    spec: &DeploymentSpec,
) -> Result<(), SakctlError> {
    let in_the_way = foreign_records(listings, spec);
    if in_the_way.is_empty() {
        return Ok(());
    }
    Err(SakctlError::new(
        t!(
            "shared.deployments.not_owned",
            error = t!(
                "shared.deployments.records_in_the_way",
                records = in_the_way.join(", ")
            )
        ),
        EXIT_NOT_OWNED,
    ))
}
